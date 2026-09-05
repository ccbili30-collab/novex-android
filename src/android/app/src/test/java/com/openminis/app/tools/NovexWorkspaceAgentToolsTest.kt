package com.openminis.app.tools

import com.openminis.app.novex.domain.FileNovexConversationWorkspaceStore
import com.openminis.app.novex.domain.NovexConversationWorkspaceScope
import com.openminis.app.novex.domain.NovexConversationWorkspaceToolRouter
import com.openminis.app.novex.domain.NovexWorkspaceProvenance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexWorkspaceAgentToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = NovexConversationWorkspaceScope(
        conversationId = "conversation-app",
        visibleBranchIds = listOf("user-message"),
        writeBranchId = "assistant-message",
    )

    @Test
    fun `workspace tools are exposed only when the conversation permits workspace access`() {
        val withoutWorkspace = AgentTools.makeAgentTools(workspaceAvailable = false)
        val withWorkspace = AgentTools.makeAgentTools(workspaceAvailable = true)

        assertFalse(withoutWorkspace.any { it.name.startsWith("workspace_") })
        assertEquals(
            listOf("workspace_inspect", "workspace_read", "workspace_write", "workspace_edit"),
            withWorkspace.filter { it.name.startsWith("workspace_") }.map { it.name },
        )
    }

    @Test
    fun `adapter executes through the Novex contract and returns no device path`() {
        val tools = NovexWorkspaceAgentTools(
            FileNovexConversationWorkspaceStore(temporaryFolder.newFolder()),
        )
        val result = tools.execute(
            name = NovexConversationWorkspaceToolRouter.WORKSPACE_WRITE,
            argumentsJson = JSONObject()
                .put("area", "notes")
                .put("path", "整理.md")
                .put("content", "资料冲突记录")
                .toString(),
            scope = scope,
            provenance = NovexWorkspaceProvenance(
                conversationId = scope.conversationId,
                branchId = scope.writeBranchId,
                messageId = "user-message",
                toolCallId = "tool-call",
            ),
        )

        assertTrue(result.success)
        assertEquals("写入工作区", result.toolTitle)
        assertTrue(result.output.contains("workspace.written"))
        assertFalse(result.output.contains(temporaryFolder.root.absolutePath))
    }
}
