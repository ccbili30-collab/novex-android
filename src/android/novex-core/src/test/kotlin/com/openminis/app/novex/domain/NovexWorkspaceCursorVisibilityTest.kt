package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexWorkspaceCursorVisibilityTest {
    @get:Rule val temp = TemporaryFolder()
    private fun data(result: NovexToolResult) = JSONObject(result.toJson()).getJSONObject("data")

    @Test fun `recording an intervening tool reply does not invalidate unchanged inventory pagination`() {
        val store = FileNovexConversationWorkspaceStore(temp.newFolder())
        val scope = NovexConversationWorkspaceScope("chat", listOf("user"), "reply")
        for (name in listOf("a.txt", "b.txt")) store.importArtifact(
            NovexConversationWorkspaceScope("chat", emptyList(), "root"), NovexWorkspaceArea.SOURCES,
            name, name.toByteArray(), "text/plain", NovexWorkspaceProvenance("chat", "root"))
        val first = NovexWorkspaceBrowser(scope, store).browse(null, null, null, null, 1)
        val cursor = data(first).getString("next_cursor")
        val nextScope = scope.copy(visibleBranchIds = listOf("user", "tool-result"))
        val next = NovexWorkspaceBrowser(nextScope, store).browse(null, null, null, cursor, 1)
        assertTrue(next.toJson(), next.ok)
        assertEquals("b.txt", data(next).getJSONArray("entries").getJSONObject(0).getString("path"))
        assertFalse(data(next).getBoolean("truncated"))
    }

    @Test fun `newly visible branch files still invalidate a cursor`() {
        val store = FileNovexConversationWorkspaceStore(temp.newFolder())
        val scope = NovexConversationWorkspaceScope("chat", emptyList(), "root")
        for (name in listOf("a.txt", "b.txt")) store.importArtifact(scope, NovexWorkspaceArea.SOURCES,
            name, name.toByteArray(), "text/plain", NovexWorkspaceProvenance("chat", "root"))
        val hidden = scope.copy(writeBranchId = "sibling")
        store.writeText(hidden, NovexWorkspaceArea.NOTES, "secret.txt", "分支资料", "text/plain", NovexWorkspaceProvenance("chat", "sibling"))
        val cursor = data(NovexWorkspaceBrowser(scope, store).browse(null, null, null, null, 1)).getString("next_cursor")
        val result = NovexWorkspaceBrowser(scope.copy(visibleBranchIds = listOf("sibling")), store).browse(null, null, null, cursor, 1)
        assertFalse(result.ok)
        assertEquals("workspace.invalid_cursor", result.code)
        assertFalse(result.toJson().contains("分支资料"))
    }
}
