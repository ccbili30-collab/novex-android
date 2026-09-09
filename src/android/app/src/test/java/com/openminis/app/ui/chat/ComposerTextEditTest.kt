package com.openminis.app.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ComposerTextEditTest {
    private fun value(text: String) = TextFieldValue(text, TextRange(text.length))
    private fun edit(old: TextFieldValue, next: TextFieldValue, age: Long = 1000,
        send: Boolean = false, mention: Boolean = false) =
        interpretComposerTextEdit(old, next, age, send, mention)

    @Test fun lateImeCommitCannotRestoreSentTextButClearStillWorks() {
        assertEquals(ComposerTextEdit.Ignore, edit(value(""), value("刚发出的文字"), age = 299))
        assertEquals(ComposerTextEdit.Replace(value("")), edit(value("旧草稿"), value(""), age = 100))
        assertEquals(ComposerTextEdit.Replace(value("新文字")), edit(value(""), value("新文字"), age = 300))
    }

    @Test fun multilinePasteAndMentionEditingRemainTextEvenWithEnterSendingEnabled() {
        val pasted = value("第一段\n第二段\n第三段")
        assertEquals(ComposerTextEdit.Replace(pasted), edit(value(""), pasted, send = true))
        val mention = value("@资料\n")
        assertEquals(ComposerTextEdit.Replace(mention), edit(value("@资料"), mention, send = true, mention = true))
    }

    @Test fun softKeyboardEnterFollowsTheUserPreference() {
        val old = value("发送这句")
        val next = value("发送这句\n")
        assertEquals(ComposerTextEdit.Send, edit(old, next, send = true))
        assertEquals(ComposerTextEdit.Replace(next), edit(old, next))
    }

    @Test fun ordinarySelectionAndCompositionSurviveAnEdit() {
        val proposed = TextFieldValue("编辑拼音", TextRange(2), TextRange(2, 4))
        assertEquals(ComposerTextEdit.Replace(proposed), edit(value("编辑"), proposed))
    }

    @Test fun closingALeadingInstructionKeepsTheExistingLineSeparation() {
        assertEquals(ComposerTextEdit.Replace(value("【请记住】\n")), edit(value("【请记住"), value("【请记住】")))
        assertEquals(ComposerTextEdit.Replace(value("正文[备注]")), edit(value("正文[备注"), value("正文[备注]")))
        val pasted = value("[整段粘贴]")
        assertEquals(ComposerTextEdit.Replace(pasted), edit(value(""), pasted))
    }
}
