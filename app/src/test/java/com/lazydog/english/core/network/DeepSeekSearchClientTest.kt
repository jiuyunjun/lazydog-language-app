package com.lazydog.english.core.network

import com.lazydog.english.domain.generation.WebSearchProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeepSeekSearchClientTest {

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

    private fun client(key: String = "sk-test") = DeepSeekSearchClient(
        apiKey = { key },
        endpoint = server.url("/v1/tools/search").toString(),
        today = { LocalDate.of(2026, 11, 10) },
    )

    /** 官方文档 API reference 里的示例响应原样搬过来。 */
    private val payload = """
        {"results": [{"rerank_score": 2.4, "title": "DeepSeek V4 model released",
          "url": "https://example.com/deepseek-v4",
          "snippet": "DeepSeek V4 is now available, with tool calling and long-context support.",
          "summary": "DeepSeek has released the V4 model. The release adds improved tool calling.",
          "content": "", "site_name": "Example News", "site_icon": "https://example.com/favicon.ico",
          "date": "2026-06-01T00:00:00+00:00", "authority": 3}]}
    """.trimIndent()

    @Test
    fun `按官方结构解析并用 POST 带 Bearer 密钥`() = runBlocking {
        server.enqueue(MockResponse().setBody(payload))
        val result = client().search("deepseek v4", count = 3)

        assertNull(result.failure)
        val hit = result.hits.single()
        assertEquals("DeepSeek V4 model released", hit.title)
        assertTrue(hit.summary.startsWith("DeepSeek V4 is now available"))
        assertEquals("https://example.com/deepseek-v4", hit.url)
        assertEquals("2026-06-01T00:00:00+00:00", hit.age)
        assertEquals("Example News", hit.source)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("deepseek v4", body["query"]!!.jsonPrimitive.content)
        assertEquals(3, body["count"]!!.jsonPrimitive.int)
    }

    /** DeepSeek 没有时间窗参数，只能把 Brave 式的 pd/pw 换成 after: 日期。 */
    @Test
    fun `时间窗换成 after 运算符`() {
        val c = client()
        assertEquals("q after:2026-11-09", c.buildQuery("q", WebSearchProvider.FRESH_DAY, lang = ""))
        assertEquals("q after:2026-11-03", c.buildQuery("q", WebSearchProvider.FRESH_WEEK, lang = ""))
        assertEquals("q", c.buildQuery("q", "", lang = ""))
    }

    @Test
    fun `新闻检索带上语言和时间，国家丢掉`() = runBlocking {
        server.enqueue(MockResponse().setBody(payload))
        client().news("科技", freshness = WebSearchProvider.FRESH_DAY, country = "CN", searchLang = "zh-hans")
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("科技 lang:zh after:2026-11-09", body["query"]!!.jsonPrimitive.content)
    }

    /** 查询最多 128 字、运算符也算；截的是正文，运算符必须留全。 */
    @Test
    fun `超长查询截正文不截运算符`() {
        val query = client().buildQuery("长".repeat(300), WebSearchProvider.FRESH_DAY, lang = "ja")
        assertEquals(DeepSeekSearchClient.MAX_QUERY_CHARS, query.length)
        assertTrue(query.endsWith(" lang:ja after:2026-11-09"))
    }

    @Test
    fun `空结果当作没搜到，不是解析失败`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results": []}"""))
        val result = client().search("nothing")
        assertEquals("没搜到可用结果", result.failure)
    }

    @Test
    fun `余额不足和服务端报错如实说`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402))
        assertEquals("DeepSeek 账户余额不足（HTTP 402）", client().search("x").failure)

        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"message":"'count' must be between 1 and 50","type":"invalid_request_error","param":null,"code":"invalid_request_error"}}""",
            ),
        )
        assertEquals("DeepSeek 搜索失败：HTTP 400，'count' must be between 1 and 50", client().search("x").failure)
    }

    @Test
    fun `没配密钥不发请求`() = runBlocking {
        val c = client(key = "")
        assertFalse(c.isConfigured())
        assertEquals("没有配置 DeepSeek 密钥", c.search("x").failure)
        assertEquals(0, server.requestCount)
    }
}
