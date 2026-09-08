package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NovexLearningToolsTest {
    @Test fun `preparing a saved stopped task points to its notes instead of requesting a fresh paid run`() {
        listOf(NovexLearningTaskStatus.COMPLETE, NovexLearningTaskStatus.PARTIAL_FAILURE,
            NovexLearningTaskStatus.CANCELLED, NovexLearningTaskStatus.PAUSED_BUDGET_REACHED).forEach { status ->
            val tools = NovexLearningTools { _, _ -> snapshot.copy(taskStatus = status) }
            val result = tools.learningPrepare(collectionRef, null)
            assertTrue(result.ok)
            assertEquals(status.name, JSONObject(result.toJson()).getJSONObject("data").optString("task_status"))
            assertFalse("$status 不得重开付费确认", result.nextActions.any { it.id == "wait_for_native_confirmation" })
            assertTrue(result.nextActions.any { it.id == "learning_read" })
            assertTrue(result.nextActions.any { it.id == "open_native_learning_status" })
        }
    }

    @Test fun `saved learning notes are readable with source anchors and bounded continuation without restarting learning`() {
        val directory = Files.createTempDirectory("novex-note-read").toFile()
        try {
            val sourceRef = NovexResourceRef("novex://documents/source-a")
            val collection = NovexSourceCollection(collectionRef,
                NovexResourceRef("novex://conversation-branches/branch-a"), "资料集", listOf(
                    NovexCollectionSource(NovexResourceRef("novex://sources/a"), "原文", "a".repeat(64),
                        NovexSourceStatus.READY, sourceRef, listOf("block_a")),
                ), 0, 0)
            val body = "整理后的规则与关系。".repeat(600)
            val note = NovexLearningNote(NovexResourceRef("novex://learning-notes/note-a"),
                NovexLearningNoteLevel.SECTION, "规则笔记", body, listOf(sourceRef), listOf("block_a"))
            FileNovexLearningRepository(directory).save(NovexLearningState(collection,
                NovexReviewLedger.start(collection), notes = listOf(note)))
            val router = NovexLearningToolRouter(NovexLearningTools(object : NovexLearningPreflightResolver {
                override fun prepare(collectionRef: NovexResourceRef, modelId: String?): NovexLearningPreflightSnapshot? =
                    error("回读不能重新发起学习预检")
                override fun readState(collectionRef: NovexResourceRef): NovexLearningState? =
                    FileNovexLearningRepository(directory).find(collectionRef).takeIf { collectionRef == collection.ref }
            }))
            val received = StringBuilder()
            var cursor: String? = null
            var pages = 0
            do {
                val args = JSONObject().put("collection_ref", collectionRef.value).put("max_chars", 1000)
                cursor?.let { args.put("cursor", it) }
                val result = router.execute("learning_read", args.toString())
                assertTrue(result.summary, result.ok)
                assertEquals(NovexToolSideEffect.NONE, result.sideEffect)
                val data = JSONObject(result.toJson()).getJSONObject("data")
                val blocks = data.getJSONArray("blocks")
                var pageChars = 0
                for (i in 0 until blocks.length()) {
                    val text = blocks.getJSONObject(i).getString("text")
                    received.append(text)
                    pageChars += text.length
                }
                assertTrue(pageChars <= 1000)
                assertTrue(data.getJSONArray("note_sources").toString().contains(sourceRef.value))
                cursor = data.optString("next_cursor").ifBlank { null }
                pages++
                assertTrue(pages < 20)
            } while (cursor != null)
            assertEquals(body, received.toString())
            assertTrue(pages > 1)
            assertFalse(router.execute("learning_read", "{\"collection_ref\":\"novex://source-collections/other\"}").ok)
        } finally { directory.deleteRecursively() }
    }

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
    fun preparationToolReturnsAReadOnlyPlanAndPointsToTheCommonStartGate() {
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
        assertEquals(3, data.getJSONObject("estimated_duration").getInt("minimum_minutes"))
        assertEquals(12, data.getJSONObject("estimated_duration").getInt("maximum_minutes"))
        assertEquals(
            "测试模型提供商",
            data.getJSONObject("data_exposure").getString("destination"),
        )
        assertTrue(
            data.getJSONObject("data_exposure").getBoolean("source_content_may_leave_device"),
        )
        assertTrue(data.getBoolean("requires_background_review"))
        assertFalse(result.nextActions.any { it.id == "wait_for_native_confirmation" })
        assertTrue(result.nextActions.any { it.id == "learning_start" })
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

    @Test
    fun activeLearningTaskIsReportedInsteadOfCreatingAnotherConfirmationPlan() {
        val tools = NovexLearningTools { requested, _ ->
            snapshot.copy(taskStatus = NovexLearningTaskStatus.REVIEWING)
                .takeIf { requested == collectionRef }
        }

        val result = tools.learningPrepare(collectionRef, null)
        val data = JSONObject(result.toJson()).getJSONObject("data")

        assertTrue(result.ok)
        assertEquals("learning.task_active", result.code)
        assertEquals("REVIEWING", data.getString("task_status"))
        assertFalse(result.nextActions.any { it.id == "wait_for_native_confirmation" })
        assertTrue(result.nextActions.any { it.id == "open_native_learning_status" })
    }
}
