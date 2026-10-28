package com.lazydog.english.domain.generation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HotTopicsTest {

    private fun story(title: String, site: String = "", direct: Boolean = false, link: String = "") =
        NewsStory(title = title, source = "", sourceSite = site, link = link, direct = direct)

    @Test
    fun `Google 标题末尾的来源去掉`() {
        assertEquals("台风今晚登陆浙江", NewsText.cleanTitle("台风今晚登陆浙江 - 澎湃新闻", "澎湃新闻"))
        assertEquals("台风今晚登陆浙江 - 别家", NewsText.cleanTitle("台风今晚登陆浙江 - 别家", "澎湃新闻"))
    }

    /** 同一件事十几家转载，标题只差几个字，列表里只该出现一次。 */
    @Test
    fun `转载的同一条新闻只留第一条`() {
        val list = NewsText.distinct(
            listOf(
                story("台风「海燕」今晚登陆浙江沿海"),
                story("台风「海燕」今晚登陆浙江沿海地区"),
                story("央行宣布下调存款准备金率"),
            ),
        )
        assertEquals(listOf("台风「海燕」今晚登陆浙江沿海", "央行宣布下调存款准备金率"), list.map { it.title })
    }

    @Test
    fun `发布时间显示成几小时前，超过一周写日期`() {
        val now = 1_800_000_000_000L
        assertEquals("刚刚", NewsText.ageZh(now - 10_000, now))
        assertEquals("15 分钟前", NewsText.ageZh(now - 15 * 60_000, now))
        assertEquals("3 小时前", NewsText.ageZh(now - 3 * 3_600_000, now))
        assertEquals("2 天前", NewsText.ageZh(now - 2 * 86_400_000L, now))
        assertTrue(NewsText.ageZh(now - 30 * 86_400_000L, now).contains("-"))
        assertEquals("", NewsText.ageZh(null, now))
    }

    // ---- 找原文 ----

    private class FakeSearch(val hits: List<WebSearchHit>, val configured: Boolean = true) : WebSearchProvider {
        var calls = 0
        override suspend fun isConfigured() = configured
        override suspend fun search(query: String, count: Int, freshness: String): WebSearchResult {
            calls++
            return WebSearchResult(hits)
        }
        override suspend fun news(query: String, count: Int, freshness: String, country: String, searchLang: String) =
            WebSearchResult()
    }

    private class FakeFetcher(val pages: Map<String, List<String>>) : ArticleFetcher {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String): FetchedArticle? {
            fetched += url
            return pages[url]?.let { FetchedArticle(url, it) }
        }
    }

    private val body = listOf("第一段正文足够长，讲清楚了事情的经过和时间地点人物。", "第二段继续交代背景和各方反应，内容同样足够长。", "第三段给出后续安排和官方说法，凑够两百字的门槛。")
        .map { it.repeat(3) }

    private fun hit(title: String, url: String) = WebSearchHit(title = title, summary = "", url = url)

    /** 同一来源站的链接排最前；标题不像的旧闻、别的事不要。 */
    @Test
    fun `同站优先，标题不像的不要`() {
        val loader = NewsArticleLoader(FakeSearch(emptyList()), FakeFetcher(emptyMap()))
        val ranked = loader.rankCandidates(
            story("杭州东湖高架两车遭遇风筝线 智驾告警司机紧急接管", site = "https://www.hangzhou.com.cn"),
            listOf(
                hit("东湖高架两车遭遇风筝线，智驾告警司机紧急接管", "https://news.sohu.com/a/1"),
                hit("杭州东湖高架两车遭遇风筝线", "https://www.hangzhou.com.cn/b/2"),
                hit("杭州今年第三季度GDP公布", "https://other.example/c"),
            ),
        )
        assertEquals(listOf("https://www.hangzhou.com.cn/b/2", "https://news.sohu.com/a/1"), ranked)
    }

    @Test
    fun `第一个链接抽不出正文就换下一个`() = runBlocking {
        val fetcher = FakeFetcher(mapOf("https://b.example/2" to body))
        val loader = NewsArticleLoader(
            FakeSearch(listOf(hit("台风今晚登陆浙江", "https://a.example/1"), hit("台风今晚登陆浙江沿海", "https://b.example/2"))),
            fetcher,
        )
        val result = loader.load(story("台风今晚登陆浙江"))
        assertTrue(result is NewsArticleLoader.Result.Success)
        assertEquals(listOf("https://a.example/1", "https://b.example/2"), fetcher.fetched)
    }

    /** Brave 兜底来的新闻自带原文地址，能直接抓到就不再花一次检索。 */
    @Test
    fun `自带原文地址的先直接抓，不检索`() = runBlocking {
        val search = FakeSearch(emptyList())
        val loader = NewsArticleLoader(search, FakeFetcher(mapOf("https://direct.example/x" to body)))
        val result = loader.load(story("某条新闻", direct = true, link = "https://direct.example/x"))
        assertTrue(result is NewsArticleLoader.Result.Success)
        assertEquals(0, search.calls)
    }

    @Test
    fun `没有 Brave 密钥时说清楚要填密钥`() = runBlocking {
        val loader = NewsArticleLoader(FakeSearch(emptyList(), configured = false), FakeFetcher(emptyMap()))
        val result = loader.load(story("某条新闻"))
        assertTrue((result as NewsArticleLoader.Result.Failure).reason.contains("Brave 密钥"))
    }

    // ---- 清理只能删不能改 ----

    private val source = listOf("新华社北京10月28日电 今天发布了新规定。", "规定自明年起施行。（完）", "责任编辑：李四")

    @Test
    fun `清理结果以原文为准，改过字的退回原段落`() {
        val outcome = NewsCleanup.apply(
            source,
            listOf(
                NewsCleanup.Kept(1, "规定自明年起施行。"),
                NewsCleanup.Kept(0, "今天发布了一项新的规定。"),
            ),
        )
        // 顺序按原文；第 0 段 AI 改了字，用原文；第 1 段只切掉段尾杂质，接受。
        assertEquals(listOf("新华社北京10月28日电 今天发布了新规定。", "规定自明年起施行。"), outcome.paragraphs)
        assertEquals(1, outcome.notes.size)
    }

    @Test
    fun `空 text 表示整段保留，越界和重复的序号丢掉`() {
        val outcome = NewsCleanup.apply(
            source,
            listOf(NewsCleanup.Kept(0, ""), NewsCleanup.Kept(0, ""), NewsCleanup.Kept(9, "x")),
        )
        assertEquals(listOf(source[0]), outcome.paragraphs)
    }

    @Test
    fun `原文过长时按段截到上限，第一段总会留下`() {
        val long = List(40) { "段".repeat(300) }
        val trimmed = NewsCleanup.trimSource(long)
        assertTrue(trimmed.sumOf { it.length } <= NewsCleanup.MAX_SOURCE_CHARS)
        assertEquals(1, NewsCleanup.trimSource(listOf("长".repeat(9_000))).size)
    }
}
