package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexLearningControlPolicyTest {
    @Test
    fun `executing work can pause or cancel but cannot silently dismiss`() {
        assertEquals(
            setOf(NovexLearningControl.PAUSE, NovexLearningControl.CANCEL),
            NovexLearningControlPolicy.allowedControls(NovexLearningTaskStatus.REVIEWING),
        )
    }

    @Test
    fun `paused work can resume or cancel`() {
        assertEquals(
            setOf(NovexLearningControl.RESUME, NovexLearningControl.CANCEL),
            NovexLearningControlPolicy.allowedControls(NovexLearningTaskStatus.PAUSED),
        )
    }

    @Test
    fun `budget stopped work can request a larger confirmation or end`() {
        assertEquals(
            setOf(
                NovexLearningControl.EXTEND_BUDGET,
                NovexLearningControl.CANCEL,
                NovexLearningControl.DISMISS,
            ),
            NovexLearningControlPolicy.allowedControls(
                NovexLearningTaskStatus.PAUSED_BUDGET_REACHED,
            ),
        )
    }

    @Test
    fun `terminal states can be acknowledged`() {
        listOf(
            NovexLearningTaskStatus.CANCELLED,
            NovexLearningTaskStatus.PARTIAL_FAILURE,
            NovexLearningTaskStatus.COMPLETE,
        ).forEach { status ->
            assertEquals(
                setOf(NovexLearningControl.DISMISS),
                NovexLearningControlPolicy.allowedControls(status),
            )
        }
    }

    @Test
    fun `only executing or manually paused work blocks a replacement preflight`() {
        listOf(
            NovexLearningTaskStatus.INDEXING,
            NovexLearningTaskStatus.REVIEWING,
            NovexLearningTaskStatus.SYNTHESIZING,
            NovexLearningTaskStatus.PAUSED,
        ).forEach { status ->
            assertTrue(NovexLearningControlPolicy.blocksReplacementPreflight(status))
        }
        listOf(
            NovexLearningTaskStatus.NOT_STARTED,
            NovexLearningTaskStatus.PAUSED_BUDGET_REACHED,
            NovexLearningTaskStatus.CANCELLED,
            NovexLearningTaskStatus.PARTIAL_FAILURE,
            NovexLearningTaskStatus.COMPLETE,
        ).forEach { status ->
            assertFalse(NovexLearningControlPolicy.blocksReplacementPreflight(status))
        }
    }
}
