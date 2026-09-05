package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
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
    fun `terminal and budget stopped states can be acknowledged`() {
        listOf(
            NovexLearningTaskStatus.PAUSED_BUDGET_REACHED,
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
}
