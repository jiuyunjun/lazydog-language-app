package com.lazydog.english.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelsUrlTest {

    @Test
    fun `appends models path`() {
        assertEquals("https://api.openai.com/v1/models", modelsUrl("https://api.openai.com/v1"))
    }

    @Test
    fun `tolerates trailing slash and whitespace`() {
        assertEquals("https://api.openai.com/v1/models", modelsUrl(" https://api.openai.com/v1/ "))
    }
}

class ChatModelFilterTest {

    @Test
    fun `chat models are kept`() {
        listOf("gpt-5.5", "gpt-4o-mini", "o3", "deepseek-chat", "qwen2.5-72b-instruct", "claude-opus-5")
            .forEach { assertTrue(it, looksLikeChatModel(it)) }
    }

    @Test
    fun `models that cannot chat are filtered out`() {
        // 选中一个生图或嵌入模型，要到真正生成时才报错——所以在选之前就筛掉。
        listOf(
            "text-embedding-3-large", "bge-m3", "jina-reranker-v2", "gpt-image-1", "dall-e-3",
            "flux.1-dev", "stable-diffusion-3.5", "whisper-1", "gpt-4o-mini-tts",
            "gpt-4o-realtime-preview", "omni-moderation-latest", "sora-2", "cogvideox",
        ).forEach { assertFalse(it, looksLikeChatModel(it)) }
    }

    @Test
    fun `an unknown name is kept rather than hidden`() {
        // 筛选只是按名字猜。猜不出来的一律留下，宁可多列一个也别把新模型藏起来。
        assertTrue(looksLikeChatModel("some-new-model-2027"))
    }
}

class OpenAiCompatClientTest {

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

    private fun client() = OpenAiCompatClient(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
    )

    @Test
    fun `the model list comes back sorted and de-duplicated`() {
        // 设置页只让用户在这个列表里挑，手打模型名是这套配置最容易出错的地方。
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"object":"list","data":[{"id":"z-model"},{"id":"a-model"},{"id":"a-model"},{"id":" "}]}""",
                ),
            )

            val result = client().listModels()

            assertEquals(
                listOf("a-model", "z-model"),
                (result as OpenAiCompatClient.ModelsResult.Success).models,
            )
        }
    }

    @Test
    fun `an empty model list is reported as a failure instead of an empty picker`() {
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"object":"list","data":[]}"""))

            val result = client().listModels()

            assertTrue((result as OpenAiCompatClient.ModelsResult.Failure).reason.contains("空"))
        }
    }

    @Test
    fun `a rejected key is explained when listing models`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(401))

            val result = client().listModels()

            assertTrue((result as OpenAiCompatClient.ModelsResult.Failure).reason.contains("401"))
        }
    }

    @Test
    fun `success when model is listed`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"object":"list","data":[{"id":"gpt-5.5"},{"id":"other"}]}"""),
        )

        val result = client().testConnection("gpt-5.5")

        val success = result as OpenAiCompatClient.ConnectionResult.Success
        assertEquals(2, success.modelCount)
        assertTrue(success.modelListed)

        val recorded = server.takeRequest()
        assertEquals("/v1/models", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `success but model missing from list`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"other"}]}"""))

        val result = client().testConnection("gpt-5.5")

        val success = result as OpenAiCompatClient.ConnectionResult.Success
        assertEquals(false, success.modelListed)
    }

    @Test
    fun `unauthorized maps to readable failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        val result = client().testConnection("gpt-5.5")

        val failure = result as OpenAiCompatClient.ConnectionResult.Failure
        assertTrue(failure.reason.contains("401"))
    }

    @Test
    fun `malformed body maps to failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))

        val result = client().testConnection("gpt-5.5")

        assertTrue(result is OpenAiCompatClient.ConnectionResult.Failure)
    }
}
