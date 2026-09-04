package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexConversationConfigurationTest {
    @Test
    fun `background and management relationships for the same subject remain independent`() {
        val world = NovexContentAddress.world("world-1")
        val configured = NovexConversationConfiguration.empty("conversation-1")
            .apply(NovexConversationCommand.AddBackground(world))
            .apply(NovexConversationCommand.MountSubject(world, ManagedAccess.EDIT))

        assertEquals(listOf(world), configured.snapshot.backgroundSettings.map { it.subject })
        assertEquals(listOf(world), configured.snapshot.managedSubjects.map { it.subject })

        val withoutBackground = configured.apply(NovexConversationCommand.RemoveBackground(world))
        assertTrue(withoutBackground.snapshot.backgroundSettings.isEmpty())
        assertEquals(listOf(world), withoutBackground.snapshot.managedSubjects.map { it.subject })

        val withoutManagement = configured.apply(NovexConversationCommand.UnmountSubject(world))
        assertEquals(listOf(world), withoutManagement.snapshot.backgroundSettings.map { it.subject })
        assertTrue(withoutManagement.snapshot.managedSubjects.isEmpty())
    }

    @Test
    fun `a conversation always has exactly one answer identity and setting another replaces it`() {
        val empty = NovexConversationConfiguration.empty("conversation-1")
        assertEquals(AnswerIdentity.Nova, empty.snapshot.answerIdentity)

        val firstCharacter = empty.apply(
            NovexConversationCommand.SetAnswerIdentity(
                AnswerIdentity.CharacterVersion("version-1"),
            ),
        )
        val secondCharacter = firstCharacter.apply(
            NovexConversationCommand.SetAnswerIdentity(
                AnswerIdentity.CharacterVersion("version-2"),
            ),
        )

        assertEquals(
            AnswerIdentity.CharacterVersion("version-2"),
            secondCharacter.snapshot.answerIdentity,
        )
    }

    @Test
    fun `activating another interactive fiction replaces the prior snapshot`() {
        val first = ActiveInteractiveFictionSnapshot(
            projectId = "game-1",
            snapshotId = "snapshot-1",
            title = "云岚试炼",
        )
        val second = ActiveInteractiveFictionSnapshot(
            projectId = "game-2",
            snapshotId = "snapshot-2",
            title = "星海远征",
        )

        val configured = NovexConversationConfiguration.empty("conversation-1")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(first))
            .apply(
                NovexConversationCommand.SetPlaythroughValue(
                    branchId = "main",
                    key = "health",
                    value = PlaythroughValue.Number(100.0),
                ),
            )
            .apply(NovexConversationCommand.ActivateInteractiveFiction(second))

        assertEquals(second, configured.snapshot.activeInteractiveFiction)
        assertTrue(configured.snapshot.playthroughStates.isEmpty())
    }

    @Test
    fun `forked playthrough state is copied once and then changes independently per branch`() {
        val game = ActiveInteractiveFictionSnapshot(
            projectId = "game-1",
            snapshotId = "snapshot-1",
            title = "云岚试炼",
        )
        val configured = NovexConversationConfiguration.empty("conversation-1")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(game))
            .apply(
                NovexConversationCommand.SetPlaythroughValue(
                    branchId = "main",
                    key = "health",
                    value = PlaythroughValue.Number(100.0),
                ),
            )
            .apply(NovexConversationCommand.ForkPlaythroughState("main", "alternate"))
            .apply(
                NovexConversationCommand.SetPlaythroughValue(
                    branchId = "alternate",
                    key = "health",
                    value = PlaythroughValue.Number(20.0),
                ),
            )

        assertEquals(
            PlaythroughValue.Number(100.0),
            configured.snapshot.playthroughStates.getValue("main").values.getValue("health"),
        )
        assertEquals(
            PlaythroughValue.Number(20.0),
            configured.snapshot.playthroughStates.getValue("alternate").values.getValue("health"),
        )
    }

    @Test
    fun `conversation controls share one ordered collection across preset AI and user sources`() {
        val health = ConversationControlDefinition(
            id = "health",
            label = "查看血量",
            behavior = ConversationControlBehavior.VIEW,
            source = ConversationControlSource.PROJECT_PRESET,
            actionKey = "show_health",
        )
        val attack = ConversationControlDefinition(
            id = "attack",
            label = "发动攻击",
            behavior = ConversationControlBehavior.ACTION,
            source = ConversationControlSource.AI,
            actionKey = "attack",
        )

        val configured = NovexConversationConfiguration.empty("conversation-1")
            .apply(NovexConversationCommand.UpsertControl(health))
            .apply(NovexConversationCommand.UpsertControl(attack))
            .apply(NovexConversationCommand.MoveControl("attack", 0))

        assertEquals(listOf("attack", "health"), configured.snapshot.controls.map { it.id })
        assertEquals(
            ConversationControlBehavior.ACTION,
            configured.snapshot.controls.first().behavior,
        )
    }

    @Test
    fun `stored configuration reopens through the same seam and rejects duplicate relationships`() {
        val world = NovexContentAddress.world("world-1")
        val stored = NovexConversationConfiguration.empty("conversation-1")
            .apply(NovexConversationCommand.AddBackground(world))
            .apply(NovexConversationCommand.MountSubject(world, ManagedAccess.READ_ONLY))
            .snapshot

        assertEquals(stored, NovexConversationConfiguration.open(stored).snapshot)
        assertThrows(IllegalArgumentException::class.java) {
            NovexConversationConfiguration.open(
                stored.copy(
                    backgroundSettings = listOf(
                        BackgroundSetting(world),
                        BackgroundSetting(world),
                    ),
                ),
            )
        }
    }
}
