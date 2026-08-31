package com.openminis.app.ui.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexModelVerificationTest {
    @Test
    fun `chat only model skips tool probes and is reported as available`() = runBlocking {
        var toolCalls = 0

        val result = verifyNovexModels(
            modelIds = listOf("gemini-chat-only"),
            repetitions = 3,
            shouldProbeTools = { false },
            chatProbe = { null },
            toolProbe = {
                toolCalls++
                "must not run"
            },
        )

        assertEquals(0, toolCalls)
        assertEquals(listOf("gemini-chat-only"), result.availableModels)
        assertEquals(listOf("gemini-chat-only"), result.toolDisabledModels)
        assertEquals(
            "gemini-chat-only：普通对话通过 · 工具已关闭",
            formatNovexModelVerificationLine(result, "gemini-chat-only"),
        )
    }

    @Test
    fun `one unavailable model is skipped and every remaining model is checked`() = runBlocking {
        val chatVisited = mutableListOf<String>()
        val toolVisited = mutableListOf<String>()

        val result = verifyNovexModels(
            modelIds = listOf("bad-chat", "bad-tool", "good"),
            chatProbe = { model ->
                chatVisited += model
                if (model == "bad-chat") "HTTP 404" else null
            },
            toolProbe = { model ->
                toolVisited += model
                if (model == "bad-tool") "没有结构化工具调用" else null
            },
        )

        assertEquals(listOf("bad-chat", "bad-tool", "good"), chatVisited)
        assertEquals(listOf("bad-tool", "good"), toolVisited)
        assertEquals(listOf("good"), result.availableModels)
        assertEquals(listOf("bad-chat", "bad-tool"), result.failures.map { it.modelId })
        assertTrue(formatNovexVerificationReport(result).contains("HTTP 404"))
        assertTrue(formatNovexVerificationReport(result).contains("没有结构化工具调用"))
    }

    @Test
    fun `each model is checked three times and an intermittent failure does not abort later models`() = runBlocking {
        val chatAttempts = mutableMapOf<String, Int>()
        val toolAttempts = mutableMapOf<String, Int>()
        val progress = mutableListOf<Triple<Int, Int, String>>()

        val result = verifyNovexModels(
            modelIds = listOf("flaky", "good"),
            repetitions = 3,
            onProgress = { _, modelIndex, modelCount, model ->
                progress += Triple(modelIndex, modelCount, model)
            },
            chatProbe = { model ->
                chatAttempts[model] = (chatAttempts[model] ?: 0) + 1
                null
            },
            toolProbe = { model ->
                val attempt = (toolAttempts[model] ?: 0) + 1
                toolAttempts[model] = attempt
                if (model == "flaky" && attempt == 2) {
                    "HTTP 200，finish_reason=stop，但没有文字或结构化工具调用"
                } else {
                    null
                }
            },
        )

        assertEquals(mapOf("flaky" to 3, "good" to 3), chatAttempts)
        assertEquals(mapOf("flaky" to 3, "good" to 3), toolAttempts)
        assertEquals(listOf("flaky", "good"), result.availableModels)
        assertTrue(result.failures.isEmpty())
        assertTrue(result.warnings.single().detail.contains("第 2/3 轮"))
        assertTrue(result.warnings.single().detail.contains("通过 2/3"))
        assertTrue(formatNovexVerificationReport(result).contains("不稳定但可用"))
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { (_, modelCount, _) -> modelCount <= 2 })
        assertTrue(progress.none { (_, modelCount, _) -> modelCount == 6 })
    }

    @Test
    fun `model that fails the majority is unavailable and later models still run`() = runBlocking {
        val visited = mutableListOf<String>()
        val attempts = mutableMapOf<String, Int>()

        val result = verifyNovexModels(
            modelIds = listOf("mostly-broken", "good"),
            repetitions = 3,
            chatProbe = { null },
            toolProbe = { model ->
                visited += model
                val attempt = (attempts[model] ?: 0) + 1
                attempts[model] = attempt
                if (model == "mostly-broken" && attempt <= 2) "无结构化调用" else null
            },
        )

        assertEquals(listOf("good"), result.availableModels)
        assertEquals(listOf("mostly-broken", "mostly-broken", "mostly-broken", "good", "good", "good"), visited)
        assertTrue(result.failures.single().detail.contains("仅通过 1/3"))
    }
}
