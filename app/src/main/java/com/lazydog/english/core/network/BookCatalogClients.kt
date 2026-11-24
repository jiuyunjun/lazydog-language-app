package com.lazydog.english.core.network

import com.lazydog.english.domain.books.Ao3Rating
import com.lazydog.english.domain.books.CatalogEntry
import com.lazydog.english.domain.books.CatalogResult
import com.lazydog.english.domain.books.CatalogSource
import com.lazydog.english.domain.books.ParsedChapter
import java.io.IOException
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 在线书库的三个客户端（D-098 R3）。
 *
 * 都用同一个如实的 UA：书库会区分自动程序，限流时就老实报"忙"，**不伪装成浏览器去绕**。
 * 在中国大陆这三个站通常都要代理。
 */
internal object CatalogHttp {
    const val USER_AGENT = "LazyDogEnglishReader/1.0 (Android reading app)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .eventListenerFactory { HttpTimingListener() }
        .build()

    sealed interface Response {
        data class Ok(val bytes: ByteArray, val contentType: String) : Response
        /** 429 / 503：对方限流或维护，过一会儿再试。 */
        data class Busy(val code: Int) : Response
        data class Failed(val reason: String) : Response
    }

    fun get(url: String, op: String): Response {
        val request = runCatching {
            Request.Builder().url(url).header("User-Agent", USER_AGENT)
                .tag(CallHooks::class.java, CallHooks(op = op)).get().build()
        }.getOrNull() ?: return Response.Failed("地址不对")
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 429 || response.code == 503 -> Response.Busy(response.code)
                    !response.isSuccessful -> Response.Failed("HTTP ${response.code}")
                    else -> Response.Ok(response.body?.bytes() ?: ByteArray(0), response.header("Content-Type").orEmpty())
                }
            }
        } catch (e: IOException) {
            Response.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    fun count(n: Long): String = NumberFormat.getIntegerInstance().format(n)
}

/** 下载结果：成功拿到 EPUB 字节，或者对方忙（让用户去浏览器下），或者失败。 */
sealed interface CatalogDownload {
    data class Epub(val bytes: ByteArray) : CatalogDownload
    data class Busy(val reason: String) : CatalogDownload
    data class Failed(val reason: String) : CatalogDownload
}

/**
 * 古腾堡计划，经 Gutendex 的公开 JSON 接口检索（字段见 gutendex.com 文档）。
 * 不带关键词时按下载量排，就是"最受欢迎"。下载取 EPUB；书目里没给就按古腾堡缓存目录的固定路径拼。
 */
class GutendexClient(private val base: String = BASE) {

    suspend fun download(entry: CatalogEntry, epubUrl: String): CatalogDownload = withContext(Dispatchers.IO) {
        val url = epubUrl.ifBlank { fallbackEpubUrl(entry.id) }
        when (val response = CatalogHttp.get(url, op = "古腾堡下载")) {
            is CatalogHttp.Response.Ok -> if (isZip(response.bytes)) CatalogDownload.Epub(response.bytes)
            else CatalogDownload.Failed("下回来的不是 EPUB")
            is CatalogHttp.Response.Busy -> CatalogDownload.Busy("古腾堡正忙或在维护")
            is CatalogHttp.Response.Failed -> CatalogDownload.Failed("下载失败：${response.reason}")
        }
    }

    @Serializable
    private data class Page(val results: List<Book> = emptyList())

    @Serializable
    private data class Book(
        val id: Long = 0,
        val title: String = "",
        val authors: List<Person> = emptyList(),
        val formats: Map<String, String> = emptyMap(),
        val download_count: Long = 0,
        val copyright: Boolean? = null,
    )

    @Serializable
    private data class Person(val name: String = "")

