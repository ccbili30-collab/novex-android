package com.openminis.app.novex.domain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NovexLearningRunCompletionTest {
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

    @Test fun `calling agent waits for saved completion before receiving a result`() = runBlocking {
        var state = initial()
        val task = state.task!!
        val finish = CompletableDeferred<Unit>()
        val run = launch { finish.await(); state = state.copy(task = task.finish(NovexLearningTaskStatus.COMPLETE)) }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            NovexLearningRunCompletion.await(state.collection.ref, task.preflightId, run, { state }, { error("unexpected stop") })
        }
        yield()
        assertFalse(result.isCompleted)
        finish.complete(Unit)
        assertEquals("learning.completed", result.await().code)
        assertEquals("learning_read", result.await().nextActions.single().id)
    }

    @Test fun `paused budget cancelled and replaced tasks never claim full completion`() = runBlocking {
        val initial = initial()
        for (task in listOf(initial.task!!.pause(), initial.task!!.pauseForBudget(), initial.task!!.cancel())) {
            val result = NovexLearningRunCompletion.await(initial.collection.ref, task.preflightId, null,
                { initial.copy(task = task) }, {})
            assertFalse(result.ok)
            assertEquals("learning.incomplete", result.code)
        }
        assertEquals("learning.task_changed", NovexLearningRunCompletion.await(initial.collection.ref,
            "another-plan", null, { initial }, {}).code)
    }

    @Test fun `cancelling caller stops its run before cancellation returns and retains checkpoint`() = runBlocking {
        var state = initial()
        var stopped = false
        val run = launch { awaitCancellation() }
        val caller = launch(start = CoroutineStart.UNDISPATCHED) {
            NovexLearningRunCompletion.await(state.collection.ref, state.task!!.preflightId, run, { state }, {
                run.cancelAndJoin()
                state = state.copy(task = state.task!!.pause())
                stopped = true
            })
        }
        caller.cancelAndJoin()
        assertTrue(stopped)
        assertFalse(run.isActive)
        assertEquals(NovexLearningTaskStatus.PAUSED, state.task!!.status)
    }
}
