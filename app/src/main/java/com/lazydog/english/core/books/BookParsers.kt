package com.lazydog.english.core.books

import com.lazydog.english.core.network.HtmlText
import com.lazydog.english.domain.books.BookLanguage
import com.lazydog.english.domain.books.ParsedBook
import com.lazydog.english.domain.books.ParsedChapter
import com.lazydog.english.domain.books.TextBookSplitter
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * TXT 导入（设计文档 §3.2）：先认编码，再切章。
 *
 * 编码顺序：BOM（UTF-8 / UTF-16）→ 严格按 UTF-8 解，解得通就是 → 否则当 GB18030（覆盖 GBK、GB2312）。
 * 中文 TXT 大量是 GBK，按 UTF-8 硬解就是一屏乱码。
 */
object TextBookParser {

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("GB18030"))
        }
    }

    fun parse(bytes: ByteArray, fileName: String): ParsedBook? {
        val text = decode(bytes)
        val chapters = TextBookSplitter.split(text)
        if (chapters.isEmpty()) return null
        return ParsedBook(
            title = fileName.substringBeforeLast('.').trim().ifBlank { "未命名" },
            author = "",
            language = BookLanguage.detect(text.take(5_000)),
            chapters = chapters,
        )
    }
}

/**
 * EPUB 导入（设计文档 §3.2）：`META-INF/container.xml` → OPF（元数据、manifest、spine、封面）
 * → 目录（EPUB 3 的 nav 或 EPUB 2 的 toc.ncx）给章节起名 → 按 spine 顺序把每个 XHTML 转成段落。
 *
 * 一个 spine 文件就是一章。字数太少、又没有目录标题的（封面页、版权页）跳过。
 * 带 DRM 的 EPUB 内容是加密的，转出来没有可读文字，整本返回 null。
 */
object EpubParser {

    /** 少于这么多字、目录里也没它的，当成封面页、版权页之类跳过。 */
    private const val MIN_CHAPTER_CHARS = 200

