package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
