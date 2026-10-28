package com.lazydog.english.domain.generation

/**
 * 联网检索的领域接口（`ARCHITECTURE.md` §7 里"内容来源"计划的第四种：Web 搜索）。
 *
 * 存在的理由只有一个：**热点内容必须先有可信事实再写文章**
 * （`母语阅读DESIGN.md` §36.7、§43 原则 9）。让模型凭记忆写"今天发生了什么"，
 * 写出来的东西看着像新闻但没有一句能信。
 *
 * 它是可选依赖，不是学习流程的前置条件——和词频表一条路子：拿不到就退回不带事实的写法，
 * 而不是让整个功能失败。
 */
interface WebSearchProvider {

    /** 没配密钥时为 false，界面据此隐藏"先搜一下"这个开关。 */
    suspend fun isConfigured(): Boolean

    /**
     * [freshness] 限定时间窗，取值见 [FRESH_MONTH] 等常量，空表示不限。
     * 默认不限：写「为什么便利店很少缺货」这种主题时按一个月卡下去经常一条都搜不到，
     * 那不是没有结果，是问错了问题。只有用户明说要「最新消息」时才该收窗口。
     */
    suspend fun search(
        query: String,
        count: Int = DEFAULT_COUNT,
        freshness: String = "",
    ): WebSearchResult

    /**
     * 新闻检索：只回新闻站的报道，按时间窗收。
     *
     * 现在只是「今日热点」在 Google 新闻连不上时的兜底（D-088）：Brave 没有新闻分区和城市，
     * 只能按 [country]、[searchLang] 加查询词筛。两者空表示不限。
     */
    suspend fun news(
        query: String,
        count: Int = NEWS_COUNT,
        freshness: String = FRESH_DAY,
        country: String = "",
        searchLang: String = "",
    ): WebSearchResult

    companion object {
        const val DEFAULT_COUNT = 5

        /** 热点列表拉多少条。去重后留十条左右给人挑，再多就翻不完了。 */
        const val NEWS_COUNT = 20

        /** Brave 的时间窗取值：过去一天 / 一周 / 一个月 / 一年。 */
        const val FRESH_DAY = "pd"
        const val FRESH_WEEK = "pw"
        const val FRESH_MONTH = "pm"
        const val FRESH_YEAR = "py"
    }
}

/** 一条检索结果。只留写文章用得上的东西，不做全文抓取。 */
data class WebSearchHit(
    val title: String,
    val summary: String,
    val url: String,
    /** 来源给出的时间描述，如 "2 hours ago" / "2026-09-05"。判断新鲜度用，可空。 */
    val age: String = "",
    /** 来源站点名或域名，热点列表上给人看是谁报的。可空。 */
    val source: String = "",
)

/**
 * [failure] 非空表示这次没搜到（没配密钥、限流、网络断了）。
 * **不是异常**：调用方看一眼就退回不带事实的写法，用户不该因为搜索失败读不到文章。
 */
data class WebSearchResult(
    val hits: List<WebSearchHit> = emptyList(),
    val failure: String? = null,
)

/**
 * 把检索结果压成进提示词的 Fact Pack。
 *
 * 一条一行、带出处，模型才有可能只用这些事实写；混成一段散文的话，
 * 模型分不清哪句是检索来的、哪句是它自己想的。
 */
fun factPackLines(hits: List<WebSearchHit>, limit: Int = 6): List<String> =
    hits.asSequence()
        .filter { it.title.isNotBlank() }
        .take(limit)
        .map { hit ->
            buildString {
                append(hit.title.trim())
                if (hit.summary.isNotBlank()) append("：").append(hit.summary.trim().take(220))
                if (hit.age.isNotBlank()) append("（${hit.age.trim()}）")
                if (hit.url.isNotBlank()) append(" [${hit.url.trim()}]")
            }
        }
        .toList()
