package com.openminis.app.data

import com.openminis.app.data.db.MessageEntity
import org.json.JSONArray

/** Resolves memory writes that belong only to inactive conversation branches. */
object ConversationBranchMemory {
    fun excludedWriteCounts(
        allMessages: List<MessageEntity>,
        activeMessageIds: Set<String>,
    ): Map<String, Int> {
        val active = linkedSetOf<String>()
        val inactive = linkedMapOf<String, Int>()
        allMessages.forEach { message ->
            val contents = memoryWriteContents(message.partsJson)
            if (message.id in activeMessageIds) {
                active += contents
            } else {
                contents.forEach { content ->
                    inactive[content] = inactive.getOrDefault(content, 0) + 1
                }
            }
        }
        return inactive.filterKeys { it !in active }
    }

    fun writesOwnedOnlyByDeletedMessages(
        deletedMessages: List<MessageEntity>,
        remainingMessages: List<MessageEntity>,
    ): List<String> {
        val survivingContents = remainingMessages.flatMapTo(hashSetOf()) {
            memoryWriteContents(it.partsJson)
        }
        return deletedMessages.flatMap { memoryWriteContents(it.partsJson) }
            .filterNot { it in survivingContents }
    }

    internal fun memoryWriteContents(partsJson: String): List<String> = runCatching {
        val result = mutableListOf<String>()
        val parts = JSONArray(partsJson)
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            if (part.optString("type") !in setOf("toolUse", "uiToolUse")) continue
            val value = part.optJSONObject("value") ?: continue
            if (value.optString("name") != "memory_write") continue
            val input = value.optString("input", "")
            val content = org.json.JSONObject(input).optString("content", "").trim()
            if (content.isNotEmpty()) result += content
        }
        result
    }.getOrDefault(emptyList())
}
