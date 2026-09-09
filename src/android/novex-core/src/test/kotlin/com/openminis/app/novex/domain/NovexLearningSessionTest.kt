package com.openminis.app.novex.domain

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NovexLearningSessionTest {
    private fun initial(): NovexLearningState {
        val source = NovexResourceRef("novex://sources/a")
        val ref = NovexResourceRef("novex://source-collections/a")
        val collection = NovexSourceCollection(ref, NovexResourceRef("novex://conversation-branches/a"), "资料",
            listOf(NovexCollectionSource(source, "正文", "a".repeat(64), NovexSourceStatus.READY,
                NovexResourceRef("novex://documents/a"), listOf("block_a"))), 0, 0)
        val state = NovexLearningState(collection, NovexReviewLedger.start(collection))
        val p = NovexLearningPreflight.prepare(NovexLearningPreflightRequest(ref,
            listOf(NovexLearningSourceEstimate(source, 90_000)), "model", effectiveContextTokens = 200_000,
            occupiedContextTokens = 0, directReadBudgetTokens = 12_000,
            proposedBudget = NovexLearningTokenBudget(180_000, 24_000)), state)
        return state.copy(preflight = p, task = NovexLearningCoordinator().start(p,
            NovexLearningConfirmation(p.id, p.modelId, p.sourceRefs, 180_000, 24_000, 1)))
    }

    @Test fun simultaneousStartPauseReplayAndExplicitResumeKeepOnePaidRun() = runBlocking {
        val root = Files.createTempDirectory("learning-session").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = FileNovexLearningRepository(root)
            val initial = initial()
            val ref = initial.collection.ref
            val id = initial.task!!.preflightId
            var visible = listOf(ref)
            val controller = NovexLearningSession(owner, repo, { visible }, {})
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            fun commit(): NovexLearningExecutionPlans.Commit {
                val prior = repo.find(ref)
                if (prior == null) repo.save(initial)
                return NovexLearningExecutionPlans.Commit(prior ?: initial, prior != null)
            }
            val execute: suspend (NovexLearningState) -> Unit = {
                assertNotNull(repo.find(ref)) // Always saved before execution starts.
                calls.incrementAndGet()
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            awaitAll(async { controller.start(ref, id, false, ::commit, {}, execute) },
                async { controller.start(ref, id, false, ::commit, {}, execute) })
            withTimeout(5_000) { entered.await() }
            assertEquals(1, calls.get())
            visible = emptyList() // Revocation must not prevent cancelling an owned request.
            controller.stop(ref, false)
            visible = listOf(ref)
            assertTrue(cancelled.isCompleted)
            assertFalse(controller.isRunning)
            assertEquals(NovexLearningTaskStatus.PAUSED, repo.find(ref)!!.task!!.status)
            val replay = controller.start(ref, id, true, ::commit, {}, execute)
            assertEquals("learning.incomplete", replay.code)
            assertEquals(1, calls.get())
            try {
                controller.resume(ref, { error("model changed") }, execute)
                fail("A changed model must not restart a paid plan")
            } catch (_: IllegalStateException) { }
            assertEquals(NovexLearningTaskStatus.PAUSED, repo.find(ref)!!.task!!.status)
            val finished = CompletableDeferred<Unit>()
            controller.resume(ref, {}, { state ->
                calls.incrementAndGet()
                repo.save(state.copy(task = state.task!!.finish(NovexLearningTaskStatus.COMPLETE)))
                finished.complete(Unit)
            })
            withTimeout(5_000) { finished.await() }
            assertEquals(2, calls.get())
            assertEquals("learning.completed", controller.start(ref, id, true, ::commit, {}, execute).code)
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join(); root.deleteRecursively() }
    }

    @Test fun aNewPlanStartsWhileThePreviousPlanIsFinishingItsArtifactPublication() = runBlocking {
        val root = Files.createTempDirectory("learning-session-replan").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = FileNovexLearningRepository(root)
            val initial = initial()
            val ref = initial.collection.ref
            val controller = NovexLearningSession(owner, repo, { listOf(ref) }, {})
            val completedButPublishing = CompletableDeferred<Unit>()
            val oldPublicationStopped = CompletableDeferred<Unit>()
            controller.start(ref, initial.task!!.preflightId, false,
                { repo.save(initial); NovexLearningExecutionPlans.Commit(initial, false) }, {}, { state ->
                    repo.save(state.copy(task = state.task!!.finish(NovexLearningTaskStatus.COMPLETE)))
                    completedButPublishing.complete(Unit)
                    try { awaitCancellation() } finally { oldPublicationStopped.complete(Unit) }
                })
            withTimeout(5_000) { completedButPublishing.await() }
            val p = initial.preflight!!.copy(id = "new-plan")
            val next = initial.copy(preflight = p, task = NovexLearningCoordinator().start(p,
                NovexLearningConfirmation(p.id, p.modelId, p.sourceRefs, 180_000, 24_000, 2)))
            val nextStarted = CompletableDeferred<Unit>()
            controller.start(ref, p.id, false,
                { repo.save(next); NovexLearningExecutionPlans.Commit(next, false) }, {}, {
                    nextStarted.complete(Unit)
                    awaitCancellation()
                })
            withTimeout(5_000) { nextStarted.await() }
            assertTrue(oldPublicationStopped.isCompleted)
            assertEquals(p.id, repo.find(ref)!!.task!!.preflightId)
            controller.pauseActive()
            assertEquals(p.id, repo.find(ref)!!.task!!.preflightId)
            assertEquals(NovexLearningTaskStatus.PAUSED, repo.find(ref)!!.task!!.status)
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join(); root.deleteRecursively() }
    }

    @Test fun reopeningPausesAnOrphanAndRevokedSourcesCannotStart() = runBlocking {
        val root = Files.createTempDirectory("learning-session-restore").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val initial = initial()
            val ref = initial.collection.ref
            val repo = FileNovexLearningRepository(root)
            repo.save(initial)
            var visible = listOf(ref)
            val controller = NovexLearningSession(owner, FileNovexLearningRepository(root), { visible }, {})
            assertEquals(NovexLearningTaskStatus.PAUSED, controller.restore()!!.task!!.status)
            assertEquals(NovexLearningTaskStatus.PAUSED, FileNovexLearningRepository(root).find(ref)!!.task!!.status)
            assertFalse(controller.isRunning)
            visible = emptyList()
            try {
                controller.start(ref, initial.task!!.preflightId, false,
                    { error("Must not commit after source removal") }, {}, { error("Must not execute") })
                fail("Revoked sources cannot start")
            } catch (_: IllegalArgumentException) { }
            assertNull(controller.restore())
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join(); root.deleteRecursively() }
    }
}
