package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexLearningToolsTest {
    private val collectionRef = NovexResourceRef("novex://source-collections/large")
    private val snapshot = NovexLearningPreflight.prepare(
        NovexLearningPreflightRequest(
            collectionRef = collectionRef,
            sources = listOf(
                NovexLearningSourceEstimate(
                    ref = NovexResourceRef("novex://documents/long"),
                    estimatedTokens = 90_000,
                    pageCount = 120,
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

    @Test
    fun preparationToolReturnsAReadOnlyPlanAndTellsTheModelToWaitForNativeConfirmation() {
        var prepareCalls = 0
        val router = NovexLearningToolRouter(
            NovexLearningTools { requestedRef, requestedModel ->
                prepareCalls += 1
                snapshot.takeIf {
                    requestedRef == collectionRef && (requestedModel == null || requestedModel == "model-a")
                }
            },
        )

        val result = router.execute(
            name = "learning_prepare",
            argumentsJson = JSONObject().put("collection_ref", collectionRef.value).toString(),
        )
        val json = JSONObject(result.toJson())
        val data = json.getJSONObject("data")

        assertTrue(result.ok)
        assertEquals(1, prepareCalls)
        assertEquals("learning.preflight_ready", result.code)
        assertEquals("none", json.getString("side_effect"))
        assertEquals(snapshot.id, data.getString("preflight_id"))
        assertEquals(90_000, data.getInt("estimated_source_tokens"))
        assertTrue(data.getBoolean("requires_confirmation"))
        assertTrue(result.nextActions.any { it.id == "wait_for_native_confirmation" })
        assertFalse(result.nextActions.any { it.id == "learning_start" })
    }

    @Test
    fun unknownOrOutOfScopeCollectionReturnsStableFailureWithoutStartingAnything() {
        val router = NovexLearningToolRouter(NovexLearningTools { _, _ -> null })

        val result = router.execute(
            name = "learning_prepare",
            argumentsJson = JSONObject()
                .put("collection_ref", "novex://source-collections/missing")
                .toString(),
        )

        assertFalse(result.ok)
        assertEquals("learning.collection_not_found", result.code)
        assertEquals(NovexToolSideEffect.NONE, result.sideEffect)
    }
}
