package com.openminis.app.novex.domain

import java.io.File
import org.json.JSONObject
import org.json.JSONArray
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
    fun `reopened checkpoint supplies exact branch events rather than unsupported model conclusions`() {
        val root = temporaryFolder.newFolder("evidence")
        val store = FileNovexConversationWorkspaceStore(root)
        val facts = listOf("午后正门已上锁，侧门后窗状态未知，没有外来访客，没有人从外面开门。", "傍晚倒了一杯水，没有记录饮水。",
            "青色围巾借给安禾后，于同日傍晚原样收回。", "收回时天色尚未全黑。")
        val events = facts.mapIndexed { index, text -> NovexCheckpointSourceEvent("event-$index", "user",
            JSONArray().put(JSONObject().put("type", "text").put("value", text)).toString(),
            if (index == 0) null else "event-${index - 1}", index.toLong(), null) }
        val configuration = NovexConversationConfigurationSnapshot("chat-1", activePlaythroughId = "play-1",
            activeInteractiveFiction = ActiveInteractiveFictionSnapshot("game", "snapshot", "本局"),
            playthroughStates = mapOf("event-3" to PlaythroughState("event-3", mapOf("health" to PlaythroughValue.Number(72.0)))))
        val path = events.map { it.messageId } + "missing-unpersisted"
        val scope = NovexConversationWorkspaceScope("chat-1", path, "reply")
        val summary = "人物被困，外人替他开门，已经喝水，围巾从未借出，现在是深夜。"
        val checkpoint = NovexPlaythroughCheckpointFactory.create("operation", configuration, path, "reply", "断点", summary,
            """{"model_says":"错误前情"}""", 100, events)
        val provenance = NovexWorkspaceProvenance("chat-1", "reply", "event-3", "tool-1")
        val saved = NovexPlaythroughCheckpointWriter(store).save(scope, checkpoint, provenance)
        val reopened = FileNovexConversationWorkspaceStore(root)
        val same = NovexPlaythroughCheckpointWriter(reopened).save(scope, checkpoint.copy(createdAtMillis = 200,
            playthroughValues = emptyMap(), sourceEvents = emptyList()), provenance)
        assertEquals(saved.sha256, same.sha256)
        assertEquals(1, reopened.inspect(scope).entries.size)
        val records = NovexCheckpointContinuation(reopened).inspect(scope)
        assertEquals(listOf("missing-unpersisted"), records.single().checkpoint!!.missingSourceMessageIds)
        assertEquals(events, records.single().checkpoint!!.sourceEvents)
        assertEquals(PlaythroughValue.Number(72.0), records.single().checkpoint!!.playthroughValues["health"])
        val prompt = NovexCheckpointContinuation(reopened).prepare(configuration, scope)!!.content
        facts.forEach { assertTrue(prompt.contains(it)) }
        assertFalse(prompt.contains(summary))
        assertFalse(prompt.contains("错误前情"))
        assertTrue(prompt.contains("未核验辅助整理"))
        assertTrue(prompt.contains(saved.workspaceRef.value))
        assertEquals(null, NovexCheckpointContinuation(reopened).prepare(configuration.copy(activePlaythroughId = "another-play"), scope))
        assertTrue(NovexCheckpointContinuation(reopened).inspect(NovexConversationWorkspaceScope("chat-1", events.map { it.messageId }, "sibling")).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { NovexPlaythroughCheckpointWriter(reopened).save(scope, checkpoint.copy(summary = "改写后重试"), provenance) }
    }

    @Test
    fun `legacy payload is preserved and corrupt event revisions cannot silently recover`() {
        val source = NovexCheckpointSourceEvent("message", "user", "[]", null, 1, null)
        val checkpoint = NovexPlaythroughCheckpoint("old", "chat", "reply", "旧存档", "旧摘要", "{}", emptyMap(), null, null, 1,
            sourceEvents = listOf(source), sourceCaptureRecorded = true)
        val encoded = JSONObject(NovexPlaythroughCheckpointCodec.encode(checkpoint))
        encoded.getJSONArray("source_events").getJSONObject(0).put("parts_json", "被改写")
        assertThrows(IllegalArgumentException::class.java) { NovexPlaythroughCheckpointCodec.decode(encoded.toString()) }
        val legacy = JSONObject(NovexPlaythroughCheckpointCodec.encode(checkpoint)).apply {
            put("version", 1); remove("source_events"); remove("source_capture_recorded"); put("unknown_original", "保留")
        }.toString()
        val restored = NovexPlaythroughCheckpointCodec.decode(legacy)
        assertFalse(restored.sourceCaptureRecorded)
        assertEquals(legacy, restored.legacyPayloadJson)
        assertEquals(legacy, NovexPlaythroughCheckpointCodec.decode(NovexPlaythroughCheckpointCodec.encode(restored)).legacyPayloadJson)
    }

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
