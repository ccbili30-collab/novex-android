package com.openminis.app.ui.chat

/**
 * Distinguishes delayed StateFlow echoes of local composer edits from genuine
 * external writes (slash actions, voice input, send/clear, move-to, etc.).
 *
 * Compose can deliver several IME edits before recomposing the collector. An
 * older collected String must never be applied back to TextFieldValue: doing so
 * restores deleted text and rebuilds the selection at the end of the field.
 */
internal class ComposerInputSynchronizer(
    private val maxPendingEdits: Int = 64,
) {
    private val pendingLocalEdits = ArrayDeque<String>()

    fun recordLocalEdit(text: String) {
        pendingLocalEdits.addLast(text)
        while (pendingLocalEdits.size > maxPendingEdits) pendingLocalEdits.removeFirst()
    }

    /** Returns true only when [externalText] is an authoritative external write. */
    fun shouldApplyExternal(externalText: String): Boolean {
        val echoIndex = pendingLocalEdits.indexOfLast { it == externalText }
        if (echoIndex >= 0) {
            repeat(echoIndex + 1) { pendingLocalEdits.removeFirst() }
            return false
        }
        pendingLocalEdits.clear()
        return true
    }
}
