package com.openminis.app.ui.settings

import java.io.File
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
}
