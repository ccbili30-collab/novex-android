package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchGraphTest {
    @Test
    fun `retry keeps the old reply and creates a sibling reply branch`() {
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a1", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a2", 2),
                node("a2", "u2", null, 3),
            ),
            activeRootId = "u1",
            activeLeafId = "a2",
        )

        val fork = graph.forkReplyFrom("u1")

        assertTrue(fork.deletedMessageIds.isEmpty())
        assertEquals("u1", fork.newMessageParentId)
        assertEquals(listOf("u1"), fork.activePathIds)

        val afterReply = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a3", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a2", 2),
                node("a2", "u2", null, 3),
                node("a3", "u1", null, 4),
            ),
            activeRootId = "u1",
            activeLeafId = "a3",
        )

        assertEquals(ConversationBranchGraph.SiblingPosition(2, 2), afterReply.siblingPosition("a3"))
        assertEquals(listOf("u1", "a3"), afterReply.activePathIds)

        val oldReply = afterReply.switchSibling("a3", -1)
        assertEquals(listOf("u1", "a1", "u2", "a2"), oldReply.activePathIds)
        assertEquals("a2", oldReply.activeLeafId)
    }

    @Test
    fun `editing creates a sibling user branch without deleting the original timeline`() {
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a1", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a2", 2),
                node("a2", "u2", null, 3),
            ),
            activeRootId = "u1",
            activeLeafId = "a2",
        )

        val fork = graph.forkEditedMessageFrom("u2")

        assertTrue(fork.deletedMessageIds.isEmpty())
        assertEquals("a1", fork.newMessageParentId)
        assertEquals(listOf("u1", "a1"), fork.activePathIds)
    }

    @Test
    fun `active path includes tool results and selects only summaries and memories anchored on that path`() {
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a1", 0),
                node("a1", "u1", "tr1", 1),
                node("tr1", "a1", "a2", 2),
                node("a2", "tr1", null, 3),
                node("a-old", "u1", null, 4),
            ),
            activeRootId = "u1",
            activeLeafId = "a2",
        )

        assertEquals(listOf("u1", "a1", "tr1", "a2"), graph.activePathIds)
        assertTrue(graph.containsOnActivePath("tr1"))
        assertTrue(graph.containsOnActivePath("a2"))
        assertEquals(false, graph.containsOnActivePath("a-old"))
    }

    @Test
    fun `deleting the active branch falls back to the adjacent sibling and preserves its descendants`() {
        val graph = ConversationBranchGraph.open(
            nodes = listOf(
                node("u1", null, "a2", 0),
                node("a1", "u1", "u2", 1),
                node("u2", "a1", "a3", 2),
                node("a3", "u2", null, 3),
                node("a2", "u1", "u3", 4),
                node("u3", "a2", null, 5),
            ),
            activeRootId = "u1",
            activeLeafId = "u3",
        )

        val deletion = graph.deleteBranchFrom("a2")

        assertEquals(setOf("a2", "u3"), deletion.deletedMessageIds)
        assertEquals(listOf("u1", "a1", "u2", "a3"), deletion.activePathIds)
        assertEquals("a3", deletion.activeLeafId)
    }

    private fun node(
        id: String,
        parentId: String?,
        activeChildId: String?,
        order: Int,
    ) = ConversationBranchGraph.Node(
        id = id,
        parentId = parentId,
        activeChildId = activeChildId,
        order = order,
    )
}
