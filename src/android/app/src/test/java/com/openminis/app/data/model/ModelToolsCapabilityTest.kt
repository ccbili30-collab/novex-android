package com.openminis.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelToolsCapabilityTest {
    @Test
    fun `tool capability override survives persistence and applies to effective model`() {
        val entry = ModelEntry(
            providerInstanceId = "provider",
            baseModel = LLMModel("gemini", "Gemini", "relay"),
            overrides = ModelOverrides(supportsTools = false),
        )

        val encoded = Json.encodeToString(ModelEntry.serializer(), entry)
        val restored = Json.decodeFromString(ModelEntry.serializer(), encoded)

        assertFalse(restored.model.supportsTools ?: true)
        assertFalse(restored.overrides.isEmpty)
    }

    @Test
    fun `models without an explicit override keep tools enabled`() {
        val entry = ModelEntry(
            providerInstanceId = "provider",
            baseModel = LLMModel("ordinary", "Ordinary", "relay"),
        )

        assertTrue(entry.model.supportsTools != false)
    }
}
