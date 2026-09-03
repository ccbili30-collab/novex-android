package com.openminis.app.ui.navigation

internal enum class NovexEditorBackAction {
    CLOSE_PREVIEW,
    PROMPT_SAVE,
    LEAVE_EDITOR,
}

internal fun novexEditorBackAction(
    previewVisible: Boolean,
    hasUnsavedChanges: Boolean = false,
): NovexEditorBackAction = when {
    previewVisible -> NovexEditorBackAction.CLOSE_PREVIEW
    hasUnsavedChanges -> NovexEditorBackAction.PROMPT_SAVE
    else -> NovexEditorBackAction.LEAVE_EDITOR
}

/**
 * Editor-facing seam: callers provide the loaded baseline and current draft,
 * so an actual field change cannot be hidden behind an untested Boolean.
 */
internal fun <Draft> novexEditorBackAction(
    previewVisible: Boolean,
    baselineDraft: Draft?,
    currentDraft: Draft,
): NovexEditorBackAction = novexEditorBackAction(
    previewVisible = previewVisible,
    hasUnsavedChanges = baselineDraft != null && currentDraft != baselineDraft,
)

internal data class NovexSavedDetailNavigationPlan(
    val replaceCurrentEditor: Boolean,
    val launchSingleTop: Boolean,
)

internal fun novexSavedDetailNavigationPlan(): NovexSavedDetailNavigationPlan =
    NovexSavedDetailNavigationPlan(
        replaceCurrentEditor = true,
        launchSingleTop = true,
    )
