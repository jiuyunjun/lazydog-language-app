package com.lazydog.english.domain.books

/**
 * 在线书库（D-098 R3，`读书与网络阅读DESIGN.md` §3.1、§3.3）。这里只描述"有哪些书库、一条结果长什么样"，
 * 怎么检索和下载在 `core/network`。
 */
enum class CatalogSource(val labelZh: String, val noteZh: String, val book: BookSource) {
    Gutenberg("古腾堡", "7 万多本英文公版书", BookSource.Gutenberg),
    Wikisource("维基文库", "中文公版作品，繁体会转成简体", BookSource.Wikisource),
    Ao3("AO3", "作者公开发布的同人和原创，已完结的英文作品", BookSource.Ao3),
}

/** 检索结果里的一本。[id] 是书库自己的编号或条目名，下载和去重都靠它。 */
data class CatalogEntry(
    val source: CatalogSource,
    val id: String,
    val title: String,
    val author: String = "",
    val summary: String = "",
    /** 一行附加信息：下载量、字数、章节数。 */
    val meta: String = "",
    /** 在浏览器里打开这本书的地址（AO3 下载被限流时让用户自己下）。 */
    val webUrl: String = "",
)

/** AO3 的作品分级。[id] 是 AO3 检索参数里的 rating_ids；[All] 不加这个参数。 */
enum class Ao3Rating(val labelZh: String, val id: Int?) {
    All("全部分级", null),
    General("全年龄", 10),
    Teen("青少年", 11),
    Mature("成人向", 12),
    Explicit("限制级", 13),
    ;

    companion object {
        /** 结果页上分级的英文名 → 中文，用于结果卡片。 */
        fun labelOf(english: String): String = when (english.trim()) {
            "General Audiences" -> General.labelZh
            "Teen And Up Audiences" -> Teen.labelZh
            "Mature" -> Mature.labelZh
            "Explicit" -> Explicit.labelZh
            "Not Rated" -> "未分级"
            else -> ""
        }
    }
}

data class CatalogResult(
    val entries: List<CatalogEntry> = emptyList(),
    val failure: String? = null,
)

/** 维基文库不好浏览，给几部常读的作为起点。都是公版。 */
val WIKISOURCE_SUGGESTIONS = listOf("紅樓夢", "西遊記", "三國演義", "水滸傳", "儒林外史", "聊齋志異", "吶喊", "朝花夕拾")

/**
 * 繁体转简体，逐字（数据来自 OpenCC，见 `tools/build_t2s.py`）。按码位查，表外的字原样保留。
 */
class TraditionalToSimplified(private val table: Map<Int, Int>) {

    fun convert(text: String): String {
        if (table.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out.appendCodePoint(table[cp] ?: cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    fun convert(book: ParsedBook): ParsedBook = book.copy(
        title = convert(book.title),
        author = convert(book.author),
        chapters = book.chapters.map { chapter ->
            ParsedChapter(convert(chapter.title), chapter.paragraphs.map(::convert))
        },
    )

    companion object {
        /** 资源文件的格式：`#` 开头是注释，其余每行「繁 简」。 */
        fun parse(lines: Sequence<String>): TraditionalToSimplified = TraditionalToSimplified(
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.trim().split(' ')
                    if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                        parts[0].codePointAt(0) to parts[1].codePointAt(0)
                    } else {
                        null
                    }
                }
                .toMap(),
        )
    }
}
