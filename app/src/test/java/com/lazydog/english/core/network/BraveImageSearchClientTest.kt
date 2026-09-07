package com.lazydog.english.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BraveImageSearchClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(key: String = "test-key") = BraveImageSearchClient(
        apiKey = { key },
        endpoint = server.url("/res/v1/images/search").toString(),
    )

    private val payload = """
        {"type":"images","results":[
          {"title":"Hand <strong>gripping</strong> a metal handle",
           "url":"https://unsplash.com/photos/abc",
           "source":"unsplash.com",
           "thumbnail":{"src":"https://imgs.search.brave.com/thumb1","width":500,"height":281},
           "properties":{"url":"https://images.unsplash.com/photo-1","width":1600,"height":900}}]}
    """.trimIndent()

    @Test
    fun `解出缩略图、原图和出处三个地址`() = runBlocking {
        server.enqueue(MockResponse().setBody(payload))
        val result = client().search("hand gripping a metal handle close up")
        assertNull(result.failure)
        val hit = result.hits.single()
        // 词卡里显示的是 Brave 代理出的缩略图（约 500px 宽），不是原图。
        assertEquals("https://imgs.search.brave.com/thumb1", hit.thumbnailUrl)
        assertEquals("https://images.unsplash.com/photo-1", hit.originalUrl)
        // 来源行点开去的是图片所在的网页，不是图片文件。
        assertEquals("https://unsplash.com/photos/abc", hit.sourcePageUrl)
        assertEquals("Hand gripping a metal handle", hit.title)
        assertEquals(1600, hit.width)
        assertEquals("test-key", server.takeRequest().getHeader("X-Subscription-Token"))
        Unit
    }

    /** §52：公开学习产品默认最严；这条一旦被改松，得有人先看见测试红。 */
    @Test
    fun `永远发 strict 安全搜索和英文查询`() = runBlocking {
        server.enqueue(MockResponse().setBody(payload))
        client().search("river otter swimming close up")
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path.contains("safesearch=strict"))
        assertTrue(path.contains("search_lang=en"))
        // §15：不把请求锁到用户所在国家。
        assertTrue(path.contains("country=ALL"))
        Unit
    }

    /** §16：图片搜索不支持分页，一次 30 张够筛出八张候选；再多只是多花配额。 */
    @Test
    fun `默认取三十张，超过上限会被夹住`() = runBlocking {
        server.enqueue(MockResponse().setBody(payload))
        client().search("x")
        assertTrue(server.takeRequest().path.orEmpty().contains("count=30"))

        server.enqueue(MockResponse().setBody(payload))
        client().search("x", count = 999)
        assertTrue(server.takeRequest().path.orEmpty().contains("count=200"))
        Unit
    }

    @Test
    fun `没配密钥不发请求，直接说没配`() = runBlocking {
        val result = client(key = "").search("anything")
        assertEquals("没有配置 Brave 搜索密钥", result.failure)
        assertEquals(0, server.requestCount)
        Unit
    }

    @Test
    fun `解不开的返回把开头带进失败原因`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>blocked</html>"))
        val result = client().search("anything")
        assertNotNull(result.failure)
        assertTrue(result.failure.orEmpty().contains("html"))
        Unit
    }

    @Test
    fun `一张图都没有时说没搜到，不返回空成功`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"type":"images","results":[]}"""))
        assertEquals("没搜到可用图片", client().search("nonsense").failure)
        Unit
    }
}
