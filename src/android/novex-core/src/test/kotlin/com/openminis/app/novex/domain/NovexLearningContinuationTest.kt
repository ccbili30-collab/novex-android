package com.openminis.app.novex.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NovexLearningContinuationTest {
    private val sha = "a".repeat(64)
    private val doc = NovexDocumentSnapshot(NovexResourceRef("novex://documents/$sha"), sha, "parser-a", "资料",
        NovexDocumentFormat.TEXT, NovexDocumentStatus.READY, (0..2).map { i ->
            val anchor = NovexDocumentSourceAnchor("source", i)
            NovexDocumentBlock(NovexDocumentBlockId.from(sha, anchor), NovexDocumentBlockKind.PARAGRAPH,
                i, "事实 $i：原文没有提供时刻。".repeat(40), source = anchor)
        })
    private val documents = NovexDocumentSnapshotStore { doc }
    private val collection = NovexSourceCollectionBuilder.create(NovexResourceRef("novex://source-collections/continuation"),
        NovexResourceRef("novex://conversation-branches/a"), "资料集",
        listOf(NovexSourceImportResult(NovexResourceRef("novex://sources/a"), doc.title, sha, doc)), 1000)

    private fun preflight(state: NovexLearningState, model: String, fingerprint: String? = null,
        document: NovexDocumentSnapshot = doc) = NovexLearningPreflight.prepare(NovexLearningPreflightRequest(
            collection.ref, listOf(NovexLearningSourceEstimate(collection.sources.single().ref, 20_000)), model,
            effectiveContextTokens = 16_384, occupiedContextTokens = 0, directReadBudgetTokens = 100,
            proposedBudget = NovexLearningTokenBudget(200_000, 40_000), sourcePlanFingerprint = fingerprint,
            sourceDocuments = mapOf(collection.sources.single().ref to document)), state)
    private fun confirmation(p: NovexLearningPreflightSnapshot) = NovexLearningConfirmation(p.id, p.modelId,
        p.sourceRefs, p.confirmedBudget.inputTokens, p.confirmedBudget.outputTokens, 2000)
    private fun started(): NovexLearningState {
        val state = NovexLearningState(collection, NovexReviewLedger.start(collection))
        val p = preflight(state, "model-a")
        return state.copy(task = NovexLearningCoordinator().start(p, confirmation(p)), preflight = p)
    }
    private class Reviewer : NovexLearningReviewer {
        val blocks = mutableListOf<String>()
        var syntheses = 0
        override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
            blocks += request.blocks.map { it.id }
            return NovexLearningReviewOutput("本批", "只记录原文已有事实。", 100, 20)
        }
        override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
            syntheses++
            return NovexLearningReviewOutput("总览", "没有提供时刻。", 200, 30)
        }
    }

    @Test fun `new model resumes saved ranges after restart and retains cumulative usage and history`() = runBlocking {
        val root = Files.createTempDirectory("learning-handoff").toFile()
        try {
            var repo = FileNovexLearningRepository(root)
            val first = Reviewer()
            assertThrows(IllegalStateException::class.java) { runBlocking {
                NovexLearningReviewRunner(documents, first, { state ->
                    repo.save(state)
                    if (state.notes.size == 1) error("模拟界面更新前中断")
                }, maxBlocksPerBatch = 1, responseJournal = repo).run(started())
            } }
            repo = FileNovexLearningRepository(root)
            val saved = requireNotNull(repo.find(collection.ref))
            val paused = saved.copy(task = requireNotNull(saved.task).pause())
            repo.save(paused)
            val original = requireNotNull(FileNovexLearningRepository(root).find(collection.ref))
            val prepared = NovexLearningContinuation.prepareState(original, documents, NovexLearningContinuationMode.CURRENT_SOURCES)
            val p = preflight(prepared, "model-b", NovexLearningContinuation.originFingerprint(original))
            assertTrue(p.requiresConfirmation)
            assertThrows(IllegalArgumentException::class.java) {
                NovexLearningContinuation.confirm(original, prepared, NovexLearningContinuationMode.CURRENT_SOURCES, p, null)
            }
            val continued = NovexLearningContinuation.confirm(original, prepared, NovexLearningContinuationMode.CURRENT_SOURCES, p, confirmation(p))
            repo.save(continued)
            val second = Reviewer()
            val result = NovexLearningReviewRunner(documents, second, repo::save, maxBlocksPerBatch = 1,
                responseJournal = repo).run(requireNotNull(FileNovexLearningRepository(root).find(collection.ref)))
            assertEquals(NovexLearningTaskStatus.COMPLETE, result.task?.status)
            assertEquals(listOf(doc.blocks.first().id), first.blocks)
            assertEquals(doc.blocks.drop(1).map { it.id }, second.blocks)
            assertEquals(500, result.task?.usage?.usedInputTokens)
            assertEquals(90, result.task?.usage?.usedOutputTokens)
            assertEquals("model-a", result.previousTasks.single().preflight.modelId)
            assertEquals(100, result.previousTasks.single().usage.usedInputTokens)
            assertEquals(3, result.reviewLedger.reviewedBlocks)
            val reopened = requireNotNull(FileNovexLearningRepository(root).find(collection.ref))
            assertEquals(500, reopened.task?.usage?.usedInputTokens)
            assertEquals(original.notes.first(), reopened.notes.first())
            assertEquals("model-b", reopened.task?.preflight?.modelId)
        } finally { root.deleteRecursively() }
    }

    @Test fun `source recheck preserves unknown legacy notes without granting them new coverage`() = runBlocking {
        var saved = started()
        assertThrows(IllegalStateException::class.java) { runBlocking {
            NovexLearningReviewRunner(documents, Reviewer(), {
                saved = it
                if (it.notes.size == 1) error("中断")
            }, maxBlocksPerBatch = 1).run(saved)
        } }
        val legacy = saved.copy(task = requireNotNull(saved.task).pause(), notes = saved.notes.map { it.copy(sourceRevisions = emptyMap()) })
        val changed = doc.copy(parserVersion = "parser-b", blocks = doc.blocks.map { it.copy(text = it.text + "新解析内容") })
        val prepared = NovexLearningContinuation.prepareState(legacy, NovexDocumentSnapshotStore { changed }, NovexLearningContinuationMode.RECHECK_SOURCES)
        assertEquals(1, legacy.reviewLedger.reviewedBlocks)
        assertEquals(0, prepared.reviewLedger.reviewedBlocks)
        assertEquals(legacy.notes, prepared.historicalNotes)
        val p = preflight(prepared, "model-b", NovexLearningContinuation.originFingerprint(legacy), changed)
        val result = NovexLearningContinuation.confirm(legacy, prepared, NovexLearningContinuationMode.RECHECK_SOURCES, p, confirmation(p))
        val reopened = NovexLearningStateJsonCodec.decode(NovexLearningStateJsonCodec.encode(result))
        assertEquals(legacy.notes, reopened.historicalNotes)
        assertTrue(reopened.notes.isEmpty())
        assertEquals(100, reopened.task?.usage?.usedInputTokens)
        assertEquals(NovexSourceReadEvidence.documentRevision(changed), reopened.task?.preflight?.documentRevisions?.get(doc.ref))
        val tools = NovexLearningTools(object : NovexLearningPreflightResolver {
            override fun prepare(collectionRef: NovexResourceRef, modelId: String?): NovexLearningPreflightSnapshot? = error("只读不可启动")
            override fun readState(collectionRef: NovexResourceRef) = reopened.takeIf { collectionRef == it.collection.ref }
        })
        assertEquals("learning.notes_empty", tools.learningRead(collection.ref, org.json.JSONObject()).code)
        val historical = tools.learningRead(collection.ref, org.json.JSONObject().put("note_set", "history"))
        assertTrue(historical.ok)
        assertEquals("history", historical.data["note_set"])
        assertTrue(historical.summary.contains("不计入当前解析"))
        assertFalse(tools.learningRead(NovexResourceRef("novex://source-collections/other"), org.json.JSONObject().put("note_set", "history")).ok)
    }

    @Test fun `late usage invalidates continuation confirmation`() {
        val state = started().let { it.copy(task = requireNotNull(it.task).pause()) }
        val prepared = NovexLearningContinuation.prepareState(state, documents, NovexLearningContinuationMode.CURRENT_SOURCES)
        val p = preflight(prepared, "model-b", NovexLearningContinuation.originFingerprint(state))
        val receipt = NovexLearningResponseReceipt("late", requireNotNull(state.task).preflightId, "request", NovexLearningReviewOutput("返回", "正文", 11, 7))
        val changed = state.accountFor(listOf(receipt))
        assertNotEquals(p.id, preflight(changed, "model-b", NovexLearningContinuation.originFingerprint(changed)).id)
        assertThrows(IllegalArgumentException::class.java) {
            NovexLearningContinuation.confirm(changed, changed, NovexLearningContinuationMode.CURRENT_SOURCES, p, confirmation(p))
        }
    }
}
