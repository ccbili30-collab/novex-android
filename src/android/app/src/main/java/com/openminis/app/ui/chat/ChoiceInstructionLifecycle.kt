package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * Choice-tool instructions must have an explicit lifetime. Both constants here
 * exist because a bare instruction with no expiry poisoned a real session
 * (2026-09-17, session 297e156a): the recovery hint "respond only with
 * present_choices, do not write prose" was appended permanently to a user
 * message in agentHistory, and every later turn kept obeying it — the model
 * looped on choice-only replies until the user gave up.
 */
internal object ChoiceInstructionLifecycle {

    /**
     * One-shot recovery hint for relays that flatten an explicit native-button
     * request into prose. The host applies it to the request copy of exactly
     * the forced retry turn (every attempt of that one logical request), then
     * it expires. It is never written into agentHistory or the DB.
     */
    const val FORCED_CHOICE_RECOVERY_HINT =
        "<system-reminder>The user explicitly requested native choice buttons. " +
            "For this one reply only: respond with a single present_choices tool call containing " +
            "2 to 12 concise choices, and no prose. This instruction applies to this single reply " +
            "and expires immediately after it.</system-reminder>"

    /**
     * Context marker attached (and persisted) to the user message a player
     * sends right after a choices card. present_choices is a terminal UI tool:
     * the card itself is stripped from provider-facing history, so without
     * this marker the model cannot tell a selection apart from a fresh
     * meta-instruction like "选项工具" — the second half of the same loop.
     * Rides the existing system-reminder rails: DB keeps the raw text, the
     * model sees it on later turns, the UI strips it.
     */
    const val SELECTION_RESPONSE_REMINDER =
        "<system-reminder>用户正在回应上一轮的选项卡片：这条消息可能是点选了其中某个选项，" +
            "也可能是自由输入的行动。请把它当作对剧情的推进来写正文；不要原样重复上一轮的选项。</system-reminder>"

    /**
     * Appends [hint] as a Text part on the LAST user message of the request
     * copy. Returns the input list unchanged when [hint] is null or no user
     * message exists. Pure: the input list and its messages are never mutated,
     * so agentHistory stays clean — the hint lives only in the returned copy
     * for this one request.
     */
    fun appendForcedChoiceHint(messages: List<LLMMessage>, hint: String?): List<LLMMessage> {
        if (hint == null || messages.isEmpty()) return messages
        val index = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        if (index < 0) return messages
        return messages.toMutableList().also { list ->
            val target = list[index]
            list[index] = target.copy(contentParts = target.contentParts + AgentContentPart.Text(hint))
        }
    }

    /**
     * True when the last visible message is an assistant turn that contains a
     * present_choices card — i.e. the choices are live and the player's next
     * send answers them. Works both live (toolBlocks come from the streamed
     * turn) and after reload (toChatMessages restores uiToolUse blocks).
     */
    fun endsWithLiveChoicesCard(lastRole: String?, lastToolNames: List<String>): Boolean =
        lastRole == "assistant" && lastToolNames.any { it == "present_choices" }
}
