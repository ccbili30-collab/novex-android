package com.openminis.app.novex.domain

import com.openminis.app.tools.NovexWorkspaceAgentTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexCheckpointPrivacyTest {
    @get:Rule val files = TemporaryFolder()
    private val secret = "只属于旧角色的配套玩家秘密"
    private val scope = NovexConversationWorkspaceScope("chat", listOf("spoken", "read", "save"), "save")
    private val old = NovexConversationConfigurationSnapshot("chat", answerIdentity =
        AnswerIdentity.PersonaPreset("old-persona", "旧身份", secret), activePlaythroughId = "play",
        activeInteractiveFiction = ActiveInteractiveFictionSnapshot("game", "snapshot", "本局"))

    private fun saved(): Pair<FileNovexConversationWorkspaceStore, NovexWorkspaceEntry> {
        val store = FileNovexConversationWorkspaceStore(files.newFolder())
        val events = listOf(
            NovexCheckpointSourceEvent("spoken", "user", """[{"type":"text","value":"我还没有喝水。"}]""", null, 1, null),
            NovexCheckpointSourceEvent("read", "user", JSONArray().put(JSONObject().put("type", "toolResult")
                .put("value", JSONObject().put("id", "read-private").put("name", "novex_read_context").put("content", secret))).toString(), "spoken", 2, null),
        )
        val checkpoint = NovexPlaythroughCheckpointFactory.create("save", old, scope.visibleBranchIds, "save", "前情", "未核验摘要", "{}", 3, events)
        val entry = NovexPlaythroughCheckpointWriter(store).save(scope, checkpoint, NovexWorkspaceProvenance("chat", "save", "save", "save-call"))
        return store to entry
    }

    @Test fun continuationDoesNotInjectArchivedPrivateToolResultsIntoANewIdentity() {
        val (store, entry) = saved()
        val content = NovexCheckpointContinuation(store).prepare(old.copy(answerIdentity = AnswerIdentity.Nova), scope)!!.content
        assertTrue(content.contains("我还没有喝水"))
        assertFalse("旧工具读取不能冒充续接的公开剧情", content.contains(secret))
        assertTrue("用户原始存档必须完整保留", store.readBytes(scope, entry.workspaceRef).toString(Charsets.UTF_8).contains(secret))
    }

    @Test fun workspaceReadAndSearchCannotBypassTheAdoptedContextBoundaryViaASaveFile() {
        val (store, entry) = saved()
        val tools = NovexWorkspaceAgentTools(store)
        val read = tools.execute("workspace_read", JSONObject().put("workspace_ref", entry.workspaceRef.value).toString(), scope,
            NovexWorkspaceProvenance("chat", "save"))
        assertTrue(read.output, read.success)
        assertTrue(read.output.contains("我还没有喝水"))
        assertFalse("原始环境与工具记录不能通过仓库全文旁路读取", read.output.contains(secret))
        val search = tools.execute("workspace_search", JSONObject().put("query", secret).toString(), scope,
            NovexWorkspaceProvenance("chat", "save"))
        assertTrue(search.output, search.success)
        assertFalse(search.output.contains("checkpoint-save"))
        val merged = tools.execute("workspace_compute", JSONObject().put("operation", "merge_text")
            .put("input_refs", JSONArray().put(entry.workspaceRef.value)).put("output_area", "notes").put("output_path", "续接笔记.txt").toString(),
            scope, NovexWorkspaceProvenance("chat", "save"))
        assertTrue(merged.output, merged.success)
        val note = store.inspect(scope).entries.single { it.workspaceRef.relativePath == "续接笔记.txt" }
        val text = store.readBytes(scope, note.workspaceRef).toString(Charsets.UTF_8)
        assertFalse("受限计算也必须读取相同视图", text.contains(secret))
        assertTrue(text.contains("我还没有喝水"))
        assertTrue(store.readBytes(scope, entry.workspaceRef).toString(Charsets.UTF_8).contains(secret))
    }

    @Test fun persistedSummaryCanOnlyBeReadInItsRecordedScope() {
        val store = FileNovexConversationWorkspaceStore(files.newFolder())
        val oldKey = com.openminis.app.novex.domain.NovexHistoryAccessScope.key(old)
        val entry = NovexDistillationRecordWriter(store).save(scope, NovexDistillationRecord("old-summary", "chat", "save", secret,
            listOf(NovexResourceRef("novex://conversations/chat/messages/read")), emptyList(), 5, oldKey),
            NovexWorkspaceProvenance("chat", "save"))
        val tools = NovexWorkspaceAgentTools(store)
        val args = JSONObject().put("workspace_ref", entry.workspaceRef.value).toString()
        for (key in listOf(null, "new-scope")) {
            val read = tools.execute("workspace_read", args, scope, NovexWorkspaceProvenance("chat", "save"), historyScopeKey = key)
            assertTrue(read.output, read.success)
            assertFalse(read.output.contains(secret))
        }
        val allowed = tools.execute("workspace_read", args, scope, NovexWorkspaceProvenance("chat", "save"), historyScopeKey = oldKey)
        assertTrue(allowed.output, allowed.success)
        assertTrue(allowed.output.contains(secret))
        assertTrue(store.readBytes(scope, entry.workspaceRef).toString(Charsets.UTF_8).contains(secret))
    }
}
