package com.openminis.app.ui.chat

internal enum class EmptyResponseContext { INITIAL, AFTER_TOOL_RESULT }

internal enum class AgentTurnCompletionAction {
    COMPLETE,
    EXECUTE_TOOLS,
    INTERRUPTED,
    RETRY_EMPTY,
    FAIL_EMPTY,
}

/** Completion policy shared by the real agent loop and its regression tests. */
internal fun decideAgentTurnCompletion(
    hasVisibleContent: Boolean,
    hasToolCalls: Boolean,
    finishReason: String?,
    emptyContext: EmptyResponseContext,
    retryAlreadyUsed: Boolean,
): AgentTurnCompletionAction {
    if (finishReason == null) return AgentTurnCompletionAction.INTERRUPTED
    if (hasToolCalls) return AgentTurnCompletionAction.EXECUTE_TOOLS
    if (hasVisibleContent) return AgentTurnCompletionAction.COMPLETE
    val retryableEmptyStop = finishReason == "stop" || finishReason == "end_turn"
    if (retryableEmptyStop && !retryAlreadyUsed) {
        return AgentTurnCompletionAction.RETRY_EMPTY
    }
    return AgentTurnCompletionAction.FAIL_EMPTY
}

/** Maintains independent one-shot retry budgets for the two empty contexts. */
internal class EmptyResponseRetryState {
    private var initialRetryUsed = false
    private var afterToolRetryUsed = false

    fun decide(
        hasVisibleContent: Boolean,
        hasToolCalls: Boolean,
        finishReason: String?,
        context: EmptyResponseContext,
    ): AgentTurnCompletionAction {
        val retryUsed = when (context) {
            EmptyResponseContext.INITIAL -> initialRetryUsed
            EmptyResponseContext.AFTER_TOOL_RESULT -> afterToolRetryUsed
        }
        val action = decideAgentTurnCompletion(
            hasVisibleContent = hasVisibleContent,
            hasToolCalls = hasToolCalls,
            finishReason = finishReason,
            emptyContext = context,
            retryAlreadyUsed = retryUsed,
        )
        if (action == AgentTurnCompletionAction.RETRY_EMPTY) {
            when (context) {
                EmptyResponseContext.INITIAL -> initialRetryUsed = true
                EmptyResponseContext.AFTER_TOOL_RESULT -> afterToolRetryUsed = true
            }
        }
        return action
    }
}

internal enum class MissingChoiceToolRecoveryAction {
    NONE,
    RETRY_PRESENT_CHOICES,
    FAIL_AFTER_RETRY,
}

/**
 * One-shot recovery for relays that occasionally turn an explicit request for
 * native choice buttons into prose. This is deliberately narrower than the
 * ordinary choice fallback: normal storytelling is never forced into a tool.
 */
internal object MissingChoiceToolRecoveryPolicy {
    private val explicitChoiceRequestPatterns = listOf(
        Regex("(选项|选择).{0,8}(工具|按钮|菜单)"),
        Regex("(给|提供|列出|展示|生成).{0,8}(选项|选择)"),
        Regex("(选项|选择).{0,8}(给我|提供|列出|展示|生成)"),
    )

    fun decide(
        userRequest: String,
        assistantText: String,
        hasAnyToolCall: Boolean,
        hasPresentChoicesCall: Boolean,
        finishReason: String?,
        presentChoicesAvailable: Boolean,
        forcedAttempt: Boolean,
    ): MissingChoiceToolRecoveryAction {
        if (hasPresentChoicesCall) return MissingChoiceToolRecoveryAction.NONE
        if (forcedAttempt) return MissingChoiceToolRecoveryAction.FAIL_AFTER_RETRY
        if (!presentChoicesAvailable || finishReason == null || hasAnyToolCall) {
            return MissingChoiceToolRecoveryAction.NONE
        }
        if (explicitChoiceRequestPatterns.none { it.containsMatchIn(userRequest) }) {
            return MissingChoiceToolRecoveryAction.NONE
        }
        if (NovexChoiceFallback.extract(assistantText).size >= 2) {
            return MissingChoiceToolRecoveryAction.NONE
        }
        return MissingChoiceToolRecoveryAction.RETRY_PRESENT_CHOICES
    }
}
