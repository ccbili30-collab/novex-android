package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
