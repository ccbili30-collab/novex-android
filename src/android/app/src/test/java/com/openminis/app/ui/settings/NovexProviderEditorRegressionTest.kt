package com.openminis.app.ui.settings

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the two terminal actions that were accidentally lost when provider
 * details were redirected to the simplified Novex connection editor.
 *
 * Compose UI tests are not part of the JVM test source set, so this contract
 * deliberately checks the real screen wiring instead of duplicating its state
 * in a shallow helper that could stay green while the controls disappear.
 */
class NovexProviderEditorRegressionTest {
    private val source by lazy {
        File("src/main/java/com/openminis/app/ui/settings/NovexProviderSetupScreen.kt").readText()
    }

    @Test
    fun `simplified connection editor exposes and persists the v1 suffix switch`() {
        assertTrue(source.contains("var appendV1Suffix by remember"))
        assertTrue(source.contains("checked = appendV1Suffix"))
        assertTrue(source.contains("appendV1Suffix = appendV1Suffix"))
    }

    @Test
    fun `existing provider can be deleted from the simplified editor`() {
        assertTrue(source.contains("var deleteConfirm by remember"))
        assertTrue(source.contains("providerRepository.removeInstance(existing.id)"))
    }

    @Test
    fun `disabled suffix leaves the configured base address unchanged`() {
        assertEquals(
            "https://relay.example/api",
            novexCanonicalBase(" https://relay.example/api/ ", appendV1Suffix = false),
        )
    }

    @Test
    fun `enabled suffix adds exactly one v1 segment`() {
        assertEquals(
            "https://relay.example/v1",
            novexCanonicalBase("https://relay.example/", appendV1Suffix = true),
        )
        assertEquals(
            "https://relay.example/v1",
            novexCanonicalBase("https://relay.example/v1/", appendV1Suffix = true),
        )
    }

    @Test
    fun `saving an existing provider preserves an explicitly disabled suffix`() {
        val existing = ProviderInstance(
            id = "relay",
            label = "旧名称",
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://old.example",
            appendV1Suffix = true,
        )

        val saved = novexProviderInstanceForSave(
            existing = existing,
            label = "新名称",
            base = "https://relay.example/api/",
            appendV1Suffix = false,
        )

        assertEquals("relay", saved.id)
        assertEquals("https://relay.example/api", saved.customBaseURL)
        assertFalse(saved.appendV1Suffix)
        assertEquals("https://relay.example/api", saved.effectiveBaseURL)
    }

    @Test
    fun `image source editor keeps the same toggle and delete actions`() {
        val imageSource = File(
            "src/main/java/com/openminis/app/ui/settings/ImageGenerationSettingsScreen.kt",
        ).readText()
        assertTrue(imageSource.contains("checked = appendV1Suffix"))
        assertTrue(imageSource.contains("providerRepository.removeInstance(currentId)"))
    }
}
