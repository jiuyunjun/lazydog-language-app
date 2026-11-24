package com.lazydog.english.domain.reading

/**
 * 日常阅读的网上来源（`读书与网络阅读DESIGN.md` §2）。材料是真实的文章，不是模型写的；
 * 这里只描述"有哪些来源、列表里一条长什么样、抓回来的正文长什么样"，怎么抓在 `core/network`。
 */
enum class WebReadingSource(val wire: String, val labelZh: String, val attribution: String) {
    Voa("voa", "VOA 慢速英语", "VOA Learning English"),
    News("news", "新闻原文", ""),
    Wiki("wiki", "简明维基", "Simple English Wikipedia（CC BY-SA）"),
    ;

    companion object {
        fun fromWire(wire: String): WebReadingSource? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * VOA Learning English 里文字为主的栏目。视频、播客、按语言分的 Anna 课程不收——
 * 那些页面没有可读的正文。[feedPath] 是站点 RSS 的路径，来自它的 /rssfeeds 页（2026-11 核实）。
 */
enum class VoaChannel(val labelZh: String, val feedPath: String) {
    AsItIs("时事 As It Is", "/api/zkm-ql-vomx-tpej-rqi"),
    AmericanStories("美国短篇小说", "/api/zyg__l-vomx-tpetmty"),
    Science("科技", "/api/zmg_pl-vomx-tpeymtm"),
    Health("健康生活", "/api/zmmpql-vomx-tpey-_q"),
    Arts("文化艺术", "/api/zpyp_l-vomx-tpe_rym"),
    Words("词语的故事", "/api/zmypyl-vomx-tpeyry_"),
    History("美国历史", "/api/zj_pvl-vomx-tpebb_v"),
    People("美国人物", "/api/z-viqql-vomx-tpero_qq"),
    ;

    companion object {
        fun fromName(name: String): VoaChannel = entries.firstOrNull { it.name == name } ?: AsItIs
    }
}

/** 列表里的一条。[key] 在同一来源内唯一：VOA 和新闻是链接，维基是条目标题。 */
data class WebReadingCard(
    val source: WebReadingSource,
    val key: String,
    val title: String,
    val teaser: String = "",
    /** 栏目或媒体名。 */
    val channel: String = "",
    val link: String = "",
    val publishedAt: Long? = null,
)

/** 抓回来的正文。[paragraphs] 是原文段落，没有改写。 */
data class WebReadingArticle(
    val card: WebReadingCard,
    val paragraphs: List<String>,
    /** 原文地址（跳转后的最终地址）。 */
    val url: String,
    /** 页面底部的出处，如「VOA Learning English」「Reuters」。 */
    val attribution: String,
)

data class WebReadingFeed(
    val cards: List<WebReadingCard> = emptyList(),
    val failure: String? = null,
)

/** 正文太短的不收：多半是视频页、图集或只有导语的快讯。 */
object WebReadingRules {
    const val MIN_PARAGRAPHS = 3
    const val MIN_CHARS = 600

    /** 阅读页一篇放多少字。再长就是一本小册子了，读不完。 */
    const val MAX_CHARS = 12_000

    fun usable(paragraphs: List<String>): Boolean =
        paragraphs.size >= MIN_PARAGRAPHS && paragraphs.sumOf { it.length } >= MIN_CHARS

    /** 截到 [MAX_CHARS] 以内，按整段截，不切半句。 */
    fun trim(paragraphs: List<String>): List<String> {
        var total = 0
        return paragraphs.takeWhile { p ->
            val fits = total == 0 || total + p.length <= MAX_CHARS
            total += p.length
            fits
        }
    }
}
