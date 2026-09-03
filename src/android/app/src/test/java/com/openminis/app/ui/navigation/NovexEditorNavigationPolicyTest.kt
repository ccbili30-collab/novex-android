package com.openminis.app.ui.navigation

import com.openminis.app.ui.settings.CharacterEditorDraftState
import com.openminis.app.ui.settings.WorldEditorDraftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexEditorNavigationPolicyTest {
    @Test
    fun systemBackFromDraftPreviewReturnsToTheEditorBeforeLeavingTheRoute() {
        assertEquals(
            NovexEditorBackAction.CLOSE_PREVIEW,
            novexEditorBackAction(previewVisible = true, hasUnsavedChanges = true),
        )
        assertEquals(
            NovexEditorBackAction.LEAVE_EDITOR,
            novexEditorBackAction(previewVisible = false, hasUnsavedChanges = false),
        )
    }

    @Test
    fun leavingAnEditedDraftRequiresAnExplicitSaveOrDiscardDecision() {
        assertEquals(
            NovexEditorBackAction.PROMPT_SAVE,
            novexEditorBackAction(previewVisible = false, hasUnsavedChanges = true),
        )
    }

    @Test
    fun changingAWorldTitleIsObservedAsAnUnsavedEdit() {
        val baseline = WorldEditorDraftState.create(now = 1L, name = "原世界")
        val edited = baseline.copy(name = "新世界")

        assertEquals(
            NovexEditorBackAction.PROMPT_SAVE,
            novexEditorBackAction(
                previewVisible = false,
                baselineDraft = baseline,
                currentDraft = edited,
            ),
        )
    }

    @Test
    fun changingACharacterTitleIsObservedAsAnUnsavedEdit() {
        val baseline = CharacterEditorDraftState.create()
        val edited = baseline.copy(rootName = "苏晚晴", name = "苏晚晴")

        assertEquals(
            NovexEditorBackAction.PROMPT_SAVE,
            novexEditorBackAction(
                previewVisible = false,
                baselineDraft = baseline,
                currentDraft = edited,
            ),
        )
    }

    @Test
    fun saveReplacesTheCurrentEditorAndDoesNotStackAnotherDetailPage() {
        val plan = novexSavedDetailNavigationPlan()

        assertTrue(plan.replaceCurrentEditor)
        assertTrue(plan.launchSingleTop)
    }
}
