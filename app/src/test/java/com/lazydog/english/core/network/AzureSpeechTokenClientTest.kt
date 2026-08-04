package com.lazydog.english.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeechTokenUrlTest {

    @Test
    fun `builds regional issue token url`() {
        assertEquals(
            "https://southeastasia.api.cognitive.microsoft.com/sts/v1.0/issueToken",
            speechTokenUrl(" southeastasia "),
        )
    }
}

class AzureSpeechTokenClientTest {

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

    private fun client() = AzureSpeechTokenClient(
        subscriptionKey = "test-key",
        tokenUrl = server.url("/sts/v1.0/issueToken").toString(),
    )

    @Test
    fun `success on 200 and sends subscription key header`() = runBlocking {
        server.enqueue(MockResponse().setBody("token"))

        val result = client().testConnection()

        assertTrue(result is AzureSpeechTokenClient.TokenResult.Success)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("test-key", recorded.getHeader("Ocp-Apim-Subscription-Key"))
    }

    @Test
    fun `401 maps to readable failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client().testConnection()

        val failure = result as AzureSpeechTokenClient.TokenResult.Failure
        assertTrue(failure.reason.contains("401"))
    }
}
