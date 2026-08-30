package com.openminis.app.ui.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexModelVerificationTest {
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
        assertEquals(listOf("good"), result.availableModels)
        assertTrue(result.failures.single().detail.contains("第 2/3 轮"))
        assertTrue(progress.isNotEmpty())
        assertTrue(progress.all { (_, modelCount, _) -> modelCount <= 2 })
        assertTrue(progress.none { (_, modelCount, _) -> modelCount == 6 })
    }
}
