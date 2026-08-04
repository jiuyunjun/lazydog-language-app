package com.lazydog.english.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
