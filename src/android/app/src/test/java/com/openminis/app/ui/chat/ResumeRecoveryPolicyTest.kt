package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeRecoveryPolicyTest {
    @Test
    fun `a fresh interrupted run offers one explicit continuation`() {
        assertTrue(shouldOfferResumeAfterFailure(AgentRunRecoveryOrigin.FRESH))
    }

    @Test
    fun `an interrupted continuation does not create an endless resume loop`() {
        assertFalse(shouldOfferResumeAfterFailure(AgentRunRecoveryOrigin.RESUME))
    }

    @Test
    fun `retry consumes stale resume eligibility before starting`() {
        assertFalse(resumeEligibilityAfterRecoveryAction(RecoveryAction.RETRY))
        assertFalse(resumeEligibilityAfterRecoveryAction(RecoveryAction.RESUME))
    }
}
