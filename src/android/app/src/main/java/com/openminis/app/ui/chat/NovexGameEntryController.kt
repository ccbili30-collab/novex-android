package com.openminis.app.ui.chat

import com.openminis.app.novex.domain.ActiveInteractiveFictionSnapshot
import com.openminis.app.novex.domain.ConversationPlayerIdentity
import com.openminis.app.novex.domain.NovexGamePlayerChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

sealed interface NovexGameEntryState {
    data object Ready : NovexGameEntryState
    data object Preparing : NovexGameEntryState
    data class ChoosePlayer(val game: ActiveInteractiveFictionSnapshot) : NovexGameEntryState
    data class Failed(val message: String) : NovexGameEntryState
}

/** An entry remains pending until its frozen game and explicit player choice are saved. */
class NovexGameEntryController(
    requested: Boolean,
    private val prepare: suspend () -> ActiveInteractiveFictionSnapshot,
    private val currentPlayer: () -> ConversationPlayerIdentity?,
    private val activate: suspend (ActiveInteractiveFictionSnapshot) -> Unit,
) {
    private val mutableState = MutableStateFlow<NovexGameEntryState>(
        if (requested) NovexGameEntryState.Preparing else NovexGameEntryState.Ready)
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    suspend fun start() = runEntry {
        var game = prepare()
        val current = currentPlayer()
        val conflicting = current != null && game.playerIdentity != null && current != game.playerIdentity
        if (NovexGamePlayerChoices.needsSelection(game) || conflicting) {
            if (current != null) game = NovexGamePlayerChoices.prepare(game,
                NovexGamePlayerChoices.read(game) + current)
            mutableState.value = NovexGameEntryState.ChoosePlayer(game)
        } else commit(game)
    }

    suspend fun select(id: String) {
        val pending = state.value as? NovexGameEntryState.ChoosePlayer ?: return
        runEntry { commit(NovexGamePlayerChoices.select(pending.game, id)) }
    }

    private suspend fun commit(game: ActiveInteractiveFictionSnapshot) {
        activate(game)
        mutableState.value = NovexGameEntryState.Ready
    }

    private suspend fun runEntry(work: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        try {
            mutableState.value = NovexGameEntryState.Preparing
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = NovexGameEntryState.Failed(failure.message ?: "读取或保存文游失败")
        } finally {
            mutex.unlock()
        }
    }
}
