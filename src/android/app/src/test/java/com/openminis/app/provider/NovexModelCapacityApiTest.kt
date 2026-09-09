package com.openminis.app.provider

import com.openminis.app.provider.openai.OpenAIModelsApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class NovexModelCapacityApiTest {
    @Test fun providerReportedLimitsSurviveCatalogEnrichment() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[
                {"id":"deepseek-v4-flash","context_length":128000},
                {"id":"capacity-window-case","context_window":256000},
                {"id":"capacity-limit-case","limit":{"context":512000}},
                {"id":"capacity-unknown-case","context_length":-1}]}"""))
            val models = OpenAIModelsApi.fetchModels("test-only-key", server.url("/v1").toString())
            assertEquals(listOf(128_000, 256_000, 512_000, null), models.map { it.contextWindow })
            assertEquals("/v1/models", server.takeRequest().path)
        } finally { server.shutdown() }
    }
}
