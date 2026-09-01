package com.openminis.app.provider.image

import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ImageModelCatalogTest {
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

    @Test
    fun `openai compatible source appends v1 when switch is enabled`() = runBlocking {
        server.enqueue(jsonModels())

        val models = ImageModelCatalog.fetch(
            providerType = ProviderType.openAI,
            baseURL = server.url("/").toString(),
            apiKey = "test-key",
            appendV1Suffix = true,
        )

        assertEquals("/v1/models", server.takeRequest().path)
        assertEquals(listOf("image-test"), models.map { it.id })
    }

    @Test
    fun `openai compatible source leaves address untouched when switch is disabled`() = runBlocking {
        server.enqueue(jsonModels())

        ImageModelCatalog.fetch(
            providerType = ProviderType.openAI,
            baseURL = server.url("/").toString(),
            apiKey = "test-key",
            appendV1Suffix = false,
        )

        assertEquals("/models", server.takeRequest().path)
    }

    @Test
    fun `html model response reports an address error instead of json parser internals`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<!doctype html><html><body>website</body></html>"),
        )

        val error = runCatching {
            ImageModelCatalog.fetch(
                providerType = ProviderType.openAI,
                baseURL = server.url("/").toString(),
                apiKey = "test-key",
                appendV1Suffix = false,
            )
        }.exceptionOrNull()!!

        assertTrue(error.message.orEmpty().contains("返回了网页"))
        assertTrue(error.message.orEmpty().contains("/v1"))
    }

    private fun jsonModels() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"data":[{"id":"image-test"}]}""")
}
