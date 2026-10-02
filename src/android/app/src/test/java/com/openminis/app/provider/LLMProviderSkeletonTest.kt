package com.openminis.app.provider

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import novex.android.data.model.LLMError
import novex.android.data.model.LLMMessage
import novex.android.data.model.LLMModel
import novex.android.data.model.LLMResponse
import novex.android.data.model.LLMStreamChunk
import novex.android.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [P4] LLMProvider 骨架重写的行为钉：接口签名面冻结（全仓约 290 个消费
 * 方），重写只动实现体与换行——这里把三层协议各自钉死，防未来重构漂移：
 *  1. 思考档钳制（clampThinkingLevel）按当前 model 的目录天花板；
 *  2. 公共入口只钳一次、实现面收到的是钳后档位；
 *  3. 静默截断流检测（failOnSilentEmptyCompletion）的空/非空判定。
 */
class LLMProviderSkeletonTest {

    /** 记录实现面收到的档位；不做别的。 */
    private class RecordingProvider(override var model: LLMModel) : LLMProvider {
        override val name = "recording"
        var receivedLevel: ThinkingLevel? = null
        var receivedTemperature: Double? = null

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<novex.android.data.model.AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse {
            receivedLevel = thinkingLevel
            receivedTemperature = temperature
            return LLMResponse(text = "ok", stopReason = "stop", usage = null)
        }

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<novex.android.data.model.AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): kotlinx.coroutines.flow.Flow<LLMStreamChunk> {
            receivedLevel = thinkingLevel
            return flowOf(LLMStreamChunk.Finished("stop"))
        }
    }

    // ─── 档位钳制 ───────────────────────────────────────────────────────────

    @Test
    fun `clamp lowers an over-range request to the model ceiling`() {
        // claude-opus-4-6 家族天花板 = MAX（目录规则）。
        val provider = RecordingProvider(
            LLMModel("claude-opus-4-6", "Opus 4.6", "Anthropic", supportsReasoning = true),
        )
        assertEquals(ThinkingLevel.MAX, provider.clampThinkingLevel(ThinkingLevel.ULTRA))
        assertEquals(ThinkingLevel.MAX, provider.clampThinkingLevel(ThinkingLevel.MAX))
        assertEquals(ThinkingLevel.LOW, provider.clampThinkingLevel(ThinkingLevel.LOW))
    }

    @Test
    fun `clamp forces OFF for a model the catalog says cannot reason`() {
        val provider = RecordingProvider(
            LLMModel("claude-opus-4-6-tts", "Opus TTS", "Anthropic", supportsReasoning = false),
        )
        for (level in ThinkingLevel.entries) {
            assertEquals(ThinkingLevel.OFF, provider.clampThinkingLevel(level))
        }
    }

    @Test
    fun `clamp falls back to XHIGH for unknown models`() {
        val provider = RecordingProvider(
            LLMModel("totally-unknown-model", "Unknown", "Any", supportsReasoning = null),
        )
        assertEquals(ThinkingLevel.XHIGH, provider.clampThinkingLevel(ThinkingLevel.ULTRA))
        assertEquals(ThinkingLevel.XHIGH, provider.clampThinkingLevel(ThinkingLevel.XHIGH))
    }

    // ─── 公共入口 → 实现面 ─────────────────────────────────────────────────

    @Test
    fun `public entry clamps once and forwards the admitted level`() = runBlocking {
        val provider = RecordingProvider(
            LLMModel("totally-unknown-model", "Unknown", "Any", supportsReasoning = true),
        )
        provider.sendMessage(
            messages = emptyList(),
            systemPrompt = null,
            maxTokens = 512,
            temperature = 0.3,
            thinkingLevel = ThinkingLevel.MAX,
        )
        // 未知模型天花板 XHIGH < MAX——实现面必须收到钳后的 XHIGH，且不得再钳。
        assertEquals(ThinkingLevel.XHIGH, provider.receivedLevel)
        assertEquals(0.3, provider.receivedTemperature!!, 1e-9)
    }

    @Test
    fun `streaming entry forwards through the same clamp`() = runBlocking {
        val provider = RecordingProvider(
            LLMModel("claude-opus-4-6", "Opus 4.6", "Anthropic", supportsReasoning = true),
        )
        val chunks = provider.streamMessage(
            messages = emptyList(), systemPrompt = null, maxTokens = 8,
            thinkingLevel = ThinkingLevel.ULTRA,
        ).toList()
        // MAX 天花板：钳成 MAX。看门狗包在流上，正常流原样通过。
        assertEquals(ThinkingLevel.MAX, provider.receivedLevel)
        assertTrue(chunks.contains(LLMStreamChunk.Finished("stop")))
    }

    private suspend fun kotlinx.coroutines.flow.Flow<LLMStreamChunk>.toList(): List<LLMStreamChunk> {
        val out = mutableListOf<LLMStreamChunk>()
        collect { out.add(it) }
        return out
    }

    // ─── 输出上限协议 ──────────────────────────────────────────────────────

    @Test
    fun `effective max output tokens prefers the model field over the provider default`() {
        val provider = RecordingProvider(
            LLMModel("m", "M", "x", maxOutputTokens = 1_000),
        )
        assertEquals(1_000, provider.effectiveMaxOutputTokens(provider.model))
    }

    @Test
    fun `effective max output tokens falls back to 16384 when unknown`() {
        val provider = RecordingProvider(LLMModel("m", "M", "x", maxOutputTokens = null))
        assertEquals(16_384, provider.effectiveMaxOutputTokens(provider.model))
    }

    // ─── 静默截断流检测 ────────────────────────────────────────────────────

    @Test
    fun `a stream with no content and no finish reason is a transient failure`() {
        val stream = flowOf(LLMStreamChunk.Started).failOnSilentEmptyCompletion("test")
        try {
            runBlocking { stream.collect { } }
            fail("期望抛 TransientError")
        } catch (expected: LLMError.TransientError) {
            assertTrue(expected.message!!.contains("empty response"))
        }
    }

    @Test
    fun `empty text deltas alone still count as an empty run`() {
        val stream = flowOf(
            LLMStreamChunk.Started,
            LLMStreamChunk.Text(""),
            LLMStreamChunk.ThinkingDelta(""),
        ).failOnSilentEmptyCompletion("test")
        try {
            runBlocking { stream.collect { } }
            fail("期望抛 TransientError")
        } catch (expected: LLMError.TransientError) {
            // 零宽增量不是内容。
        }
    }

    @Test
    fun `a finish reason without content is a legitimate completion`() {
        val stream = flowOf(LLMStreamChunk.Finished("stop")).failOnSilentEmptyCompletion("test")
        runBlocking { stream.collect { } } // 不抛。
    }

    @Test
    fun `any real content makes the run non-empty`() {
        val cases = listOf(
            LLMStreamChunk.Text("hi"),
            LLMStreamChunk.ThinkingDelta("thinking"),
            LLMStreamChunk.ReasoningContent("reasoning"),
            LLMStreamChunk.MediaAttachment(
                novex.android.data.model.LLMMediaAttachment(
                    type = novex.android.data.model.LLMMediaAttachment.MediaType.IMAGE,
                    mimeType = "image/png",
                    data = byteArrayOf(1),
                ),
            ),
        )
        for (chunk in cases) {
            val stream = flowOf(chunk).failOnSilentEmptyCompletion("test")
            runBlocking { stream.collect { } } // 任何一种实质内容都不抛。
        }
    }
}
