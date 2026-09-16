package com.openminis.app.ui.settings

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-direction] 模型连接页「接口方向」的映射守护：
 * 方向 → 实例字段（providerType / useResponsesAPI）与编辑反显
 * （novexDirectionOf）必须互为逆映射；改预设字段（keyHelpUrl /
 * autoResponsesFallback）不能被换向保存冲掉。
 */
class NovexProviderDirectionTest {
    @Test fun directionToInstanceMapping() {
        val chat = novexProviderInstanceForSave(null, "", "https://relay.example", true, NovexProviderDirection.CHAT)
        assertEquals(ProviderType.openAI, chat.providerType)
        assertFalse(chat.useResponsesAPI)

        val responses = novexProviderInstanceForSave(null, "", "https://relay.example", true, NovexProviderDirection.RESPONSES)
        assertEquals(ProviderType.openAI, responses.providerType)
        assertTrue(responses.useResponsesAPI)

        val anthropic = novexProviderInstanceForSave(null, "", "https://relay.example", true, NovexProviderDirection.ANTHROPIC)
        assertEquals(ProviderType.anthropic, anthropic.providerType)
        assertFalse(anthropic.useResponsesAPI)
    }

    @Test fun directionRoundTripThroughSavedInstance() {
        NovexProviderDirection.values().forEach { direction ->
            val saved = novexProviderInstanceForSave(null, "relay", "https://relay.example", true, direction)
            assertEquals(direction, novexDirectionOf(saved))
        }
        // 没有实例（新建页面初始态）默认 Chat。
        assertEquals(NovexProviderDirection.CHAT, novexDirectionOf(null))
    }

    @Test fun defaultParameterKeepsLegacyChatBehavior() {
        val legacy = novexProviderInstanceForSave(null, "relay", "https://relay.example", true)
        assertEquals(ProviderType.openAI, legacy.providerType)
        assertFalse(legacy.useResponsesAPI)
        assertEquals(NovexProviderDirection.CHAT, novexDirectionOf(legacy))
    }

    @Test fun switchingDirectionOnExistingInstancePreservesPresetFields() {
        val preset = ProviderInstance(
            id = "builtin-qianchen-relay",
            label = "前尘 API",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://proxy.qianc.ltd",
            appendV1Suffix = true,
            keyHelpUrl = "https://proxy.qianc.ltd",
            autoResponsesFallback = true,
        )
        val switched = novexProviderInstanceForSave(preset, "前尘 API", "https://proxy.qianc.ltd", true, NovexProviderDirection.RESPONSES)
        assertEquals("builtin-qianchen-relay", switched.id)
        assertEquals("https://proxy.qianc.ltd", switched.keyHelpUrl)
        assertTrue(switched.autoResponsesFallback)
        assertTrue(switched.useResponsesAPI)
    }
}
