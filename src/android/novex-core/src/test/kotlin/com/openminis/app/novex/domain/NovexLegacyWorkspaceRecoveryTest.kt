package com.openminis.app.novex.domain

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexLegacyWorkspaceRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun scope(branch: String, conversation: String = "conversation") =
        NovexConversationWorkspaceScope(conversation, listOf(branch), branch)

    private fun write(store: FileNovexConversationWorkspaceStore, branch: String, message: String?,
        content: String = "灯在桌上", path: String = "资料/状态.md", conversation: String = "conversation") =
        store.writeText(scope(branch, conversation), NovexWorkspaceArea.NOTES, path, content, "text/markdown",
            NovexWorkspaceProvenance(conversation, branch, messageId = message))

    @Test fun `persisted reply recovers exact bytes visible after reopen without deleting original`() {
        val root = temp.newFolder()
        val store = FileNovexConversationWorkspaceStore(root)
        val original = write(store, "assistant_123", "reply-one")
        assertTrue(store.inspect(scope("reply-one")).entries.isEmpty())
        assertEquals(1, store.recoverLegacyReplyFiles("conversation", setOf("reply-one")))
        val reopened = FileNovexConversationWorkspaceStore(root)
        val entry = reopened.inspect(scope("reply-one")).entries.single()
        assertEquals("reply-one", entry.workspaceRef.branchId)
        assertEquals(original.sha256, entry.sha256)
        assertEquals(original.provenance.messageId, entry.provenance.messageId)
        assertTrue(entry.provenance.sourceRefs.contains(original.workspaceRef.asResourceRef()))
        assertEquals("灯在桌上", reopened.readBytes(scope("reply-one"), entry.workspaceRef).toString(Charsets.UTF_8))
        assertEquals("灯在桌上", reopened.readBytes(scope("assistant_123"), original.workspaceRef).toString(Charsets.UTF_8))
        assertEquals(0, reopened.recoverLegacyReplyFiles("conversation", setOf("reply-one")))
        assertEquals(1, reopened.inspect(scope("reply-one")).entries.size)
        assertTrue(reopened.inspect(scope("sibling")).entries.isEmpty())
        assertNull(reopened.find(scope("reply-one", "other"), entry.workspaceRef))
    }

    @Test fun `unproven and sibling files are never exposed and current targets never overwritten`() {
        val store = FileNovexConversationWorkspaceStore(temp.newFolder())
        write(store, "assistant_1", "sibling")
        write(store, "assistant_2", null, path = "未知.md")
        write(store, "assistant_3", "reply", "旧内容")
        write(store, "reply", "reply", "当前内容")
        write(store, "ordinary-branch", "reply", path = "普通分支.md")
        assertEquals(0, store.recoverLegacyReplyFiles("conversation", setOf("reply")))
        val entry = store.inspect(scope("reply")).entries.single()
        assertEquals("当前内容", store.readBytes(scope("reply"), entry.workspaceRef).toString(Charsets.UTF_8))
        assertEquals(1, store.recoverLegacyReplyFiles("conversation", setOf("sibling")))
        assertEquals(1, store.inspect(scope("reply")).entries.size)
    }

    @Test fun `conflicting legacy candidates and damaged bytes are preserved without promotion`() {
        val root = temp.newFolder()
        val store = FileNovexConversationWorkspaceStore(root)
        write(store, "assistant_1", "reply", "甲")
        write(store, "assistant_2", "reply", "乙")
        write(store, "assistant_3", "reply", "原始内容", path = "损坏.md")
        root.walkTopDown().single { it.isFile && it.name == "损坏.md" }.writeText("已损坏")
        assertEquals(0, store.recoverLegacyReplyFiles("conversation", setOf("reply")))
        assertTrue(store.inspect(scope("reply")).entries.isEmpty())
        assertEquals(3, store.inspectConversation("conversation").sumOf { it.entries.size })
    }
}
