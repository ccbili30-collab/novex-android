package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexDistillationRecordTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun distillationPersistsAsBranchLocalDerivedDataWithDurableSources() {
        val root = temporaryFolder.newFolder("workspace")
        val store = FileNovexConversationWorkspaceStore(root) { 2_000L }
        val scope = NovexConversationWorkspaceScope(
            conversationId = "chat-1",
            visibleBranchIds = listOf("user-1"),
            writeBranchId = "assistant-1",
        )
        val record = NovexDistillationRecord(
            id = "distill-1",
            conversationId = "chat-1",
            branchId = "assistant-1",
            summary = "主角接受守山约定，下一步需要选择师承。",
            sourceMessageRefs = listOf(
                NovexResourceRef("novex://conversations/chat-1/messages/user-1"),
                NovexResourceRef("novex://conversations/chat-1/messages/assistant-1"),
            ),
            durableFactRefs = listOf(NovexResourceRef("novex://memories/role-1/entries/memory-1")),
            createdAtMillis = 1_234L,
        )

        val entry = NovexDistillationRecordWriter(store).save(
            scope,
            record,
            NovexWorkspaceProvenance("chat-1", "assistant-1", messageId = "assistant-1"),
        )

        assertEquals(NovexWorkspaceArea.DERIVED, entry.workspaceRef.area)
        val saved = JSONObject(store.readBytes(scope, entry.workspaceRef).toString(Charsets.UTF_8))
        assertEquals("assistant-1", saved.getString("branch_id"))
        assertEquals(2, saved.getJSONArray("source_message_refs").length())
        assertEquals(1, saved.getJSONArray("durable_fact_refs").length())
        assertFalse(saved.toString().contains(root.absolutePath))
        assertTrue(entry.workspaceRef.value.startsWith("novex://workspaces/"))
    }
}
