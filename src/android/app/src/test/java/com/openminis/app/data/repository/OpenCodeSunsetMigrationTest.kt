package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-opencode-sunset] 下线迁移守护：禁用两个内置免费实例并隐藏其模型
 * 条目（数据不删、可逆）、幂等（第二遍零变更）、其他实例/条目不受扰。
 * 依据：OpenCode Zen 免费档 2025-09 起服务端 403 第三方调用（实测
 * UA+四头伪造不通），拥有者拍板直接下线。
 */
class OpenCodeSunsetMigrationTest {

    private fun freeInstance(id: String, enabled: Boolean = true) = ProviderInstance(
        id = id,
        label = "OpenCode Zen",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = enabled,
        createdAt = 1L,
        customBaseURL = "https://opencode.ai/zen/v1",
        appendV1Suffix = false,
        useResponsesAPI = id.endsWith("responses"),
    )

    private fun entry(instanceId: String, id: String, hidden: Boolean = false) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(id = id, displayName = id, provider = "OpenCode Zen"),
        overrides = ModelOverrides(),
        isCustom = false,
        isHidden = hidden,
        uuid = id,
        userModifiedAt = null,
    )

    @Test
    fun `disables instances and hides entries, keeps data reversible`() {
        val config = ProviderConfig(
            instances = mutableListOf(
                freeInstance("builtin-opencode-free-chat"),
                freeInstance("builtin-opencode-free-responses"),
                freeInstance("custom-relay", enabled = true).copy(label = "my relay"),
            ),
            modelEntries = mutableListOf(
                entry("builtin-opencode-free-chat", "mimo-v2.5-free"),
                entry("builtin-opencode-free-responses", "muse-spark-1.2-contributor-free", hidden = true),
                entry("custom-relay", "gpt-5"),
            ),
        )
        assertTrue(applyOpenCodeSunset(config))
        // 实例禁用但保留；普通供应商实例原样
        assertFalse(config.instances.first { it.id == "builtin-opencode-free-chat" }.isEnabled)
        assertFalse(config.instances.first { it.id == "builtin-opencode-free-responses" }.isEnabled)
        assertTrue(config.instances.first { it.id == "custom-relay" }.isEnabled)
        // 条目隐藏但保留；本就隐藏的不重复改；其他条目不受扰
        assertTrue(config.modelEntries.first { it.uuid == "mimo-v2.5-free" }.isHidden)
        assertTrue(config.modelEntries.first { it.uuid == "gpt-5" }.isHidden.not())
        assertEquals(3, config.modelEntries.size)
    }

    @Test
    fun `idempotent - second pass reports no change`() {
        val config = ProviderConfig(
            instances = mutableListOf(freeInstance("builtin-opencode-free-chat")),
            modelEntries = mutableListOf(entry("builtin-opencode-free-chat", "big-pickle")),
        )
        assertTrue(applyOpenCodeSunset(config))
        assertFalse(applyOpenCodeSunset(config))
    }

    @Test
    fun `config without opencode instances is untouched`() {
        val config = ProviderConfig(
            instances = mutableListOf(freeInstance("custom-relay").copy(label = "my relay")),
            modelEntries = mutableListOf(entry("custom-relay", "gpt-5")),
        )
        assertFalse(applyOpenCodeSunset(config))
        assertTrue(config.instances.single().isEnabled)
    }
}
