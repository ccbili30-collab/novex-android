package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** One compaction policy for general, creative, role, world, and technical conversations. */
internal object ConversationCompactionPolicy {
    val systemPrompt: String = """
        You are a conversation continuity compaction engine. One conversation may combine general assistance, creative writing, roleplay, world building, character or player identity, and technical work. Summarize only the supplied conversation and write in the language mainly used by the user.

        The application may inject source materials such as a world, character, player identity, rules, or a conversation prompt separately. Do not reproduce those injected materials wholesale. Preserve conversation-established changes, interpretations, exceptions, and current state that are needed to continue consistently.

        MUST PRESERVE WHEN RELEVANT:
        - The latest user intent and latest user corrections; newer instructions supersede conflicting older ones
        - Facts, decisions, constraints, preferences, promises, outcomes, and tool effects
        - People and entities, relationships, time, place, scene state, important events, plot threads, and exact dialogue whose wording matters
        - Unresolved requests, questions, conflicts, and commitments, clearly marked as unresolved without inventing progress
        - Exact file paths, directory names, commands, URLs, identifiers, code, error messages, and technical results

        Produce a compact continuity checkpoint, not a continuation and not a new answer. Describe completed events in past tense and current state in present tense. Do not revive cancelled, superseded, or already completed work as pending. Prefer recent context when details conflict. Omit irrelevant or empty categories. Never invent missing facts.
    """.trimIndent()

    fun transcript(messages: List<LLMMessage>): String = buildString {
        messages.forEach { message ->
            val role = message.role.name.lowercase()
            if (message.content.isNotEmpty()) {
                append(role).append(": ").append(message.content).append('\n')
            }
            message.imageParts.forEach { image ->
                append(role).append(" [image: ").append(image.mimeType)
                image.linuxPath?.let { append(", path=").append(it) }
                append("]\n")
            }
            message.audioParts.forEach { audio ->
                append(role).append(" [audio: ").append(audio.format).append("]\n")
            }
            message.contentParts.forEach { part ->
                when (part) {
                    is AgentContentPart.Text ->
                        append(role).append(": ").append(part.text).append('\n')
                    is AgentContentPart.ToolUse ->
                        append(role).append(" [tool:").append(part.name).append("]: ")
                            .append(part.input.toString()).append('\n')
                    is AgentContentPart.ToolResult -> {
                        append(role).append(" [result:").append(part.name)
                        if (part.isError) append(", error")
                        append("]: ").append(part.content)
                        part.imageLinuxPath?.let { append(" [image path=").append(it).append(']') }
                        append('\n')
                    }
                    is AgentContentPart.ImageData -> {
                        append(role).append(" [image: ").append(part.mimeType)
                        part.linuxPath?.let { append(", path=").append(it) }
                        append("]\n")
                    }
                }
            }
        }
    }

    /**
     * Chooses the user turn boundary nearest the middle. A user message that
     * carries ToolResult parts belongs to the preceding assistant turn and is
     * therefore never treated as a safe split point.
     */
    fun splitBetweenTurns(
        messages: List<LLMMessage>,
    ): Pair<List<LLMMessage>, List<LLMMessage>>? {
        if (messages.size < 2) return null
        val midpoint = messages.size / 2
        val candidates = messages.indices.filter { index ->
            index > 0 && index < messages.size &&
                messages[index].role == LLMMessage.Role.USER &&
                messages[index].contentParts.none { it is AgentContentPart.ToolResult }
        }
        val splitIndex = candidates.minByOrNull { kotlin.math.abs(it - midpoint) }
            ?: (1 until messages.size)
                .filter { index -> !separatesToolExchange(messages, index) }
                .minByOrNull { kotlin.math.abs(it - midpoint) }
            ?: return null
        return messages.take(splitIndex) to messages.drop(splitIndex)
    }

    private fun separatesToolExchange(messages: List<LLMMessage>, splitIndex: Int): Boolean {
        val leftToolUses = messages.take(splitIndex)
            .flatMap(LLMMessage::contentParts)
            .filterIsInstance<AgentContentPart.ToolUse>()
            .mapTo(mutableSetOf(), AgentContentPart.ToolUse::id)
        if (leftToolUses.isEmpty()) return false
        return messages.drop(splitIndex)
            .flatMap(LLMMessage::contentParts)
            .filterIsInstance<AgentContentPart.ToolResult>()
            .any { it.id in leftToolUses }
    }
}
