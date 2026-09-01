package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTimelineMutationTest {
    @Test
    fun `editing an earlier user message removes that turn and every later reply`() {
        val messages = listOf(
            message("u1", "user", "你让我很生气，离开我家"),
            message("a1", "assistant", "好的，我这就离开"),
            message("u2", "user", "关门"),
            message("a2", "assistant", "门已关上"),
        )

        val plan = ConversationTimelineMutation.inclusive(messages, "u1")!!

        assertEquals("u1", plan.cutoffDbMessageId)
        assertTrue(plan.retainedMessages.isEmpty())
        assertEquals(listOf("u1", "a1", "u2", "a2"), plan.deletedMessages.map { it.id })
    }

    @Test
    fun `merged assistant bubble deletes from its first persisted row`() {
        val merged = message("a2", "assistant", "连续回复").copy(sourceDbIds = listOf("a1", "a2"))

        val plan = ConversationTimelineMutation.inclusive(
            listOf(message("u1", "user", "你好"), merged),
            "a2",
        )!!

        assertEquals("a1", plan.cutoffDbMessageId)
        assertEquals(listOf("u1"), plan.retainedMessages.map { it.id })
    }

    @Test
    fun `rewinding compacted history invalidates the stale summary`() {
        val compacted = message("u1", "user", "旧指令").copy(isCompactedHistory = true)

        val plan = ConversationTimelineMutation.inclusive(
            listOf(compacted, message("a1", "assistant", "旧回复")),
            "u1",
        )!!

        assertTrue(plan.invalidateCompactMarkers)
    }

    private fun message(id: String, role: String, content: String) = ChatMessage(
        id = id,
        role = role,
        content = content,
        sourceDbIds = listOf(id),
    )
}
