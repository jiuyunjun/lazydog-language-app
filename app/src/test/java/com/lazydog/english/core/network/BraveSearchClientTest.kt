package com.lazydog.english.core.network

import com.lazydog.english.domain.generation.WebSearchProvider
import com.lazydog.english.domain.generation.factPackLines
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BraveSearchClientTest {

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

    private fun client(key: String = "test-key") = BraveSearchClient(
        apiKey = { key },
        endpoint = server.url("/res/v1/web/search").toString(),
    )

    private val payload = """
        {"type":"search","query":{"original":"convenience store"},
         "web":{"results":[
           {"title":"Convenience <strong>store</strong> - Wikipedia",
            "description":"A convenience store is a small retail store.",
            "url":"https://en.wikipedia.org/wiki/Convenience_store",
            "age":"2 weeks ago","page_age":"2026-08-26T23:25:52"}]}}
    """.trimIndent()

    /**
     * 这条守的是一个真实踩过的坑：**自己写 `Accept-Encoding: gzip` 会关掉 OkHttp 的透明解压**，
     * 服务端返回原始 gzip 字节，`body.string()` 拿到二进制，JSON 永远解不出来。
     * 现象是"Brave 返回的不是预期结构"，但密钥和接口其实都是好的。
     */
    @Test
    fun `压缩过的返回照样解得开`() = runBlocking {
        val gzipped = Buffer()
        GzipSink(gzipped).buffer().use { it.writeUtf8(payload) }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Encoding", "gzip")
                .setBody(gzipped),
        )
        val result = client().search("convenience store")
        // 只有在 Accept-Encoding 是 OkHttp 自己加的时候，它才会替我们解压。
        // 一旦有人把这行头写回请求里，这条就会挂——挂在这里比挂在用户那儿好。
        assertNull(result.failure)
        assertEquals(1, result.hits.size)
        assertEquals("test-key", server.takeRequest().getHeader("X-Subscription-Token"))
        Unit
    }

    @Test
    fun `parses官方结构并去掉摘要里的高亮标签`() = runBlocking {
        server.enqueue(MockResponse().setBody(payload))
        val result = client().search("convenience store")
        assertNull(result.failure)
        assertEquals(1, result.hits.size)
        val hit = result.hits.first()
        assertEquals("Convenience store - Wikipedia", hit.title)
        assertTrue(hit.summary.startsWith("A convenience store"))
        // page_age 比 age 精确，优先用它。
        assertEquals("2026-08-26T23:25:52", hit.age)
        // 进提示词的是一行一条、带出处的事实，不是一段散文。
        assertTrue(factPackLines(result.hits).first().contains("wikipedia.org"))
        Unit
    }

    @Test
    fun `不限时间窗时不发 freshness，指定了才发`() = runBlocking {
        server.enqueue(MockResponse().setBody(payload))
        client().search("evergreen topic")
        assertFalse(server.takeRequest().path.orEmpty().contains("freshness"))

        server.enqueue(MockResponse().setBody(payload))
        client().search("today news", freshness = WebSearchProvider.FRESH_MONTH)
        assertTrue(server.takeRequest().path.orEmpty().contains("freshness=pm"))
        Unit
    }

    @Test
    fun `解不开的返回把开头带进失败原因，方便下次直接看出是什么`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html><body>blocked</body></html>"))
        val result = client().search("anything")
        assertNotNull(result.failure)
        assertTrue(result.failure.orEmpty().contains("html"))
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
    fun `限流和密钥无效分开报，别让人以为是同一件事`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(client().search("x").failure.orEmpty().contains("限流"))

        server.enqueue(MockResponse().setResponseCode(401))
        assertTrue(client().search("x").failure.orEmpty().contains("密钥无效"))
        Unit
    }
}
