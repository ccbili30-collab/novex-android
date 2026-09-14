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
