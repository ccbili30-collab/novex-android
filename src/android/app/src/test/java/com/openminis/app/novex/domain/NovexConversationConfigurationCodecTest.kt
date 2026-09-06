package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class NovexConversationConfigurationCodecTest {
    @Test
    fun selectedPersonaSurvivesRestartAndMountingBackgroundWithoutBecomingNova() {
        val saved = """{"version":1,"answerIdentity":{"kind":"personaPreset","presetId":"historian","label":"历史共创者","instructions":"区分历史事实与架空推演。"}}"""
        val restored = NovexConversationConfigurationCodec.decode(saved, "chat-1")
        val changed = NovexConversationConfiguration.open(restored)
            .apply(NovexConversationCommand.AddBackground(NovexContentAddress.world("world-1")))
            .apply(NovexConversationCommand.MountSubject(
                NovexContentAddress.characterVersion("fusheng"), ManagedAccess.EDIT,
            ))
        val persisted = JSONObject(NovexConversationConfigurationCodec.encode(changed.snapshot))
            .getJSONObject("answerIdentity")

        assertEquals("personaPreset", persisted.getString("kind"))
        assertEquals("historian", persisted.getString("presetId"))
        assertEquals("历史共创者", persisted.getString("label"))
        assertEquals("区分历史事实与架空推演。", persisted.getString("instructions"))
        assertEquals(changed.snapshot, NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(changed.snapshot), "chat-1",
        ))
    }

    @Test
    fun everyConversationConfigurationRelationSurvivesPersistenceRoundTrip() {
        val sharedWorld = NovexContentAddress.world("world-1")
        val snapshot = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            answerIdentity = AnswerIdentity.CharacterVersion("version-1"),
            backgroundSettings = listOf(BackgroundSetting(sharedWorld)),
            managedSubjects = listOf(ManagedSubject(sharedWorld, ManagedAccess.EDIT)),
            activeInteractiveFiction = ActiveInteractiveFictionSnapshot(
                "game-1",
                "snapshot-1",
                "云岚问道",
                contentJson = """{"summary":"修行冒险"}""",
                presetControls = listOf(
                    ConversationControlDefinition(
                        id = "project-status",
                        label = "文游状态",
                        behavior = ConversationControlBehavior.VIEW,
                        source = ConversationControlSource.PROJECT_PRESET,
                        actionKey = "project.status",
                    ),
                ),
            ),
            playthroughStates = mapOf(
                "branch-1" to PlaythroughState(
                    "branch-1",
                    mapOf(
                        "生命" to PlaythroughValue.Number(80.0),
                        "地点" to PlaythroughValue.Text("山门"),
                        "已入门" to PlaythroughValue.Flag(true),
                    ),
                ),
            ),
            controls = listOf(
                ConversationControlDefinition(
                    id = "project-status",
                    label = "文游状态",
                    behavior = ConversationControlBehavior.VIEW,
                    source = ConversationControlSource.PROJECT_PRESET,
                    actionKey = "project.status",
                ),
                ConversationControlDefinition(
                    id = "status",
                    label = "角色档案",
                    behavior = ConversationControlBehavior.VIEW,
                    source = ConversationControlSource.USER,
                    actionKey = "show_status",
                    enabled = false,
                    branchId = "reply-a",
                ),
            ),
        )

        val restored = NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(snapshot),
            conversationId = "chat-1",
        )

        assertEquals(snapshot, restored)
        assertEquals("reply-a", restored.controls.last().branchId)
    }

    @Test
    fun emptyOrBrokenLegacyValuesRecoverAsANovaConversation() {
        val empty = NovexConversationConfigurationCodec.decode(null, "chat-legacy")
        val broken = NovexConversationConfigurationCodec.decode("{broken", "chat-legacy")

        assertEquals(NovexConversationConfiguration.empty("chat-legacy").snapshot, empty)
        assertEquals(empty, broken)
    }
}
