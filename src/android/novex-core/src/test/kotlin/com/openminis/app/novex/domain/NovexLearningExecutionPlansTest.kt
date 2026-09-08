package com.openminis.app.novex.domain

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class NovexLearningExecutionPlansTest {
    private val sha = "a".repeat(64)
    private val document = NovexDocumentSnapshot(NovexResourceRef("novex://documents/$sha"), sha, "parser-a", "原资料",
        NovexDocumentFormat.TEXT, NovexDocumentStatus.READY, (0..3).map { i ->
            val anchor = NovexDocumentSourceAnchor("source", i)
            NovexDocumentBlock(NovexDocumentBlockId.from(sha, anchor), NovexDocumentBlockKind.PARAGRAPH,
                i, "姓名和日期必须忠于原文。".repeat(400), source = anchor)
        })
    private val collection = NovexSourceCollectionBuilder.create(NovexResourceRef("novex://source-collections/test"),
        NovexResourceRef("novex://conversation-branches/mine"), "待整理资料",
        listOf(NovexSourceImportResult(NovexResourceRef("novex://sources/a"), document.title, sha, document)), 0)
    private val model = NovexLearningPlanningModel("model-a", "测试提供商", 32768, 4096, 0)

    private fun fixture(block: (java.io.File, FileNovexLearningRepository, NovexLearningExecutionPlans,
        (NovexLearningState, NovexLearningTokenBudget?, String?) -> NovexLearningPreflightSnapshot) -> Unit) {
        val root = Files.createTempDirectory("learning-execution").toFile()
        try {
            val repo = FileNovexLearningRepository(java.io.File(root, "state"))
            repo.save(NovexLearningState(collection, NovexReviewLedger.start(collection)))
            val documents = NovexDocumentSnapshotStore { document }
            val plans = NovexLearningExecutionPlans(repo, documents, java.io.File(root, "plans"))
            val builder = NovexLearningPreflightBuilder(documents)
            block(root, repo, plans) { state, budget, fingerprint -> builder.build(state, model, budget, fingerprint) }
        } finally { root.deleteRecursively() }
    }
    private fun allowed(ref: NovexResourceRef) = ref == collection.ref

    @Test fun `prepare does not run and reopened exact plan commits once without a UI pending object`() = fixture { root, repo, plans, build ->
        val p = plans.prepare("mine", collection.ref, NovexLearningPlanAction.START, ::allowed, build)
        assertNull(repo.find(collection.ref)?.task)
        assertTrue(plans.review("mine", collection.ref, p.id, ::allowed).contains(p.confirmedBudget.inputTokens.toString()))
        assertEquals(p.id, plans.prepare("mine", collection.ref, NovexLearningPlanAction.START, ::allowed, build).id)
        val reopened = NovexLearningExecutionPlans(FileNovexLearningRepository(java.io.File(root, "state")),
            NovexDocumentSnapshotStore { document }, java.io.File(root, "plans"))
        var checks = 0
        val first = reopened.commit("mine", collection.ref, p.id, ::allowed, build) { checks++ }
        assertFalse(first.replayed)
        assertEquals(NovexLearningTaskStatus.INDEXING, repo.find(collection.ref)?.task?.status)
        val paused = first.state.copy(task = first.state.task!!.pause())
        repo.save(paused)
        val replay = reopened.commit("mine", collection.ref, p.id, ::allowed, build) { checks++ }
        assertTrue(replay.replayed)
        assertEquals(NovexLearningTaskStatus.PAUSED, replay.state.task?.status)
        assertEquals(1, checks)
        val cancelled = paused.copy(task = paused.task!!.cancel())
        repo.save(cancelled)
        assertEquals(NovexLearningTaskStatus.CANCELLED,
            reopened.commit("mine", collection.ref, p.id, ::allowed, build) {}.state.task?.status)
    }

    @Test fun `foreign conversation source removal and changed model cannot execute an approved plan`() = fixture { _, repo, plans, build ->
        val p = plans.prepare("mine", collection.ref, NovexLearningPlanAction.START, ::allowed, build)
        assertThrows(IllegalArgumentException::class.java) { plans.commit("other", collection.ref, p.id, ::allowed, build) {} }
        assertThrows(IllegalArgumentException::class.java) { plans.commit("mine", collection.ref, p.id, { false }, build) {} }
        val changedBuilder = NovexLearningPreflightBuilder(NovexDocumentSnapshotStore { document })
        assertThrows(IllegalArgumentException::class.java) {
            plans.commit("mine", collection.ref, p.id, ::allowed,
                { state, budget, fingerprint -> changedBuilder.build(state, model.copy(id = "model-b"), budget, fingerprint) }) {}
        }
        assertNull(repo.find(collection.ref)?.task)
    }

    @Test fun `continuation preserves cumulative use and notes and rejects a plan after progress changed`() = fixture { _, repo, plans, build ->
        val p = plans.prepare("mine", collection.ref, NovexLearningPlanAction.START, ::allowed, build)
        val first = plans.commit("mine", collection.ref, p.id, ::allowed, build) {}.state
        val usedTask = first.task!!.recordUsage(100, 20).pause()
        val saved = first.copy(task = usedTask)
        repo.save(saved)
        val next = plans.prepare("mine", collection.ref, NovexLearningPlanAction.CONTINUE, ::allowed, build)
        val continued = plans.commit("mine", collection.ref, next.id, ::allowed, build) {}.state
        assertEquals(100, continued.task?.usage?.usedInputTokens)
        assertEquals(20, continued.task?.usage?.usedOutputTokens)
        assertEquals(saved.notes, continued.notes)
        assertEquals(saved.reviewLedger.reviewedBlocksByDocument, continued.reviewLedger.reviewedBlocksByDocument)
        assertEquals(saved.reviewLedger.totalReadableBlocks, continued.reviewLedger.totalReadableBlocks)
        assertEquals(p.id, continued.previousTasks.single().preflight.id)
        repo.save(continued.copy(task = continued.task!!.pause()))
        val stale = plans.prepare("mine", collection.ref, NovexLearningPlanAction.CONTINUE, ::allowed, build)
        repo.save(repo.find(collection.ref)!!.copy(lastFailure = "进度已经重新核对"))
        assertThrows(IllegalArgumentException::class.java) { plans.commit("mine", collection.ref, stale.id, ::allowed, build) {} }
        assertEquals(NovexLearningTaskStatus.PAUSED, repo.find(collection.ref)?.task?.status)
    }
}
