package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexConversationConfigurationTest {
    @Test
    fun `a started or ended game protects its conversation even before the first message`() {
        val empty = NovexConversationConfiguration.empty("chat")
        assertEquals(false, empty.snapshot.hasPersistentConfiguration)
        val started = empty.apply(NovexConversationCommand.ActivateInteractiveFiction(
            ActiveInteractiveFictionSnapshot("game", "v1", "生生"),
        ))
        assertTrue(started.snapshot.hasPersistentConfiguration)
        assertTrue(started.apply(NovexConversationCommand.DeactivateInteractiveFiction).snapshot.hasPersistentConfiguration)
    }

    @Test
    fun `a delayed end cannot stop a newly started playthrough of the same game`() {
        val game = ActiveInteractiveFictionSnapshot("game", "v1", "生生")
        val first = NovexConversationConfiguration.empty("chat")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(game))
        val firstRun = first.snapshot.activePlaythroughId!!
        val restarted = first.apply(NovexConversationCommand.EndInteractiveFiction(firstRun))
            .apply(NovexConversationCommand.ActivateInteractiveFiction(game))
        assertThrows(IllegalArgumentException::class.java) {
            restarted.apply(NovexConversationCommand.EndInteractiveFiction(firstRun))
        }
        assertEquals(game, restarted.snapshot.activeInteractiveFiction)
        val saved = NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(restarted.snapshot), "chat")
        assertEquals(restarted.snapshot.activePlaythroughId, saved.activePlaythroughId)
    }

    @Test
    fun `a games explicit answer identity survives persistence and is selected on activation`() {
        val actor = AnswerIdentity.CharacterVersion("fusheng-stage-adult")
        val game = ActiveInteractiveFictionSnapshot("game", "v1", "生生", answerIdentity = actor)
        val started = NovexConversationConfiguration.empty("chat")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(game)).snapshot
        val restored = NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(started), "chat")
        assertEquals(actor, restored.answerIdentity)
        assertEquals(actor, restored.activeInteractiveFiction?.answerIdentity)
    }

    @Test
    fun `starting another game retains ended state and registered controls in saved history`() {
        val first = ActiveInteractiveFictionSnapshot("game", "v1", "生生")
        val oldControl = ConversationControlDefinition("status", "查看声望", ConversationControlBehavior.VIEW,
            ConversationControlSource.AI, "status")
        val ended = NovexConversationConfiguration.empty("chat")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(first))
            .apply(NovexConversationCommand.SetPlaythroughValue("branch", "声望", PlaythroughValue.Number(8.0)))
            .apply(NovexConversationCommand.UpsertControl(oldControl))
            .apply(NovexConversationCommand.DeactivateInteractiveFiction)
        val next = ended.apply(NovexConversationCommand.ActivateInteractiveFiction(
            ActiveInteractiveFictionSnapshot("other", "v2", "新局"),
        )).snapshot
        val saved = NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(next), "chat")
        val previous = saved.completedPlaythroughs.single()
        assertEquals(first, previous.game)
        assertEquals(PlaythroughValue.Number(8.0), previous.states["branch"]?.values?.get("声望"))
        assertEquals(listOf(oldControl), previous.controls)
        assertTrue(saved.playthroughStates.isEmpty())
        assertTrue(saved.controls.none { it.id == oldControl.id })
    }

    @Test
    fun `a game requires explicit player replacement and restores the previous player after reload`() {
        val previous = ConversationPlayerIdentity("traveler", "旅人", "来自远方")
        val companion = ConversationPlayerIdentity("scribe", "记言人", "记录帝议")
        val before = NovexConversationConfiguration.empty("chat")
            .apply(NovexConversationCommand.SetPlayerIdentity(previous))
        val game = ActiveInteractiveFictionSnapshot("game", "v1", "生生", playerIdentity = companion)
        assertThrows(IllegalArgumentException::class.java) {
            before.apply(NovexConversationCommand.ActivateInteractiveFiction(game))
        }
        val started = before.apply(NovexConversationCommand.ActivateInteractiveFiction(game, replacePlayerIdentity = true))
        assertEquals(companion, started.snapshot.playerIdentity)
        val ended = NovexConversationConfiguration.open(NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(started.snapshot), "chat",
        )).apply(NovexConversationCommand.DeactivateInteractiveFiction)
        assertEquals(previous, ended.snapshot.playerIdentity)
        assertEquals(AnswerIdentity.Nova, ended.snapshot.answerIdentity)
    }

    @Test
    fun `ending a saved game restores the prior identity and preserves branch state`() {
        val creator = AnswerIdentity.PersonaPreset("creator", "共创者", "协助整理制度设定")
        val started = NovexConversationConfiguration.empty("chat")
            .apply(NovexConversationCommand.SetAnswerIdentity(creator))
            .apply(NovexConversationCommand.ActivateInteractiveFiction(
                ActiveInteractiveFictionSnapshot("game", "v1", "生生"),
            ))
            .apply(NovexConversationCommand.SetPlaythroughValue("branch", "声望", PlaythroughValue.Number(8.0)))
            .apply(NovexConversationCommand.SetAnswerIdentity(AnswerIdentity.CharacterVersion("伏生")))
        val restored = NovexConversationConfiguration.open(NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(started.snapshot), "chat",
        )).apply(NovexConversationCommand.DeactivateInteractiveFiction)

        assertEquals(creator, restored.snapshot.answerIdentity)
        assertEquals(started.snapshot.playthroughStates, restored.snapshot.playthroughStates)
        assertEquals(null, restored.snapshot.activeInteractiveFiction)
        assertEquals(restored.snapshot, restored.apply(NovexConversationCommand.DeactivateInteractiveFiction).snapshot)
    }

    @Test
    fun `starting a game without an identity selects a game host without granting management access`() {
        val started = NovexConversationConfiguration.empty("conversation-1")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(
                ActiveInteractiveFictionSnapshot("game-1", "snapshot-1", "生生"),
            )).snapshot

        assertTrue(started.answerIdentity is AnswerIdentity.PersonaPreset)
        assertEquals("游戏主持人", (started.answerIdentity as AnswerIdentity.PersonaPreset).label)
        assertTrue(started.managedSubjects.isEmpty())
        assertTrue(started.backgroundSettings.isEmpty())
    }

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