    companion object {
        const val BASE = "https://gutendex.com"
        private val json = Json { ignoreUnknownKeys = true }

        fun fallbackEpubUrl(id: String) = "https://www.gutenberg.org/cache/epub/$id/pg$id.epub"

        /** 结果 → 条目；有版权的（极少数）不列。EPUB 地址放进 [epubUrls]。 */
        internal fun parseSearch(body: String, epubUrls: MutableMap<String, String>? = null): List<CatalogEntry>? {
            val page = runCatching { json.decodeFromString(Page.serializer(), body) }.getOrNull() ?: return null
            return page.results.filter { it.copyright != true && it.title.isNotBlank() }.map { book ->
                val id = book.id.toString()
                book.formats["application/epub+zip"]?.let { epubUrls?.put(id, it) }
                CatalogEntry(
                    source = CatalogSource.Gutenberg,
                    id = id,
                    title = book.title,
                    // Gutendex 的作者名是「姓, 名」，翻回「名 姓」。
                    author = book.authors.joinToString(" / ") { person ->
                        person.name.split(", ", limit = 2).let { if (it.size == 2) "${it[1]} ${it[0]}" else person.name }
                    },
                    meta = "下载 ${CatalogHttp.count(book.download_count)} 次",
                    webUrl = "https://www.gutenberg.org/ebooks/$id",
                )
            }
        }
    }

    /** 检索给出的 EPUB 地址，按书号存，下载时用。 */
    private val epubUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 检索；顺带记下每本书的 EPUB 地址，下载时用。 */
    suspend fun search(query: String): CatalogResult = withContext(Dispatchers.IO) {
        val url = if (query.isBlank()) "$base/books/?languages=en&sort=popular"
        else "$base/books/?languages=en&search=${CatalogHttp.encode(query.trim())}"
        when (val response = CatalogHttp.get(url, op = "古腾堡检索")) {
            is CatalogHttp.Response.Ok -> parseSearch(String(response.bytes, Charsets.UTF_8), epubUrls)
                ?.let { if (it.isEmpty()) CatalogResult(failure = "没找到，换个英文书名或作者试试") else CatalogResult(it) }
                ?: CatalogResult(failure = "古腾堡返回的格式不对")
            is CatalogHttp.Response.Busy -> CatalogResult(failure = "古腾堡正忙或在维护，过一会儿再试")
            is CatalogHttp.Response.Failed -> CatalogResult(failure = "连不上古腾堡（在中国大陆通常要代理）：${response.reason}")
        }
    }

    fun epubUrlOf(id: String): String = epubUrls[id].orEmpty()
}

/**
 * 维基文库（中文）：检索作品，按「作品名/」前缀列子页面当章节，逐章取解析后的 HTML 转段落。
 * 正文多是繁体，`variant` 参数对正文不起作用，转简体在本地做（`TraditionalToSimplified`）。
 */
class WikisourceClient(private val base: String = BASE) {

    suspend fun search(query: String): CatalogResult = withContext(Dispatchers.IO) {
        val url = "$base/w/api.php?action=query&list=search&srnamespace=0&srlimit=20&format=json&formatversion=2" +
            "&srsearch=${CatalogHttp.encode(query.trim())}"
        when (val response = CatalogHttp.get(url, op = "维基文库检索")) {
            is CatalogHttp.Response.Ok -> parseSearch(String(response.bytes, Charsets.UTF_8))
                ?.let { if (it.isEmpty()) CatalogResult(failure = "没找到这部作品") else CatalogResult(it) }
                ?: CatalogResult(failure = "维基文库返回的格式不对")
            is CatalogHttp.Response.Busy -> CatalogResult(failure = "维基文库正忙，过一会儿再试")
            is CatalogHttp.Response.Failed -> CatalogResult(failure = "连不上维基文库（在中国大陆通常要代理）：${response.reason}")
        }
    }

