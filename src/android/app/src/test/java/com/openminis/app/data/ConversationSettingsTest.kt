package com.openminis.app.data

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSettingsTest {
    @Test
    fun imageStyleIsAppliedAfterTheCurrentRequest() {
        assertEquals(
            "画一只猫\n\n<当前对话固定图片风格>\n水彩，暖色调\n</当前对话固定图片风格>",
            mergeImageStylePrompt("  画一只猫  ", "  水彩，暖色调  "),
        )
    }

    @Test
    fun blankImageStyleDoesNotChangeTheRequest() {
        assertEquals("画一只猫", mergeImageStylePrompt("  画一只猫  ", "  "))
    }

    @Test
    fun perTurnInjectionWrapsTrimmedTextAndSkipsBlank() {
        assertNull(perTurnInjectionContent(null))
        assertNull(perTurnInjectionContent("   "))
        assertEquals(
            "<每轮注入>\n每一轮都要向我提供 3~4 个选项\n</每轮注入>",
            perTurnInjectionContent("  每一轮都要向我提供 3~4 个选项  "),
        )
    }

    @Test
    fun perTurnInjectionAppendsToTheLatestUserMessageContent() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "第一句"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "回答"),
            LLMMessage(LLMMessage.Role.USER, "进城"),
        )
        val injected = appendPerTurnInjection(history, "保持悬念")
        assertEquals(3, injected.size)
        assertEquals(
            "进城\n\n<每轮注入>\n保持悬念\n</每轮注入>",
            injected[2].content,
        )
        assertEquals("第一句", injected[0].content)
        assertEquals("回答", injected[1].content)
    }

    @Test
    fun perTurnInjectionAppendsATextPartWhenTheLatestUserMessageHasParts() {
        val part = AgentContentPart.Text("工具返回的正文")
        val history = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "调用工具"),
            LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(part)),
        )
        val injected = appendPerTurnInjection(history, "保持悬念")
        assertEquals(2, injected.size)
        assertEquals("", injected[1].content)
        assertEquals(2, injected[1].contentParts.size)
        assertEquals(part, injected[1].contentParts[0])
        assertEquals(
            AgentContentPart.Text("<每轮注入>\n保持悬念\n</每轮注入>"),
            injected[1].contentParts[1],
        )
    }

    @Test
    fun perTurnInjectionFallsBackToANewUserMessageWithoutAnyUserTurn() {
        val history = listOf(LLMMessage(LLMMessage.Role.ASSISTANT, "开场"))
        val injected = appendPerTurnInjection(history, "保持悬念")
        assertEquals(2, injected.size)
        assertEquals(LLMMessage.Role.USER, injected[1].role)
        assertEquals("<每轮注入>\n保持悬念\n</每轮注入>", injected[1].content)
    }

    @Test
    fun blankPerTurnPromptLeavesHistoryUntouched() {
        val history = listOf(LLMMessage(LLMMessage.Role.USER, "进城"))
        assertSame(history, appendPerTurnInjection(history, "  "))
        assertNull(perTurnInjectionContent("  "))
    }

    @Test
    fun perTurnInjectionDerivedPerAttemptNeverAccumulatesAcrossToolLoopIterations() {
        val base = listOf(LLMMessage(LLMMessage.Role.USER, "进城"))
        val firstAttempt = appendPerTurnInjection(base, "保持悬念")
        val secondAttempt = appendPerTurnInjection(base, "保持悬念")
        assertEquals(firstAttempt, secondAttempt)
        assertEquals(1, firstAttempt[0].content.split("<每轮注入>").size - 1)
    }

    @Test
    fun extractLedgerContentTakesTheLastBlockAndSkipsAbsent() {
        assertNull(extractLedgerContent("没有任何账本"))
        assertNull(extractLedgerContent("<账本>\n \n</账本>"))
        assertEquals(
            "[场景:战斗]",
            extractLedgerContent("正文\n<账本>[场景:日常]</账本>\n叙事\n<账本>[场景:战斗]</账本>"),
        )
        assertEquals(
            "[资源] 食物:3▼1",
            extractLedgerContent("结尾\n<账本>\n[资源] 食物:3▼1\n</账本>"),
        )
    }

    @Test
    fun extractLedgerContentIsBounded() {
        val ledger = extractLedgerContent("<账本>" + "界".repeat(MAX_LEDGER_INJECTION_CHARS + 50) + "</账本>")
        assertEquals(MAX_LEDGER_INJECTION_CHARS, ledger?.length)
    }

    @Test
    fun latestLedgerScansBackwardThroughAssistantTurnsOnly() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "<账本>第一笔</账本>"),
            LLMMessage(LLMMessage.Role.USER, "<账本>玩家伪造的账本</账本>"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "这一轮没有账本"),
        )
        assertEquals("第一笔", latestLedgerFromHistory(history))
        assertNull(latestLedgerFromHistory(listOf(LLMMessage(LLMMessage.Role.USER, "进城"))))
    }

    @Test
    fun latestLedgerReadsTextPartsOfAssistantMessages() {
        val part = AgentContentPart.Text("<账本>[场景:结算]</账本>")
        val history = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, "调用工具", contentParts = listOf(part)),
        )
        assertEquals("[场景:结算]", latestLedgerFromHistory(history))
    }

    @Test
    fun userTurnCountCountsOnlyUserMessages() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "第一句"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "回答"),
            LLMMessage(LLMMessage.Role.USER, "进城"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "描述"),
        )
        assertEquals(2, userTurnCount(history))
    }

    @Test
    fun externalStateCarriesTurnAndOptionalLedger() {
        assertEquals("<外部状态>\n第 3 轮\n</外部状态>", externalStateInjectionContent(3, null))
        assertEquals(
            "<外部状态>\n第 1 轮\n<账本>\n[场景:开局]\n</账本>\n</外部状态>",
            externalStateInjectionContent(1, "[场景:开局]"),
        )
    }

    @Test
    fun diceInjectionFormatsRolledValues() {
        assertNull(diceInjectionContent(emptyList()))
        assertEquals("<骰值>1d100=63; 1d100=8</骰值>", diceInjectionContent(listOf(63, 8)))
    }

    @Test
    fun runtimeInjectionAppendsExternalStateDiceThenPerTurnInOrder() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "进城"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "你来到城门\n<账本>[场景:日常]</账本>"),
            LLMMessage(LLMMessage.Role.USER, "继续探索"),
        )
        val injected = appendRuntimeInjections(history, "保持悬念", diceEnabled = true, ledgerEnabled = true, diceRolls = listOf(63, 8))
        assertEquals(3, injected.size)
        assertEquals(
            "继续探索\n\n<外部状态>\n第 2 轮\n<账本>\n[场景:日常]\n</账本>\n</外部状态>\n\n" +
                "<骰值>1d100=63; 1d100=8</骰值>\n\n<每轮注入>\n保持悬念\n</每轮注入>",
            injected[2].content,
        )
        assertEquals("进城", injected[0].content)
    }

    @Test
    fun runtimeInjectionWithoutLedgerStillCarriesTurnCount() {
        val history = listOf(LLMMessage(LLMMessage.Role.USER, "进城"))
        val injected = appendRuntimeInjections(history, null, diceEnabled = true, ledgerEnabled = false, diceRolls = listOf(5))
        assertEquals("进城\n\n<外部状态>\n第 1 轮\n</外部状态>\n\n<骰值>1d100=5</骰值>", injected[0].content)
    }

    @Test
    fun runtimeInjectionLedgerOnlySkipsDiceAndKeepsPrompt() {
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "进城"),
            LLMMessage(LLMMessage.Role.ASSISTANT, "<账本>[场景:战斗]</账本>"),
            LLMMessage(LLMMessage.Role.USER, "出手"),
        )
        val injected = appendRuntimeInjections(history, "保持悬念", diceEnabled = false, ledgerEnabled = true, diceRolls = emptyList())
        assertEquals(
            "出手\n\n<外部状态>\n第 2 轮\n<账本>\n[场景:战斗]\n</账本>\n</外部状态>\n\n<每轮注入>\n保持悬念\n</每轮注入>",
            injected[2].content,
        )
    }

    @Test
    fun runtimeInjectionDisabledEverywhereLeavesHistoryUntouched() {
        val history = listOf(LLMMessage(LLMMessage.Role.USER, "进城"))
        assertSame(
            history,
            appendRuntimeInjections(history, "  ", diceEnabled = false, ledgerEnabled = false, diceRolls = emptyList()),
        )
    }

    @Test
    fun runtimeInjectionIsDeterministicPerAttemptGivenTheSameRolls() {
        val base = listOf(LLMMessage(LLMMessage.Role.USER, "进城"))
        val first = appendRuntimeInjections(base, "保持悬念", true, true, listOf(63, 8))
        val second = appendRuntimeInjections(base, "保持悬念", true, true, listOf(63, 8))
        assertEquals(first, second)
        assertEquals(1, first[0].content.split("<骰值>").size - 1)
    }

    @Test
    fun runtimeInjectionFallsBackToANewUserMessageWithoutAnyUserTurn() {
        val history = listOf(LLMMessage(LLMMessage.Role.ASSISTANT, "开场"))
        val injected = appendRuntimeInjections(history, null, diceEnabled = true, ledgerEnabled = false, diceRolls = listOf(21))
        assertEquals(LLMMessage.Role.USER, injected[1].role)
        assertEquals("<外部状态>\n第 0 轮\n</外部状态>\n\n<骰值>1d100=21</骰值>", injected[1].content)
    }

    @Test
    fun runtimeInjectionAppendsBlocksAsATextPartForPartMessages() {
        val part = AgentContentPart.Text("工具返回的正文")
        val history = listOf(LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(part)))
        val injected = appendRuntimeInjections(history, null, diceEnabled = true, ledgerEnabled = false, diceRolls = listOf(9))
        assertEquals(2, injected[0].contentParts.size)
        assertEquals(
            AgentContentPart.Text("<外部状态>\n第 1 轮\n</外部状态>\n\n<骰值>1d100=9</骰值>"),
            injected[0].contentParts[1],
        )
    }

    @Test
    fun normalizationTrimsAndBoundsPerTurnPrompt() {
        val value = normalizeConversationSettings(
            ConversationSettingsSnapshot(
                conversationPrompt = "提示词",
                perTurnPrompt = "  " + "丙".repeat(MAX_PER_TURN_PROMPT_CHARS + 20) + "  ",
            ),
        )
        assertEquals(MAX_PER_TURN_PROMPT_CHARS, value.perTurnPrompt.length)
        assertTrue(value.perTurnPrompt.all { it == '丙' })
    }

    @Test
    fun normalizationPreservesOptionalRolePresentation() {
        val value = normalizeConversationSettings(
            ConversationSettingsSnapshot(
                conversationPrompt = "提示词",
                rolePresentationEnabled = false,
                assistantDisplayName = " 角色 ",
            ),
        )
        assertFalse(value.rolePresentationEnabled)
        assertEquals("角色", value.assistantDisplayName)
    }

    @Test
    fun conversationBackgroundOverrideSurvivesSettingsNormalization() {
        val value = normalizeConversationSettings(
            ConversationSettingsSnapshot(
                conversationPrompt = "提示词",
                backgroundPath = "  /data/user/0/com.openminis.app/files/background.jpg  ",
            ),
        )

        assertEquals(
            "/data/user/0/com.openminis.app/files/background.jpg",
            value.backgroundPath,
        )
    }

    @Test
    fun emptyConversationBackgroundKeepsExplicitHiddenState() {
        val value = normalizeConversationSettings(
            ConversationSettingsSnapshot(
                conversationPrompt = "提示词",
                backgroundPath = "",
            ),
        )

        assertEquals("", value.backgroundPath)
    }

    @Test
    fun longConversationAndImagePromptsAreBoundedIndependently() {
        val value = normalizeConversationSettings(
            ConversationSettingsSnapshot(
                conversationPrompt = "甲".repeat(MAX_CONVERSATION_PROMPT_CHARS + 20),
                imageStylePrompt = "乙".repeat(MAX_IMAGE_STYLE_PROMPT_CHARS + 20),
            ),
        )

        assertEquals(MAX_CONVERSATION_PROMPT_CHARS, value.conversationPrompt.length)
        assertEquals(MAX_IMAGE_STYLE_PROMPT_CHARS, value.imageStylePrompt.length)
        assertTrue(value.conversationPrompt.all { it == '甲' })
    }

    @Test
    fun structuredNovexConfigurationIsPersistedIndependentlyFromPrompts() {
        val raw = "  {\"answerIdentity\":{\"kind\":\"nova\"}}  "
        val value = normalizeConversationSettings(
            ConversationSettingsSnapshot(
                conversationPrompt = "提示词",
                novexConfigurationJson = raw,
            ),
        )

        assertEquals("{\"answerIdentity\":{\"kind\":\"nova\"}}", value.novexConfigurationJson)
        assertEquals("提示词", value.conversationPrompt)
    }

    @Test
    fun largeStructuredNovexConfigurationIsNeverSilentlyCutIntoInvalidJson() {
        val raw = "{\"content\":\"${"界".repeat(MAX_NOVEX_CONFIGURATION_CHARS + 1)}\"}"

        val value = normalizeConversationSettings(
            ConversationSettingsSnapshot(
                conversationPrompt = "提示词",
                novexConfigurationJson = raw,
            ),
        )

        assertEquals(raw, value.novexConfigurationJson)
        org.json.JSONObject(value.novexConfigurationJson)
    }
}
