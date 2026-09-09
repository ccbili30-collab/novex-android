package com.openminis.app.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Text editing policy only. The host retains selection correction consent, storage and sending. */
internal sealed interface ComposerTextEdit {
    data object Ignore : ComposerTextEdit
    data object Send : ComposerTextEdit
    data class Replace(val value: TextFieldValue) : ComposerTextEdit
}

internal fun interpretComposerTextEdit(
    previous: TextFieldValue,
    proposed: TextFieldValue,
    millisecondsSinceSend: Long,
    sendOnEnter: Boolean,
    mentionMenuOpen: Boolean,
): ComposerTextEdit {
    // Late IME commits must not restore the text just sent; clearing remains allowed.
    if (millisecondsSinceSend < 300L && proposed.text.isNotEmpty()) return ComposerTextEdit.Ignore
    val text = proposed.text
    if (sendOnEnter && !mentionMenuOpen && text.length == previous.text.length + 1 &&
        text.count { it == '\n' } == previous.text.count { it == '\n' } + 1) {
        val caret = proposed.selection.end
        if (caret in 1..text.length && text[caret - 1] == '\n') return ComposerTextEdit.Send
    }
    val closesInstruction = text.startsWith("【") && text.endsWith("】") ||
        text.startsWith("[") && text.endsWith("]")
    val value = if (text.length == previous.text.length + 1 && closesInstruction)
        proposed.copy(text = text + "\n", selection = TextRange(text.length + 1)) else proposed
    return ComposerTextEdit.Replace(value)
}