    /** 作品的章节页名（按名字排序，回目是补零编号所以顺序正确）；没有子页面时就是作品页本身。 */
    suspend fun chapterPages(title: String): List<String>? = withContext(Dispatchers.IO) {
        val pages = mutableListOf<String>()
        var from = ""
        do {
            val url = "$base/w/api.php?action=query&list=allpages&apnamespace=0&aplimit=500&format=json&formatversion=2" +
                "&apprefix=${CatalogHttp.encode("$title/")}" + if (from.isNotEmpty()) "&apfrom=${CatalogHttp.encode(from)}" else ""
            val response = CatalogHttp.get(url, op = "维基文库目录") as? CatalogHttp.Response.Ok ?: return@withContext null
            val parsed = parseAllPages(String(response.bytes, Charsets.UTF_8)) ?: return@withContext null
            pages += parsed.first
            from = parsed.second.orEmpty()
        } while (from.isNotEmpty() && pages.size < MAX_CHAPTERS)
        pages.take(MAX_CHAPTERS).ifEmpty { listOf(title) }
    }

    suspend fun chapter(page: String): ParsedChapter? = withContext(Dispatchers.IO) {
        val url = "$base/w/api.php?action=parse&prop=text&format=json&formatversion=2&disablelimitreport=1" +
            "&disableeditsection=1&page=${CatalogHttp.encode(page)}"
        val response = CatalogHttp.get(url, op = "维基文库正文") as? CatalogHttp.Response.Ok ?: return@withContext null
        parseChapter(page, String(response.bytes, Charsets.UTF_8))
    }

    @Serializable
    private data class SearchResponse(val query: SearchQuery? = null)

    @Serializable
    private data class SearchQuery(val search: List<SearchHit> = emptyList())

    @Serializable
    private data class SearchHit(val title: String = "", val snippet: String = "", val wordcount: Long = 0)

    @Serializable
    private data class AllPagesResponse(val query: AllPagesQuery? = null, val `continue`: Continue? = null)

    @Serializable
    private data class AllPagesQuery(val allpages: List<PageRef> = emptyList())

    @Serializable
    private data class PageRef(val title: String = "")

    @Serializable
    private data class Continue(val apcontinue: String = "")

    @Serializable
    private data class ParseResponse(val parse: Parsed? = null)

    @Serializable
    private data class Parsed(val title: String = "", val text: String = "")

