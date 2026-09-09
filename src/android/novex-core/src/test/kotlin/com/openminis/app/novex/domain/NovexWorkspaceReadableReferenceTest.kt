package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexWorkspaceReadableReferenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = NovexConversationWorkspaceScope("chat", emptyList(), "root")

    @Test fun `read accepts displayed Chinese path and canonical reference as the same stored file`() {
        val store = FileNovexConversationWorkspaceStore(temp.newFolder())
        val path = "资料/儒路线 · 人物🕊+档案.txt"
        val entry = store.importArtifact(scope, NovexWorkspaceArea.SOURCES, path,
            "林川出生于桥北村。".toByteArray(), "text/plain", NovexWorkspaceProvenance("chat", "root"))
        val router = NovexConversationWorkspaceToolRouter(NovexConversationWorkspaceTools(scope, store))
        val displayed = "novex://workspaces/chat/branches/root/sources/$path"
        for (ref in listOf(displayed, entry.workspaceRef.value, displayed.replace("人物", "%E4%BA%BA%E7%89%A9"))) {
            val result = router.execute("workspace_read", JSONObject().put("workspace_ref", ref).toString())
            assertTrue(result.toJson(), result.ok)
            assertTrue(result.toJson().contains("林川出生于桥北村"))
            assertEquals(entry.workspaceRef, NovexWorkspaceFileRef.parse(ref))
        }
        val other = NovexConversationWorkspaceToolRouter(NovexConversationWorkspaceTools(
            NovexConversationWorkspaceScope("other", emptyList(), "root"), store))
        assertFalse(other.execute("workspace_read", JSONObject().put("workspace_ref", displayed).toString()).ok)
    }

    @Test fun `malformed UTF8 cannot alias a real replacement character filename`() {
        val store = FileNovexConversationWorkspaceStore(temp.newFolder())
        store.importArtifact(scope, NovexWorkspaceArea.SOURCES, "�.txt", "不可误读".toByteArray(),
            "text/plain", NovexWorkspaceProvenance("chat", "root"))
        val router = NovexConversationWorkspaceToolRouter(NovexConversationWorkspaceTools(scope, store))
        val result = router.execute("workspace_read", JSONObject().put("workspace_ref",
            "novex://workspaces/chat/branches/root/sources/%FF.txt").toString())
        assertFalse(result.toJson(), result.ok)
    }

    @Test fun `unicode tolerance retains traversal rejection and exactly one percent decoding`() {
        val prefix = "novex://workspaces/chat/branches/root/sources/"
        for (path in listOf("../secret", "%2e%2e/secret", "资料/%2F..%2Fsecret", "%5Csecret", "%00.txt", "%ED%A0%80", "bad%")) {
            assertThrows(path, IllegalArgumentException::class.java) { NovexWorkspaceFileRef.parse(prefix + path) }
        }
        assertEquals("资料/%20.txt", NovexWorkspaceFileRef.parse(prefix + "资料/%2520.txt").relativePath)
    }
}
