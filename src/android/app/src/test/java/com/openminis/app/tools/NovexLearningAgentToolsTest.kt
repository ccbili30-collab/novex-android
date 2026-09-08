package com.openminis.app.tools

import com.openminis.app.novex.domain.NovexLearningPreflight
import com.openminis.app.novex.domain.NovexLearningPreflightRequest
import com.openminis.app.novex.domain.NovexLearningSourceEstimate
import com.openminis.app.novex.domain.NovexLearningTokenBudget
import com.openminis.app.novex.domain.NovexResourceRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class NovexLearningAgentToolsTest {
    @Test
    fun `preflight adapter does not start the saved task`() = runBlocking {
        val collectionRef = NovexResourceRef("novex://source-collections/active")
        val preflight = NovexLearningPreflight.prepare(
            NovexLearningPreflightRequest(
                collectionRef = collectionRef,
                sources = listOf(
                    NovexLearningSourceEstimate(
                        ref = NovexResourceRef("novex://documents/long"),
                        estimatedTokens = 90_000,
                    ),
                ),
                modelId = "model-a",
                modelProviderName = "测试模型提供商",
                effectiveContextTokens = 200_000,
                occupiedContextTokens = 20_000,
                directReadBudgetTokens = 12_000,
                proposedBudget = NovexLearningTokenBudget(120_000, 12_000),
            ),
        )
        val tools = NovexLearningAgentTools(com.openminis.app.novex.domain.NovexLearningPreflightResolver { requested, _ ->
            preflight.takeIf { requested == collectionRef }
        }, start = { _, _ -> error("准备计划不得启动整理") })

        val result = tools.execute(
            "learning_prepare",
            JSONObject().put("collection_ref", collectionRef.value).toString(),
        )

        assertTrue(result.success)
        assertEquals("准备资料学习", result.toolTitle)
        assertTrue(result.output.contains("learning.preflight_ready"))
        assertTrue(result.output.contains("learning_start"))
    }
}
