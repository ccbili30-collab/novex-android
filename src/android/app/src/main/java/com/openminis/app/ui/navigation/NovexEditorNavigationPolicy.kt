package com.openminis.app.ui.navigation

internal enum class NovexEditorBackAction {
    CLOSE_PREVIEW,
    LEAVE_EDITOR,
}

internal fun novexEditorBackAction(previewVisible: Boolean): NovexEditorBackAction =
    if (previewVisible) NovexEditorBackAction.CLOSE_PREVIEW else NovexEditorBackAction.LEAVE_EDITOR

internal data class NovexSavedDetailNavigationPlan(
    val replaceCurrentEditor: Boolean,
    val launchSingleTop: Boolean,
)

internal fun novexSavedDetailNavigationPlan(): NovexSavedDetailNavigationPlan =
    NovexSavedDetailNavigationPlan(
        replaceCurrentEditor = true,
        launchSingleTop = true,
    )
