package com.openminis.app.ui.chat

import com.openminis.app.novex.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NovexGameEntryControllerTest {
    private val a = ConversationPlayerIdentity("a", "旅人", "本局旅人")
    private val b = ConversationPlayerIdentity("b", "记言人", "角色配套")
    private val game = ActiveInteractiveFictionSnapshot("game", "snapshot", "文游")

    @Test fun choicesStayPendingAndChosenFrozenGameIsCommittedOnce() = runTest {
        var reads = 0
        val saved = mutableListOf<ActiveInteractiveFictionSnapshot>()
        val controller = NovexGameEntryController(true,
            { reads++; NovexGamePlayerChoices.prepare(game, listOf(a, b)) }, { null }, { saved += it })
        controller.start()
        assertTrue(controller.state.value is NovexGameEntryState.ChoosePlayer)
        assertTrue(saved.isEmpty())
        controller.select("b")
        controller.select("b")
        assertEquals(1, reads)
        assertEquals(listOf(b), saved.map { it.playerIdentity })
        assertEquals(NovexGameEntryState.Ready, controller.state.value)
    }

    @Test fun preparationOrSaveFailureNeverOpensOrdinaryChatAndCanRetry() = runTest {
        var failRead = true
        var failSave = true
        var writes = 0
        val controller = NovexGameEntryController(true, { check(!failRead) { "卡片不可用" }; game }, { null },
            { check(!failSave) { "保存失败" }; writes++ })
        controller.start()
        assertEquals(NovexGameEntryState.Failed("卡片不可用"), controller.state.value)
        failRead = false
        controller.start()
        assertEquals(NovexGameEntryState.Failed("保存失败"), controller.state.value)
        assertEquals(0, writes)
        failSave = false
        controller.start()
        assertEquals(NovexGameEntryState.Ready, controller.state.value)
        assertEquals(1, writes)
    }

    @Test fun existingIdentityIsAnExplicitChoiceAndConcurrentRetryDoesNotDuplicateCommit() = runTest {
        val pendingSave = CompletableDeferred<Unit>()
        var writes = 0
        val controller = NovexGameEntryController(true, { game.copy(playerIdentity = a) }, { b },
            { writes++; pendingSave.await() })
        controller.start()
        val pending = controller.state.value as NovexGameEntryState.ChoosePlayer
        assertEquals(listOf(a, b), NovexGamePlayerChoices.read(pending.game))
        val job = launch { controller.select("b") }
        testScheduler.runCurrent()
        assertEquals(NovexGameEntryState.Preparing, controller.state.value)
        controller.start()
        assertEquals(1, writes)
        pendingSave.complete(Unit)
        job.join()
        assertEquals(NovexGameEntryState.Ready, controller.state.value)
    }
}
