package com.openminis.app.ui.chat

/** Where the agent run came from, used to keep recovery finite. */
internal enum class AgentRunRecoveryOrigin {
    FRESH,
    RESUME,
    RETRY,
}

/** Explicit recovery actions always consume the old resume eligibility. */
internal enum class RecoveryAction {
    RESUME,
    RETRY,
}

/**
 * A fresh run may offer one continuation. A continuation or retry that fails
 * again must stop on its inline error instead of recreating the same Resume
 * banner forever.
 */
internal fun shouldOfferResumeAfterFailure(origin: AgentRunRecoveryOrigin): Boolean =
    origin == AgentRunRecoveryOrigin.FRESH

internal fun resumeEligibilityAfterRecoveryAction(action: RecoveryAction): Boolean = when (action) {
    RecoveryAction.RESUME, RecoveryAction.RETRY -> false
}
