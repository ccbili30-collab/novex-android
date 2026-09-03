package com.openminis.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexEditorNavigationPolicyTest {
    @Test
    fun systemBackFromDraftPreviewReturnsToTheEditorBeforeLeavingTheRoute() {
        assertEquals(
            NovexEditorBackAction.CLOSE_PREVIEW,
            novexEditorBackAction(previewVisible = true),
        )
        assertEquals(
            NovexEditorBackAction.LEAVE_EDITOR,
            novexEditorBackAction(previewVisible = false),
        )
    }

    @Test
    fun saveReplacesTheCurrentEditorAndDoesNotStackAnotherDetailPage() {
        val plan = novexSavedDetailNavigationPlan()

        assertTrue(plan.replaceCurrentEditor)
        assertTrue(plan.launchSingleTop)
    }
}
