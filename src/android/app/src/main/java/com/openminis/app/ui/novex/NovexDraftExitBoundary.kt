package com.openminis.app.ui.novex

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.openminis.app.ui.navigation.NovexEditorBackAction
import com.openminis.app.ui.navigation.novexEditorBackAction

/** Toolbar and system back use one decision; saving and failure remain owned by the form. */
@Composable
internal fun <Draft> NovexDraftExitBoundary(
    baselineDraft: Draft?,
    currentDraft: Draft,
    saving: Boolean,
    onBack: () -> Unit,
    onSaveAndExit: () -> Unit,
    content: @Composable (requestBack: () -> Unit) -> Unit,
) {
    var showExitPrompt by rememberSaveable { mutableStateOf(false) }
    val requestBack: () -> Unit = {
        if (!saving) {
            when (novexEditorBackAction(false, baselineDraft, currentDraft)) {
                NovexEditorBackAction.PROMPT_SAVE -> showExitPrompt = true
                NovexEditorBackAction.LEAVE_EDITOR -> onBack()
                NovexEditorBackAction.CLOSE_PREVIEW -> Unit
            }
        }
    }
    BackHandler(onBack = requestBack)
    content(requestBack)
    if (showExitPrompt) {
        NovexUnsavedChangesDialog(
            saving = saving,
            onSaveAndExit = onSaveAndExit,
            onDiscard = { showExitPrompt = false; onBack() },
            onContinueEditing = { showExitPrompt = false },
        )
    }
}
