package com.openminis.app.novex.domain

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexPlaythroughCheckpointTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun checkpointRoundTripCombinesStructuredStateWithTheVisiblePlaythroughBranch() {
        val configuration = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            activeInteractiveFiction = ActiveInteractiveFictionSnapshot(
                projectId = "game-1",
                snapshotId = "snapshot-7",
                title = "云岚书院",
            ),
            playthroughStates = mapOf(
                "reply-a" to PlaythroughState(
                    branchId = "reply-a",
                    values = mapOf(
                        "health" to PlaythroughValue.Number(72.0),
                        "location" to PlaythroughValue.Text("山门"),
                    ),
                ),
            ),
        )

        val checkpoint = NovexPlaythroughCheckpointFactory.create(
            id = "checkpoint-1",
            configuration = configuration,
            activePathIds = listOf("user-1", "reply-a"),
            writeBranchId = "reply-b",
            name = "入山前",
            summary = "主角抵达山门，尚未选择师承。",
            stateJson = """{"inventory":["玉佩"],"threads":["寻找旧友"]}""",
            createdAtMillis = 1_234L,
        )

        val restored = NovexPlaythroughCheckpointCodec.decode(
            NovexPlaythroughCheckpointCodec.encode(checkpoint),
        )

        assertEquals("chat-1", restored.conversationId)
        assertEquals("reply-b", restored.branchId)
        assertEquals("game-1", restored.interactiveFictionProjectId)
        assertEquals("snapshot-7", restored.interactiveFictionSnapshotId)
        assertEquals(PlaythroughValue.Number(72.0), restored.playthroughValues["health"])
        assertEquals("玉佩", JSONObject(restored.stateJson).getJSONArray("inventory").getString(0))
    }

    @Test
    fun writerStoresCheckpointInBranchLocalSavesWithoutExposingADevicePath() {
        val root = temporaryFolder.newFolder("workspaces")
        val store = FileNovexConversationWorkspaceStore(root) { 2_000L }
        val scope = NovexConversationWorkspaceScope(
            conversationId = "chat-1",
            visibleBranchIds = listOf("user-1"),
            writeBranchId = "assistant-1",
        )
        val checkpoint = NovexPlaythroughCheckpoint(
            id = "checkpoint-1",
            conversationId = "chat-1",
            branchId = "assistant-1",
            name = "第一幕",
            summary = "已离开村庄。",
            stateJson = """{"location":"城外"}""",
            playthroughValues = emptyMap(),
            interactiveFictionProjectId = null,
            interactiveFictionSnapshotId = null,
            createdAtMillis = 1_234L,
        )

        val entry = NovexPlaythroughCheckpointWriter(store).save(
            scope = scope,
            checkpoint = checkpoint,
            provenance = NovexWorkspaceProvenance(
                conversationId = "chat-1",
                branchId = "assistant-1",
                messageId = "user-1",
                toolCallId = "tool-1",
            ),
        )

        val saved = store.readBytes(scope, entry.workspaceRef).toString(Charsets.UTF_8)
        assertEquals(NovexWorkspaceArea.SAVES, entry.workspaceRef.area)
        assertTrue(entry.workspaceRef.value.startsWith("novex://workspaces/"))
        assertTrue(JSONObject(saved).getString("summary").contains("离开村庄"))
        assertFalse(entry.workspaceRef.value.contains("/var/minis"))
        assertFalse(saved.contains(root.absolutePath))
    }

    @Test
    fun invalidStructuredStateIsRejectedBeforeAnythingIsWritten() {
        val root = temporaryFolder.newFolder("invalid")
        val store = FileNovexConversationWorkspaceStore(root)
        val scope = NovexConversationWorkspaceScope("chat-1", emptyList(), "assistant-1")

        assertThrows(IllegalArgumentException::class.java) {
            NovexPlaythroughCheckpointFactory.create(
                id = "checkpoint-1",
                configuration = NovexConversationConfigurationSnapshot("chat-1"),
                activePathIds = emptyList(),
                writeBranchId = "assistant-1",
                name = "损坏存档",
                summary = "不应写入",
                stateJson = "not json",
                createdAtMillis = 1L,
            )
        }
        assertTrue(store.inspect(scope).entries.isEmpty())
        assertFalse(File(root, "conversations").exists())
    }
}
