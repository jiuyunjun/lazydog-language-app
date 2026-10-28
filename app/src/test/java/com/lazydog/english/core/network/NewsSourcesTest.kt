package com.lazydog.english.core.network

import com.lazydog.english.domain.generation.NewsCategory
import com.lazydog.english.domain.generation.NewsEdition
import com.lazydog.english.domain.generation.NewsFeed
import com.lazydog.english.domain.generation.NewsFeedResult
import com.lazydog.english.domain.generation.NewsQuery
import com.lazydog.english.domain.generation.WebSearchHit
import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.domain.generation.WebSearchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsSourcesTest {

    private val google = GoogleNewsRssClient(base = "https://news.google.com")

    @Test
    fun `四种查询对应四种 Google 新闻地址`() {
        val cn = NewsEdition.Mainland
        val locale = "hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
        assertEquals("https://news.google.com/rss?$locale", google.urlFor(NewsQuery(cn, NewsCategory.Top)))
        assertEquals(
            "https://news.google.com/rss/headlines/section/topic/SPORTS?$locale",
            google.urlFor(NewsQuery(cn, NewsCategory.Sports)),
        )
        assertEquals(
            "https://news.google.com/rss/headlines/section/geo/%E6%9D%AD%E5%B7%9E?$locale",
            google.urlFor(NewsQuery(cn, NewsCategory.Top, "杭州")),
        )
        // 城市 + 分类：Google 没有这种分区，走检索，并限两天内。
        val search = google.urlFor(NewsQuery(cn, NewsCategory.Sports, "杭州"))
        assertTrue(search.startsWith("https://news.google.com/rss/search?q="))
        assertTrue(java.net.URLDecoder.decode(search, "UTF-8").contains("杭州 体育 when:2d"))
        assertTrue(google.urlFor(NewsQuery(NewsEdition.Taiwan, NewsCategory.Top)).endsWith("ceid=TW:zh-Hant"))
    }

    private val rss = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>体育 - 最新新闻 - Google 新闻</title>
        <item>
          <title>U17国足集训名单出炉 - 紫牛新闻</title>
          <link>https://news.google.com/rss/articles/CBMiaEFV?oc=5</link>
          <pubDate>Wed, 28 Oct 2026 14:42:00 GMT</pubDate>
          <description>&lt;ol&gt;&lt;li&gt;&lt;a href="https://news.google.com/rss/articles/A"&gt;U17国足集训名单出炉&lt;/a&gt;&amp;nbsp;&amp;nbsp;&lt;font color="#6f6f6f"&gt;紫牛新闻&lt;/font&gt;&lt;/li&gt;&lt;li&gt;&lt;a href="https://news.google.com/rss/articles/B"&gt;时隔21年再战世少赛 - 新华网&lt;/a&gt;&amp;nbsp;&amp;nbsp;&lt;font color="#6f6f6f"&gt;新华网&lt;/font&gt;&lt;/li&gt;&lt;/ol&gt;</description>
          <source url="https://www.yzwb.net">紫牛新闻</source>
        </item>
        <item>
          <title>U17国足集训名单出炉！ - 搜狐网</title>
          <link>https://news.google.com/rss/articles/C</link>
          <pubDate>Wed, 28 Oct 2026 15:00:00 GMT</pubDate>
          <source url="https://www.sohu.com">搜狐网</source>
        </item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `解出标题、来源站、时间和同事件的别家报道，转载去重`() {
        val stories = google.parse(rss)!!
        assertEquals(1, stories.size)
        val story = stories.single()
        assertEquals("U17国足集训名单出炉", story.title)
        assertEquals("紫牛新闻", story.source)
        assertEquals("https://www.yzwb.net", story.sourceSite)
        assertFalse(story.direct)
        assertNotNull(story.publishedAt)
        assertEquals(listOf("时隔21年再战世少赛"), story.related.map { it.title })
        assertEquals("新华网", story.related.single().source)
    }

    @Test
    fun `不是 RSS 时返回 null`() {
        assertNull(google.parse("<html><body>blocked</body></html>")?.takeIf { it.isNotEmpty() })
        assertNull(google.parse("not xml at all"))
    }

    // ---- 兜底 ----

    private class FailingFeed : NewsFeed {
        override suspend fun headlines(query: NewsQuery) = NewsFeedResult(failure = "连不上 Google 新闻")
    }

    private class FakeBrave(val configured: Boolean) : WebSearchProvider {
        var lastCountry = ""
        var lastQuery = ""
        override suspend fun isConfigured() = configured
        override suspend fun search(query: String, count: Int, freshness: String) = WebSearchResult()
        override suspend fun news(query: String, count: Int, freshness: String, country: String, searchLang: String): WebSearchResult {
            lastCountry = country
            lastQuery = query
            val titles = listOf("港队晋级亚洲杯决赛", "马拉松周日封路安排", "羽毛球公开赛首日赛果", "启德体育园新增场次", "游泳名将宣布退役")
            return WebSearchResult(
                titles.mapIndexed { i, title ->
                    WebSearchHit(title = title, summary = "", url = "https://s$i.example/a",
                        age = "2026-10-28T03:00:00", source = "s$i.example")
                },
            )
        }
    }

    @Test
    fun `Google 连不上且配了 Brave 时兜底，并标出来`() = runBlocking {
        val brave = FakeBrave(configured = true)
        val result = FallbackNewsFeed(FailingFeed(), brave).headlines(NewsQuery(NewsEdition.HongKong, NewsCategory.Sports, "九龙"))
        assertTrue(result.fromFallback)
        assertEquals(5, result.stories.size)
        assertTrue(result.stories.all { it.direct })
        assertNotNull(result.stories.first().publishedAt)
        assertEquals("HK", brave.lastCountry)
        assertEquals("九龙 体育", brave.lastQuery)
    }

    @Test
    fun `没配 Brave 就如实返回 Google 的失败`() = runBlocking {
        val result = FallbackNewsFeed(FailingFeed(), FakeBrave(configured = false)).headlines(NewsQuery(NewsEdition.Mainland, NewsCategory.Top))
        assertEquals("连不上 Google 新闻", result.failure)
        assertFalse(result.fromFallback)
    }

    // ---- 正文抽取 ----

    @Test
    fun `正文取 article 里的段落，去掉脚本、导航和站务文字`() {
        val html = """
            <html><head><script>var p = "<p>不是正文</p>";</script></head><body>
            <nav><p>首页 新闻 体育 娱乐 财经 科技</p></nav>
            <article>
              <p>新华社北京10月28日电&nbsp;今天发布了关于公共交通的新规定，涉及全国三百多个城市。</p>
              <p>规定明确，自明年一月起，地铁和公交&ldquo;一码通行&rdquo;。<br>各地需在年底前完成系统改造。</p>
              <p>交通运输部有关负责人表示，新规将显著方便跨城出行的乘客，减少重复注册。</p>
              <p>责任编辑：李四</p>
            </article>
            <p>相关阅读：其他城市地铁票价调整的新闻标题在这里</p>
            </body></html>
        """.trimIndent()
        val paragraphs = ArticleExtractor.paragraphs(html)
        assertEquals(4, paragraphs.size)
        assertTrue(paragraphs[0].startsWith("新华社北京10月28日电 今天"))
        assertEquals("规定明确，自明年一月起，地铁和公交“一码通行”。", paragraphs[1])
        assertEquals("各地需在年底前完成系统改造。", paragraphs[2])
        assertTrue(paragraphs.none { "不是正文" in it || "责任编辑" in it || "相关阅读" in it })
    }

    @Test
    fun `没有 article 时退到整页的段落`() {
        val html = "<body><p>第一段正文，讲的是一件足够长的事情。</p><p>短</p><p>第二段正文，也足够长，可以留下来。</p></body>"
        assertEquals(2, ArticleExtractor.paragraphs(html).size)
    }

    @Test
    fun `Brave 的 page_age 按 UTC 解`() {
        assertEquals(1_793_156_400_000L, FallbackNewsFeed.parseIso("2026-10-28T03:00:00"))
        assertNull(FallbackNewsFeed.parseIso("3 hours ago"))
    }
}
