package com.openminis.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-qianchen-preset] 前尘预设新增的 ProviderInstance 字段（keyHelpUrl /
 * autoResponsesFallback）的序列化守护：新字段往返不丢，旧数据（无新字段的
 * JSON）解码回落默认值——升级不破坏既有用户配置。
 */
class QianchenPresetFieldsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun presetFieldsSurviveRoundTrip() {
        val instance = ProviderInstance(
            id = "builtin-qianchen-relay",
            label = "前尘 API",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://proxy.qianc.ltd",
            appendV1Suffix = true,
            keyHelpUrl = "https://proxy.qianc.ltd",
            autoResponsesFallback = true,
        )
        val decoded = json.decodeFromString(
            ProviderInstance.serializer(),
            json.encodeToString(ProviderInstance.serializer(), instance),
        )
        assertEquals("https://proxy.qianc.ltd", decoded.keyHelpUrl)
        assertTrue(decoded.autoResponsesFallback)
        assertTrue(decoded.useResponsesAPI.not()) // 默认 chat，responses 只作回退
    }

    @Test fun legacyJsonWithoutPresetFieldsDecodesToDefaults() {
        val legacy = json.decodeFromString(
            ProviderInstance.serializer(),
            """{"id":"old","label":"OpenAI","providerType":"openAI","credentialType":"apiKey"}""",
        )
        assertNull(legacy.keyHelpUrl)
        assertFalse(legacy.autoResponsesFallback)
    }
}
