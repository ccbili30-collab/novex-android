package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic domain pressure cases; these do not impersonate five human testers. */
class NovexLongformPressureTest {
    private data class AcceptanceMatrix(
        val personas: List<String>,
        val automated: List<String>,
        val external: List<String>,
    )

    private val requiredCases = AcceptanceMatrix(
        personas = listOf("世界构筑者", "长篇作者", "角色扮演者", "文游玩家", "设定档案管理员"),
        automated = listOf("200K 词元容量策略", "百余模块检索", "卡片往返", "分支状态恢复"),
        external = listOf("五名内测者连续创作", "真实模型费用与延迟", "360 与 412 宽度真机交互"),
    )

    @Test
    fun twoHundredFiveThousandTokenConversationStillHasBoundedRoomForRelevantModulesOnAOneMillionModel() {
        val status = NovexLongformModelPolicy.evaluate(
            effectiveWindowTokens = 1_000_000,
            occupiedTokens = 205_000,
            reservedOutputTokens = 64_000,
        )
        val giantModule = buildString {
            repeat(8_000) { index ->
                append("第 $index 段是无关的旧史记录。\n\n")
            }
            append("白塔盟约的真正见证人是沈砚；第七枚印章藏在北塔钟后。")
        }
        val composition = NovexContextComposer.compose(
            query = "谁见证了白塔盟约，第七枚印章在哪里？",
            tokenBudget = status.moduleBudgetTokens,
            candidates = listOf(
                NovexContextCandidate(
                    sourceId = "oversized-history",
                    label = "雾港完整历史",
                    content = giantModule,
                    aliases = setOf("白塔盟约", "第七枚印章"),
                ),
            ),
        )

        assertEquals(NovexLongformModelTier.EXTENDED, status.tier)
        assertEquals(128_000, status.moduleBudgetTokens)
        assertTrue(composition.fragments.single().text.contains("沈砚"))
        assertTrue(composition.usedTokens <= status.moduleBudgetTokens)
    }

    @Test
    fun worldBuilderRecallsOneExactThreadFromMoreThanOneHundredStructuredModules() {
        val modules = (0 until 150).map { index ->
            NovexContextCandidate(
                sourceId = "module-$index",
                label = "设定条目 $index",
                content = if (index == 137) {
                    "赤潮历 412 年，雾港议会秘密签署白塔盟约。\n\n伏笔：失踪的第七枚印章仍在北塔。"
                } else {
                    "这是第 $index 项结构化背景资料，与雾港议会无关。"
                },
                aliases = if (index == 137) setOf("白塔盟约", "第七枚印章") else emptySet(),
                position = index,
            )
        }

        val result = NovexContextComposer.compose(
            query = "白塔盟约和第七枚印章有什么关系？",
            tokenBudget = 4_000,
            candidates = modules,
        )

        assertTrue(result.fragments.any { it.sourceId == "module-137" })
        assertFalse(result.fragments.any { it.sourceId == "module-12" })
        assertTrue(result.usedTokens <= 4_000)
    }

    @Test
    fun novelistCanUseSeveralWorldsAsBackgroundWhileManagingTheSameSubjects() {
        val worldA = NovexContentAddress.world("world-a")
        val worldB = NovexContentAddress.world("world-b")
        val character = NovexContentAddress.characterVersion("version-a")
        val game = NovexContentAddress.interactiveFiction("game-a")
        val configured = NovexConversationConfiguration.empty("chat-cross-world")
            .apply(NovexConversationCommand.AddBackground(worldA))
            .apply(NovexConversationCommand.AddBackground(worldB))
            .apply(NovexConversationCommand.AddBackground(character))
            .apply(NovexConversationCommand.MountSubject(worldA, ManagedAccess.EDIT))
            .apply(NovexConversationCommand.MountSubject(worldB, ManagedAccess.READ_ONLY))
            .apply(NovexConversationCommand.MountSubject(character, ManagedAccess.EDIT))
            .apply(NovexConversationCommand.MountSubject(game, ManagedAccess.EDIT))
            .snapshot

        assertEquals(listOf(worldA, worldB, character), configured.backgroundSettings.map { it.subject })
        assertEquals(listOf(worldA, worldB, character, game), configured.managedSubjects.map { it.subject })
        assertEquals(ManagedAccess.READ_ONLY, configured.managedSubjects[1].access)
    }

    @Test
    fun rolePlayerKeepsAnswerIdentitySeparateFromReferenceCharacters() {
        val performer = NovexContentAddress.characterVersion("performer")
        val reference = NovexContentAddress.characterVersion("reference")
        val configured = NovexConversationConfiguration.empty("chat-role")
            .apply(NovexConversationCommand.SetAnswerIdentity(AnswerIdentity.CharacterVersion(performer.id)))
            .apply(NovexConversationCommand.AddBackground(performer))
            .apply(NovexConversationCommand.AddBackground(reference))
            .snapshot

        assertEquals(AnswerIdentity.CharacterVersion("performer"), configured.answerIdentity)
        assertEquals(listOf(performer, reference), configured.backgroundSettings.map { it.subject })
    }

    @Test
    fun gamePlayerStateAndControlsSurvivePersistenceAndForkWithoutCrossingBranches() {
        val status = ConversationControlDefinition(
            id = "status",
            label = "角色档案",
            behavior = ConversationControlBehavior.VIEW,
            source = ConversationControlSource.PROJECT_PRESET,
            actionKey = "game.status",
        )
        val active = ActiveInteractiveFictionSnapshot(
            projectId = "game-1",
            snapshotId = "snapshot-1",
            title = "雾港漫游",
            presetControls = listOf(status),
        )
        val branchA = NovexConversationConfiguration.empty("chat-game")
            .apply(NovexConversationCommand.ActivateInteractiveFiction(active))
            .apply(NovexConversationCommand.SetPlaythroughValue("reply-a", "生命", PlaythroughValue.Number(72.0)))
            .apply(NovexConversationCommand.ForkPlaythroughState("reply-a", "reply-b"))
            .apply(NovexConversationCommand.SetPlaythroughValue("reply-b", "生命", PlaythroughValue.Number(31.0)))
            .snapshot
        val restored = NovexConversationConfigurationCodec.decode(
            NovexConversationConfigurationCodec.encode(branchA),
            "chat-game",
        )

        assertEquals(PlaythroughValue.Number(72.0), restored.playthroughStates["reply-a"]?.values?.get("生命"))
        assertEquals(PlaythroughValue.Number(31.0), restored.playthroughStates["reply-b"]?.values?.get("生命"))
        assertEquals(listOf("status"), restored.controls.map { it.id })
    }

    @Test
    fun deepCreatorPressureContractSeparatesAutomatedAndExternalEvidence() {
        val matrix = requiredCases

        assertEquals(5, matrix.personas.size)
        assertTrue(matrix.automated.any { it.contains("200K") })
        assertTrue(matrix.automated.any { it.contains("卡片往返") })
        assertTrue(matrix.external.any { it.contains("五名") })
        assertTrue(matrix.external.any { it.contains("真实模型") })
        assertTrue(matrix.external.any { it.contains("360") && it.contains("412") })
    }
}