    companion object {
        const val BASE = "https://zh.wikisource.org"
        /** 一部作品最多取这么多章。章回小说一百二十回，再多多半是汇编。 */
        const val MAX_CHAPTERS = 300

        private val json = Json { ignoreUnknownKeys = true }

        /** 检索结果只留作品本身（子页面 `作品/第一回` 不单列）。 */
        internal fun parseSearch(body: String): List<CatalogEntry>? {
            val response = runCatching { json.decodeFromString(SearchResponse.serializer(), body) }.getOrNull() ?: return null
            return response.query?.search.orEmpty()
                .filter { it.title.isNotBlank() && '/' !in it.title }
                .map { hit ->
                    CatalogEntry(
                        source = CatalogSource.Wikisource,
                        id = hit.title,
                        title = hit.title,
                        summary = HtmlText.inline(hit.snippet),
                        webUrl = "$BASE/wiki/" + CatalogHttp.encode(hit.title).replace("+", "%20"),
                    )
                }
        }

        internal fun parseAllPages(body: String): Pair<List<String>, String?>? {
            val response = runCatching { json.decodeFromString(AllPagesResponse.serializer(), body) }.getOrNull() ?: return null
            return response.query?.allpages.orEmpty().map { it.title } to response.`continue`?.apcontinue?.takeIf { it.isNotBlank() }
        }

        /**
         * 一章：去掉脚注上标、参考文献、版权框、开头那行「回目录 / 上一回 / 下一回」导航；
         * 章节名取居中加粗的回目，没有就用子页面名。
         */
        internal fun parseChapter(page: String, body: String): ParsedChapter? {
            val raw = runCatching { json.decodeFromString(ParseResponse.serializer(), body) }.getOrNull()?.parse?.text ?: return null
            // 另一种页眉：`<div id="headerContainer">` 里一张表，中间一格是「书名 / 第一回 / 回目 / 作者」，
            // 右边一格是下一回的链接。整块不是正文；中间那格的回目拿来当章节名。
            val headerStart = raw.indexOf("<div id=\"headerContainer\"")
            val headerEnd = if (headerStart >= 0) raw.indexOf("<p>", headerStart).takeIf { it > 0 } ?: -1 else -1
            val headerTitle = if (headerEnd > 0) headerTitle(raw.substring(headerStart, headerEnd)) else null
            val html = if (headerEnd > 0) raw.removeRange(headerStart, headerEnd) else raw
            val cleaned = html
                // 维基文库自己标了「不导出」的表格（页脚的返回顶部、下一回链接）不是正文。
                .replace(NO_EXPORT_TABLE, "")
                .replace(REFERENCE_SUP, "")
                .replace(REFERENCE_LIST, "")
                .let { text -> text.indexOf("licenseContainer").takeIf { it > 0 }?.let { text.substring(0, text.lastIndexOf('<', it)) } ?: text }
            val heading = CENTER_HEADING.find(cleaned)?.groupValues?.get(1)?.let(HtmlText::inline)?.takeIf { it.isNotBlank() }
            val paragraphs = HtmlText.paragraphs(cleaned)
                .filterNot { line -> NAVIGATION.containsMatchIn(line) && line.length <= 30 }
                .filterNot { it == heading }
                // 「註釋」「參考文獻」这类小节标题之后都是注释列表，不是正文。
                .takeWhile { it !in NOTES_HEADINGS }
            if (paragraphs.isEmpty()) return null
            return ParsedChapter(title = heading ?: headerTitle ?: page.substringAfterLast('/'), paragraphs = paragraphs)
        }

        /** 页眉中间那格按 `<br>` 分行：第一行是书名，「作者」那行以后不要，剩下的就是「第一回 回目」。 */
        private fun headerTitle(header: String): String? {
            val cell = HEADER_CENTER.find(header)?.groupValues?.get(1) ?: return null
            return cell.split(BREAK)
                .map(HtmlText::inline)
                .filter { it.isNotBlank() }
                .drop(1)
                .takeWhile { !it.startsWith("作者") }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        }

        private val REFERENCE_SUP = Regex("""<sup[^>]*class="[^"]*reference[^"]*"[^>]*>.*?</sup>""", RegexOption.DOT_MATCHES_ALL)
        private val REFERENCE_LIST = Regex("""<ol class="references">.*?</ol>""", RegexOption.DOT_MATCHES_ALL)
        private val NO_EXPORT_TABLE = Regex("""<table[^>]*class="[^"]*ws-noexport[^"]*"[^>]*>.*?</table>""", RegexOption.DOT_MATCHES_ALL)
        private val HEADER_CENTER = Regex("""<td style="width:50%;">(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
        private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val CENTER_HEADING = Regex("""<div class="center">\s*<b>(.*?)</b>""", RegexOption.DOT_MATCHES_ALL)
        private val NAVIGATION = Regex("回目录|回目錄|上一回|下一回|上一章|下一章|返回目录")
        private val NOTES_HEADINGS = setOf("註釋", "注釋", "注释", "腳註", "脚注", "參考文獻", "参考文献", "校勘記", "校勘记")
    }
}

/**
 * AO3（Archive of Our Own）：检索已完结、英文的作品，按 kudos 排；分级可选，默认全部。
 * 下载走站点官方的 EPUB 下载地址；AO3 会对自动程序限流（429），这时让用户在浏览器里下好再导入。
 */
class Ao3Client(private val base: String = BASE) {

    suspend fun search(query: String, rating: Ao3Rating = Ao3Rating.All): CatalogResult = withContext(Dispatchers.IO) {
        val url = "$base/works/search?work_search%5Bquery%5D=${CatalogHttp.encode(query.trim())}" +
            "&work_search%5Blanguage_id%5D=en" +
            (rating.id?.let { "&work_search%5Brating_ids%5D%5B%5D=$it" } ?: "") +
            "&work_search%5Bcomplete%5D=T&work_search%5Bsort_column%5D=kudos_count"
        when (val response = CatalogHttp.get(url, op = "AO3 检索")) {
            is CatalogHttp.Response.Ok -> parseSearch(String(response.bytes, Charsets.UTF_8))
                .let { if (it.isEmpty()) CatalogResult(failure = "没找到，换个英文关键词试试") else CatalogResult(it) }
            is CatalogHttp.Response.Busy -> CatalogResult(failure = "AO3 正忙（对自动程序限流），过一会儿再试")
            is CatalogHttp.Response.Failed -> CatalogResult(failure = "连不上 AO3（在中国大陆通常要代理）：${response.reason}")
        }
    }

    suspend fun download(entry: CatalogEntry): CatalogDownload = withContext(Dispatchers.IO) {
        // 成人向作品要 view_adult=true，否则回的是"确认已成年"页面而不是文件。
        when (val response = CatalogHttp.get("$base/downloads/${entry.id}/work.epub?view_adult=true", op = "AO3 下载")) {
            is CatalogHttp.Response.Ok -> if (isZip(response.bytes)) CatalogDownload.Epub(response.bytes)
            else CatalogDownload.Busy("AO3 没有给出 EPUB")
            is CatalogHttp.Response.Busy -> CatalogDownload.Busy("AO3 对自动下载限流了")
            is CatalogHttp.Response.Failed -> CatalogDownload.Failed("下载失败：${response.reason}")
        }
    }

    companion object {
        const val BASE = "https://archiveofourown.org"

        private val BLURB = Regex("""<li id="work_(\d+)" class="work blurb group[^"]*"[^>]*>(.*?)(?=<li id="work_\d+" class="work blurb|</ol>)""", RegexOption.DOT_MATCHES_ALL)
        private val TITLE = Regex("""<h4 class="heading">\s*<a href="/works/\d+">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val AUTHOR = Regex("""<a rel="author"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val RATING = Regex("""<span class="rating-[^"]*" title="([^"]+)">""")
        private val SUMMARY = Regex("""<blockquote class="userstuff summary">(.*?)</blockquote>""", RegexOption.DOT_MATCHES_ALL)

        private fun field(block: String, name: String): String =
            Regex("""<dd class="$name">(.*?)</dd>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
                ?.let(HtmlText::inline).orEmpty()

        internal fun parseSearch(html: String): List<CatalogEntry> = BLURB.findAll(html).mapNotNull { match ->
            val id = match.groupValues[1]
            val block = match.groupValues[2]
            val title = TITLE.find(block)?.groupValues?.get(1)?.let(HtmlText::inline)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CatalogEntry(
                source = CatalogSource.Ao3,
                id = id,
                title = title,
                author = AUTHOR.findAll(block).map { HtmlText.inline(it.groupValues[1]) }.joinToString(" / "),
                summary = SUMMARY.find(block)?.groupValues?.get(1)?.let { HtmlText.paragraphs(it).joinToString(" ") }.orEmpty(),
                meta = listOf(
                    RATING.find(block)?.groupValues?.get(1)?.let(Ao3Rating::labelOf)?.takeIf { it.isNotBlank() },
                    field(block, "words").takeIf { it.isNotBlank() }?.let { "$it 词" },
                    field(block, "chapters").takeIf { it.isNotBlank() }?.let { "$it 章" },
                ).filterNotNull().joinToString(" · "),
                webUrl = "$BASE/works/$id?view_adult=true",
            )
        }.toList()
    }
}

/** EPUB 是 zip：开头是「PK」。限流时站点会回一段文字，靠这个认出来。 */
private fun isZip(bytes: ByteArray): Boolean = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
