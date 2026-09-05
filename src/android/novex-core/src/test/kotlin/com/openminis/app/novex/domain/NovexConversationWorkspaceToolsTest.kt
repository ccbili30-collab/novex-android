package com.openminis.app.novex.domain

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexConversationWorkspaceToolsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val parentBranch = "message-parent"
    private val firstReply = "assistant-first"
    private val secondReply = "assistant-second"

    @Test
    fun `logical references never expose device paths or allow path escape`() {
        val scope = scope(firstReply)
        val ref = NovexWorkspaceFileRef.create(
            scope = scope,
            area = NovexWorkspaceArea.DRAFTS,
            relativePath = "章节/第一章.md",
        )

        assertTrue(ref.value.startsWith("novex://workspaces/"))
        assertTrue(ref.value.contains("/drafts/"))
        assertFalse(ref.value.contains(temporaryFolder.root.absolutePath))
        assertEquals("章节/第一章.md", NovexWorkspaceFileRef.parse(ref.value).relativePath)

        listOf("../secret", "safe/../../secret", "/absolute", "safe\\secret", "safe\u0000name")
            .forEach { unsafe ->
                val rejected = runCatching {
                    NovexWorkspaceFileRef.create(scope, NovexWorkspaceArea.DRAFTS, unsafe)
                }
                assertTrue("应该拒绝 $unsafe", rejected.isFailure)
            }
    }

    @Test
    fun `source and derived areas are read only to the model`() {
        val tools = tools(scope(firstReply))

        listOf(NovexWorkspaceArea.SOURCES, NovexWorkspaceArea.DERIVED).forEach { area ->
            val result = tools.workspaceWrite(
                NovexWorkspaceWriteRequest(area, "资料.md", "不应写入"),
            )

            assertFalse(result.ok)
            assertEquals("workspace.area_read_only", result.code)
            assertEquals(NovexToolSideEffect.NONE, result.sideEffect)
        }
    }

    @Test
    fun `writes are branch local while descendants inherit parent files`() {
        val store = FileNovexConversationWorkspaceStore(temporaryFolder.newFolder("branch-workspace"))
        val parentScope = scope(parentBranch, visible = emptyList())
        val parentTools = NovexConversationWorkspaceTools(parentScope, store)
        val parentWrite = parentTools.workspaceWrite(
            NovexWorkspaceWriteRequest(NovexWorkspaceArea.NOTES, "人物/苏晚晴.md", "父分支版本"),
        )
        val parentRef = affectedRef(parentWrite)

        val firstScope = scope(firstReply, visible = listOf(parentBranch))
        val firstTools = NovexConversationWorkspaceTools(firstScope, store)
        assertTrue(firstTools.workspaceRead(NovexWorkspaceReadRequest(parentRef)).ok)
        val childWrite = firstTools.workspaceWrite(
            NovexWorkspaceWriteRequest(NovexWorkspaceArea.NOTES, "人物/第一分支补充.md", "第一回复分支版本"),
        )

        val secondScope = scope(secondReply, visible = listOf(parentBranch))
        val secondInspection = JSONObject(
            NovexConversationWorkspaceTools(secondScope, store)
                .workspaceInspect(NovexWorkspaceInspectRequest()).toJson(),
        ).getJSONObject("data")
        val secondEntries = secondInspection.getJSONArray("entries")

        assertEquals(1, secondEntries.length())
        assertEquals(parentRef.value, secondEntries.getJSONObject(0).getString("workspace_ref"))
        assertNotEquals(parentRef, affectedRef(childWrite))
        assertEquals(
            "父分支版本",
            readText(
                NovexConversationWorkspaceTools(secondScope, store)
                    .workspaceRead(NovexWorkspaceReadRequest(parentRef)),
            ),
        )
    }

    @Test
    fun `bounded reads continue with a stable cursor`() {
        val tools = tools(scope(firstReply))
        val ref = affectedRef(
            tools.workspaceWrite(
                NovexWorkspaceWriteRequest(NovexWorkspaceArea.DRAFTS, "长文.txt", "一二三四五六七八九十"),
            ),
        )

        val first = tools.workspaceRead(NovexWorkspaceReadRequest(ref, maxChars = 4))
        val firstData = JSONObject(first.toJson()).getJSONObject("data")
        val second = tools.workspaceRead(
            NovexWorkspaceReadRequest(ref, cursor = firstData.getString("next_cursor"), maxChars = 6),
        )
        val secondData = JSONObject(second.toJson()).getJSONObject("data")

        assertEquals("一二三四", firstData.getString("content"))
        assertEquals("五六七八九十", secondData.getString("content"))
        assertTrue(firstData.getBoolean("truncated"))
        assertFalse(secondData.getBoolean("truncated"))
    }

    @Test
    fun `write never silently overwrites a visible file`() {
        val tools = tools(scope(firstReply))
        val request = NovexWorkspaceWriteRequest(NovexWorkspaceArea.NOTES, "结论.md", "第一版")

        assertTrue(tools.workspaceWrite(request).ok)
        val duplicate = tools.workspaceWrite(request.copy(content = "第二版"))

        assertFalse(duplicate.ok)
        assertEquals("workspace.already_exists", duplicate.code)
    }

    @Test
    fun `text written to outputs becomes a managed creative artifact`() {
        val tools = tools(scope(firstReply))
        val result = tools.workspaceWrite(
            NovexWorkspaceWriteRequest(
                area = NovexWorkspaceArea.OUTPUTS,
                relativePath = "成品/第一章.md",
                content = "正文",
            ),
        )
        val json = JSONObject(result.toJson())
        val data = json.getJSONObject("data")

        assertTrue(result.ok)
        assertTrue(data.getString("artifact_ref").startsWith("novex://artifacts/sha256-"))
        assertEquals(2, json.getJSONArray("affected_refs").length())
        assertEquals(firstReply, data.getString("source_branch"))
    }

    @Test
    fun `edits use copy on write and reject stale hashes without partial changes`() {
        val store = FileNovexConversationWorkspaceStore(temporaryFolder.newFolder("edit-workspace"))
        val parentTools = NovexConversationWorkspaceTools(scope(parentBranch, visible = emptyList()), store)
        val parentRef = affectedRef(
            parentTools.workspaceWrite(
                NovexWorkspaceWriteRequest(NovexWorkspaceArea.DRAFTS, "故事.md", "旧世界"),
            ),
        )
        val parentSha = readSha(parentTools.workspaceRead(NovexWorkspaceReadRequest(parentRef)))
        val childTools = NovexConversationWorkspaceTools(scope(firstReply, visible = listOf(parentBranch)), store)

        val edited = childTools.workspaceEdit(
            NovexWorkspaceEditRequest(
                workspaceRef = parentRef,
                expectedSha256 = parentSha,
                startChar = 0,
                endChar = 1,
                replacement = "新",
            ),
        )
        val childRef = affectedRef(edited)
        val stale = childTools.workspaceEdit(
            NovexWorkspaceEditRequest(
                workspaceRef = childRef,
                expectedSha256 = parentSha,
                startChar = 0,
                endChar = 1,
                replacement = "坏",
            ),
        )

        assertTrue(edited.ok)
        assertEquals("新世界", readText(childTools.workspaceRead(NovexWorkspaceReadRequest(childRef))))
        assertEquals("旧世界", readText(parentTools.workspaceRead(NovexWorkspaceReadRequest(parentRef))))
        assertFalse(stale.ok)
        assertEquals("workspace.edit_conflict", stale.code)
        assertEquals("新世界", readText(childTools.workspaceRead(NovexWorkspaceReadRequest(childRef))))
    }

    @Test
    fun `creative artifacts are content addressed and preserve branch provenance after restart`() {
        val root = temporaryFolder.newFolder("artifact-workspace")
        val store = FileNovexConversationWorkspaceStore(root, nowMillis = { 1234L })
        val scope = scope(firstReply, visible = listOf(parentBranch))
        val provenance = NovexWorkspaceProvenance(
            conversationId = scope.conversationId,
            branchId = scope.writeBranchId,
            messageId = "user-message",
            toolCallId = "tool-call",
            sourceRefs = listOf(NovexResourceRef("novex://documents/source-1")),
        )

        val first = store.importArtifact(
            scope = scope,
            area = NovexWorkspaceArea.OUTPUTS,
            relativePath = "地图/云岚.png",
            bytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "image/png",
            provenance = provenance,
        )
        val duplicate = store.importArtifact(
            scope = scope,
            area = NovexWorkspaceArea.DERIVED,
            relativePath = "地图/缩略图.png",
            bytes = byteArrayOf(1, 2, 3, 4),
            mimeType = "image/png",
            provenance = provenance,
        )
        val restarted = FileNovexConversationWorkspaceStore(root)
        val restored = restarted.inspect(scope).entries.single { it.workspaceRef == first.workspaceRef }

        assertEquals(first.artifactRef, duplicate.artifactRef)
        assertTrue(first.artifactRef!!.value.startsWith("novex://artifacts/sha256-"))
        assertEquals(first.artifactRef, restored.artifactRef)
        assertEquals(first.provenance, restored.provenance)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), restarted.readBytes(scope, first.workspaceRef).toList())
    }

    @Test
    fun `model writes persist across repository recreation and stay reversible`() {
        val root = temporaryFolder.newFolder("persistent-workspace")
        val scope = scope(firstReply)
        val firstTools = NovexConversationWorkspaceTools(
            scope,
            FileNovexConversationWorkspaceStore(root, nowMillis = { 50L }),
        )
        val write = firstTools.workspaceWrite(
            NovexWorkspaceWriteRequest(NovexWorkspaceArea.SAVES, "第一存档.json", "{\"hp\":92}", "application/json"),
        )
        val ref = affectedRef(write)
        val restartedTools = NovexConversationWorkspaceTools(scope, FileNovexConversationWorkspaceStore(root))

        assertEquals("{\"hp\":92}", readText(restartedTools.workspaceRead(NovexWorkspaceReadRequest(ref))))
        assertEquals(NovexToolSideEffect.SESSION_REVERSIBLE, write.sideEffect)
        assertNull(JSONObject(write.toJson()).optJSONObject("internal_path"))
        assertFalse(write.toJson().contains(root.absolutePath))
    }

    @Test
    fun `native export receives a filename and bytes without needing an internal path`() {
        val root = temporaryFolder.newFolder("export-workspace")
        val scope = scope(firstReply)
        val store = FileNovexConversationWorkspaceStore(root)
        val tools = NovexConversationWorkspaceTools(scope, store)
        val ref = affectedRef(
            tools.workspaceWrite(
                NovexWorkspaceWriteRequest(NovexWorkspaceArea.OUTPUTS, "成品/设定.md", "云岚设定"),
            ),
        )
        var exportedName = ""
        var exportedMime = ""
        var exportedText = ""

        NovexWorkspaceArtifactExporter(store).export(scope, ref) { name, mimeType, bytes ->
            exportedName = name
            exportedMime = mimeType
            exportedText = bytes.toString(Charsets.UTF_8)
        }

        assertEquals("设定.md", exportedName)
        assertEquals("text/markdown", exportedMime)
        assertEquals("云岚设定", exportedText)
        assertFalse(exportedText.contains(root.absolutePath))
    }

    private fun scope(
        writeBranch: String,
        visible: List<String> = listOf(parentBranch),
    ) = NovexConversationWorkspaceScope(
        conversationId = "conversation-1",
        visibleBranchIds = visible,
        writeBranchId = writeBranch,
    )

    private fun tools(scope: NovexConversationWorkspaceScope): NovexConversationWorkspaceTools =
        NovexConversationWorkspaceTools(
            scope = scope,
            store = FileNovexConversationWorkspaceStore(temporaryFolder.newFolder()),
        )

    private fun affectedRef(result: NovexToolResult): NovexWorkspaceFileRef =
        result.affectedRefs.firstNotNullOf { ref ->
            runCatching { NovexWorkspaceFileRef.parse(ref.value) }.getOrNull()
        }

    private fun readText(result: NovexToolResult): String =
        JSONObject(result.toJson()).getJSONObject("data").getString("content")

    private fun readSha(result: NovexToolResult): String =
        JSONObject(result.toJson()).getJSONObject("data").getString("sha256")
}
