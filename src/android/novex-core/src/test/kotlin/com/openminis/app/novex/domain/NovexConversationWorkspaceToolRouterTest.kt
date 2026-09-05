package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexConversationWorkspaceToolRouterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = NovexConversationWorkspaceScope(
        conversationId = "conversation-router",
        visibleBranchIds = listOf("user-message"),
        writeBranchId = "assistant-message",
    )
    private val router by lazy {
        NovexConversationWorkspaceToolRouter(
            NovexConversationWorkspaceTools(
                scope = scope,
                store = FileNovexConversationWorkspaceStore(temporaryFolder.newFolder()),
            ),
        )
    }

    @Test
    fun `catalog publishes exactly four workspace tools with stable risks`() {
        val definitions = NovexToolCatalog.forCapabilities(setOf(NovexToolCapability.WORKSPACE))

        assertEquals(
            listOf("workspace_inspect", "workspace_read", "workspace_write", "workspace_edit"),
            definitions.map { it.name },
        )
        assertEquals(
            listOf(
                NovexToolRisk.READ_ONLY,
                NovexToolRisk.READ_ONLY,
                NovexToolRisk.SESSION_REVERSIBLE,
                NovexToolRisk.SESSION_REVERSIBLE,
            ),
            definitions.map { it.risk },
        )
    }

    @Test
    fun `router writes and reads without accepting a device path`() {
        val write = router.execute(
            NovexConversationWorkspaceToolRouter.WORKSPACE_WRITE,
            JSONObject()
                .put("area", "drafts")
                .put("path", "提纲.md")
                .put("content", "第一章")
                .toString(),
        )
        val ref = JSONObject(write.toJson()).getJSONArray("affected_refs").getString(0)
        val read = router.execute(
            NovexConversationWorkspaceToolRouter.WORKSPACE_READ,
            JSONObject().put("workspace_ref", ref).toString(),
        )

        assertTrue(write.ok)
        assertTrue(read.ok)
        assertTrue(read.toJson().contains("第一章"))
        assertFalse(read.toJson().contains(temporaryFolder.root.absolutePath))
    }

    @Test
    fun `invalid area returns legal tool names without internal exception text`() {
        val result = router.execute(
            NovexConversationWorkspaceToolRouter.WORKSPACE_WRITE,
            JSONObject().put("area", "system").put("path", "x").put("content", "x").toString(),
        )

        assertFalse(result.ok)
        assertEquals("tool.invalid_arguments", result.code)
        assertEquals(NovexConversationWorkspaceToolRouter.TOOL_NAMES, result.allowedValues)
        assertFalse(result.toJson().contains("IllegalArgumentException"))
    }
}
