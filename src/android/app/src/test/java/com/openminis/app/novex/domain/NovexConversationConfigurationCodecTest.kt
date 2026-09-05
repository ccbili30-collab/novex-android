package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexConversationConfigurationCodecTest {
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