    fun parse(file: File): ParsedBook? = runCatching {
        ZipFile(file).use { zip ->
            fun read(path: String): ByteArray? = zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }

            if (zip.getEntry("META-INF/encryption.xml") != null && isDrm(read("META-INF/encryption.xml"))) return null
            val container = xml(read("META-INF/container.xml") ?: return null) ?: return null
            val opfPath = (container.getElementsByTagNameNS("*", "rootfile").item(0) as? Element)
                ?.getAttribute("full-path")?.takeIf { it.isNotBlank() } ?: return null
            val opf = xml(read(opfPath) ?: return null) ?: return null
            val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

            val title = opf.firstText("title").ifBlank { file.nameWithoutExtension }
            val author = opf.firstText("creator")
            val manifest = manifestOf(opf)
            val spine = (0 until opf.getElementsByTagNameNS("*", "itemref").length)
                .mapNotNull { (opf.getElementsByTagNameNS("*", "itemref").item(it) as? Element)?.getAttribute("idref") }
                .mapNotNull { manifest[it] }
                .filter { it.mediaType.contains("html") }

            val tocTitles = tocTitles(opf, manifest, base, ::read)
            val chapters = spine.mapIndexedNotNull { index, item ->
                val path = resolve(base, item.href)
                val bytes = read(path) ?: return@mapIndexedNotNull null
                val html = String(bytes, Charsets.UTF_8)
                val paragraphs = HtmlText.paragraphs(html)
                val tocTitle = tocTitles[path]
                if (paragraphs.sumOf { it.length } < MIN_CHAPTER_CHARS && tocTitle == null) return@mapIndexedNotNull null
                if (paragraphs.isEmpty()) return@mapIndexedNotNull null
                val heading = HEADING.find(html)?.groupValues?.get(2)?.let(HtmlText::inline)?.takeIf { it.isNotBlank() }
                ParsedChapter(title = tocTitle ?: heading ?: "第 ${index + 1} 节", paragraphs = paragraphs)
            }
            if (chapters.isEmpty()) return null

            val cover = coverOf(opf, manifest)?.let { item -> read(resolve(base, item.href))?.let { it to item.href.substringAfterLast('.', "jpg") } }
            val language = opf.firstText("language").let { lang ->
                when {
                    lang.startsWith("zh", ignoreCase = true) -> BookLanguage.Chinese
                    lang.startsWith("en", ignoreCase = true) -> BookLanguage.English
                    else -> BookLanguage.detect(chapters.take(3).flatMap { it.paragraphs }.joinToString("").take(5_000))
                }
            }
            ParsedBook(
                title = title,
                author = author,
                language = language,
                chapters = chapters,
                cover = cover?.first,
                coverExtension = cover?.second.orEmpty(),
            )
        }
    }.getOrNull()

    internal data class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String)

    private val HEADING = Regex("""<(h[1-3])\b[^>]*>(.*?)</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    internal fun xml(bytes: ByteArray): Document? = runCatching {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 只读本地文件，也不让 DTD 和外部实体有机会去读别的东西。
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(bytes.inputStream())
    }.getOrNull()

    private fun Document.firstText(localName: String): String =
        getElementsByTagNameNS("*", localName).item(0)?.textContent.orEmpty().trim()

    private fun manifestOf(opf: Document): Map<String, ManifestItem> {
        val items = opf.getElementsByTagNameNS("*", "item")
        return (0 until items.length).mapNotNull { i ->
            val e = items.item(i) as? Element ?: return@mapNotNull null
            ManifestItem(e.getAttribute("id"), e.getAttribute("href"), e.getAttribute("media-type"), e.getAttribute("properties"))
        }.associateBy { it.id }
    }

    private fun coverOf(opf: Document, manifest: Map<String, ManifestItem>): ManifestItem? {
        manifest.values.firstOrNull { "cover-image" in it.properties }?.let { return it }
        val metas = opf.getElementsByTagNameNS("*", "meta")
        val coverId = (0 until metas.length).mapNotNull { metas.item(it) as? Element }
            .firstOrNull { it.getAttribute("name") == "cover" }?.getAttribute("content")
        return coverId?.let { manifest[it] }?.takeIf { it.mediaType.startsWith("image/") }
    }

    /** 章节文件路径 → 目录里的标题。EPUB 3 的 nav 优先，没有就读 EPUB 2 的 NCX。 */
    private fun tocTitles(
        opf: Document,
        manifest: Map<String, ManifestItem>,
        base: String,
        read: (String) -> ByteArray?,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        manifest.values.firstOrNull { "nav" in it.properties }?.let { nav ->
            val navPath = resolve(base, nav.href)
            val html = read(navPath)?.let { String(it, Charsets.UTF_8) } ?: return@let
            val navBase = navPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            NAV_LINK.findAll(html).forEach { m ->
                val target = resolve(navBase, m.groupValues[1].substringBefore('#'))
                val text = HtmlText.inline(m.groupValues[2])
                if (text.isNotBlank()) out.putIfAbsent(target, text)
            }
        }
        if (out.isNotEmpty()) return out
        val ncxId = (opf.getElementsByTagNameNS("*", "spine").item(0) as? Element)?.getAttribute("toc")
        val ncxItem = manifest[ncxId] ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" } ?: return out
        val ncxPath = resolve(base, ncxItem.href)
        val ncx = read(ncxPath)?.let(::xml) ?: return out
        val ncxBase = ncxPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val points = ncx.getElementsByTagNameNS("*", "navPoint")
        for (i in 0 until points.length) {
            val point = points.item(i) as? Element ?: continue
            val label = (point.getElementsByTagNameNS("*", "text").item(0))?.textContent.orEmpty().trim()
            val src = (point.getElementsByTagNameNS("*", "content").item(0) as? Element)?.getAttribute("src").orEmpty()
            if (label.isNotBlank() && src.isNotBlank()) out.putIfAbsent(resolve(ncxBase, src.substringBefore('#')), label)
        }
        return out
    }

    private val NAV_LINK = Regex("""<a\b[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** 相对路径拼到 [base] 上，处理 `../`；去掉 URL 编码的空格。 */
    internal fun resolve(base: String, href: String): String {
        val parts = (base + java.net.URLDecoder.decode(href, "UTF-8")).split('/').fold(mutableListOf<String>()) { acc, part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (acc.isNotEmpty()) acc.removeAt(acc.lastIndex)
                else -> acc += part
            }
            acc
        }
        return parts.joinToString("/")
    }

    /** encryption.xml 只加密字体（IDPF 字体混淆）不算 DRM；加密了正文才算。 */
    private fun isDrm(bytes: ByteArray?): Boolean {
        val text = bytes?.let { String(it, Charsets.UTF_8) } ?: return false
        val obfuscationOnly = "http://www.idpf.org/2008/embedding" in text || "http://ns.adobe.com/pdf/enc#RC" in text
        return !obfuscationOnly && ("EncryptedData" in text)
    }
}
