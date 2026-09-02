package com.openminis.app.ui.chat

/**
 * Persisted tails that are genuinely incomplete and safe to resume.
 *
 * A synthetic continuation reminder is deliberately not included: once the
 * user tapped Continue it has been consumed. Treating that reminder as a new
 * interruption after reload resurrects the same recovery loop forever.
 */
internal fun isInterruptedAgentTail(
    role: String,
    partTypes: List<String>,
    @Suppress("UNUSED_PARAMETER") singleText: String? = null,
): Boolean = when (role.uppercase()) {
    "USER" -> partTypes.isNotEmpty() && partTypes.all { it == "toolResult" }
    "ASSISTANT" -> partTypes.any { it == "toolUse" }
    else -> false
}
