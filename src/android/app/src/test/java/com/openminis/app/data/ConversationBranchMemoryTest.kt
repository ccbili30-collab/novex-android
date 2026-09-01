package com.openminis.app.data

import com.openminis.app.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationBranchMemoryTest {
    @Test
    fun `only writes unique to inactive branches are excluded`() {
        val shared = "shared fact"
        val rows = listOf(
            row("active", memoryParts(shared, "active fact"), 0),
            row("inactive", memoryParts(shared, "inactive fact"), 1),
        )

        assertEquals(
            mapOf("inactive fact" to 1),
            ConversationBranchMemory.excludedWriteCounts(rows, setOf("active")),
        )
    }

    @Test
    fun `tool results and malformed rows do not create memory exclusions`() {
        val toolResult = """[{"type":"toolResult","value":{"toolUseId":"m1","output":"ignored"}}]"""
        val rows = listOf(row("result", toolResult, 0), row("bad", "not-json", 1))

        assertEquals(emptyMap<String, Int>(), ConversationBranchMemory.excludedWriteCounts(rows, emptySet()))
    }

    @Test
    fun `duplicate inactive writes retain their count while an active copy keeps the memory visible`() {
        val duplicateInactive = listOf(
            row("inactive-1", memoryParts("repeated"), 0),
            row("inactive-2", memoryParts("repeated"), 1),
        )
        assertEquals(
            mapOf("repeated" to 2),
            ConversationBranchMemory.excludedWriteCounts(duplicateInactive, emptySet()),
        )

        val withActiveCopy = duplicateInactive + row("active", memoryParts("repeated"), 2)
        assertEquals(
            emptyMap<String, Int>(),
            ConversationBranchMemory.excludedWriteCounts(withActiveCopy, setOf("active")),
        )
    }

    @Test
    fun `branch deletion revokes hidden descendants but keeps shared surviving writes`() {
        val deleted = listOf(
            row("deleted-parent", memoryParts("deleted only", "shared"), 0),
            row("deleted-hidden-child", memoryParts("hidden child"), 1),
        )
        val remaining = listOf(row("sibling", memoryParts("shared"), 2))

        assertEquals(
            listOf("deleted only", "hidden child"),
            ConversationBranchMemory.writesOwnedOnlyByDeletedMessages(deleted, remaining),
        )
    }

    private fun row(id: String, parts: String, order: Int) = MessageEntity(
        id = id,
        sessionId = "session",
        role = "assistant",
        partsJson = parts,
        createdAt = order.toLong(),
        sortOrder = order,
    )

    private fun memoryParts(vararg contents: String): String = contents.joinToString(
        prefix = "[",
        postfix = "]",
    ) { content ->
        val input = org.json.JSONObject().put("content", content).toString()
        org.json.JSONObject()
            .put("type", "toolUse")
            .put(
                "value",
                org.json.JSONObject()
                    .put("name", "memory_write")
                    .put("input", input),
            )
            .toString()
    }
}
