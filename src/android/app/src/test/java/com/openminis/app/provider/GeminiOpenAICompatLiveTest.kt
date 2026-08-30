package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.tools.AgentTools
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional real-service regression for the Gemini OpenAI-compatible route.
 * The test is skipped unless NOVEX_GEMINI_API_KEY is provided by the caller.
 */
class GeminiOpenAICompatLiveTest {
    @Test
    fun `gemini works through the same OpenAI compatible path used by the app`() = runBlocking {
        val apiKey = System.getenv("NOVEX_GEMINI_API_KEY").orEmpty()
        assumeTrue("NOVEX_GEMINI_API_KEY is not configured", apiKey.isNotBlank())

        val baseUrl = System.getenv("NOVEX_GEMINI_BASE_URL")
            ?.trimEnd('/')
            ?.takeIf(String::isNotEmpty)
            ?: "https://sub.sailapi.top/v1"
        val modelId = System.getenv("NOVEX_GEMINI_MODEL")
            ?.takeIf(String::isNotBlank)
            ?: "gemini-3.1-pro"
        val instance = ProviderInstance(
            id = "gemini-openai-compat-live-test",
            label = "Gemini",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = baseUrl,
            appendV1Suffix = !baseUrl.endsWith("/v1"),
        )
        val provider = ProviderFactory.create(
            instance = instance,
            apiKey = apiKey,
            model = LLMModel(modelId, modelId, "OpenAI-compatible"),
        )
        val tools = AgentTools.makeAgentTools(
            supportsImageInput = false,
            visionGroupConfigured = false,
            memoryEnabled = true,
        )

        repeat(3) { attempt ->
            val chat = provider.sendMessage(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, "只回复：应用路径连接成功")),
                systemPrompt = null,
                maxTokens = 64,
                tools = tools,
            )
            assertNotNull(
                "Gemini stream ended without finish_reason on attempt ${attempt + 1}",
                chat.stopReason,
            )
            assertTrue(
                "Gemini returned an empty response with the full app tool set on attempt ${attempt + 1}",
                chat.text.isNotBlank(),
            )

            val toolChunks = provider.streamMessage(
                messages = listOf(
                    LLMMessage(
                        LLMMessage.Role.USER,
                        "请立即调用 present_choices，提供“继续”和“返回”两个选项，不要输出文字。",
                    ),
                ),
                systemPrompt = null,
                maxTokens = 256,
                tools = tools,
            ).toList()
            assertTrue(
                "Gemini did not return a structured present_choices call with the full app tool set on attempt ${attempt + 1}",
                toolChunks.any { it is LLMStreamChunk.ToolCallComplete && it.name == "present_choices" },
            )
            assertTrue(
                "Gemini tool stream ended without finish_reason on attempt ${attempt + 1}",
                toolChunks.any { it is LLMStreamChunk.Finished && it.stopReason != null },
            )
        }
    }
}
