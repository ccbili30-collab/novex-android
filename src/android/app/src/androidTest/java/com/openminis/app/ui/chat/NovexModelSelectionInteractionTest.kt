package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.model.*
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexModelSelectionInteractionTest {
    @get:Rule val ui = createComposeRule()

    @Test fun nonTextNeedsConfirmationAndTextSelectionClosesAfterBinding() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val provider = ProviderInstance("picker-fixture", "Picker fixture", ProviderType.openAI, ProviderCredential.apiKey)
        val image = ModelEntry(provider.id, LLMModel("image-fixture", "Image fixture", "openai", outputModalities = listOf("image")), isCustom = true)
        val text = ModelEntry(provider.id, LLMModel("text-fixture", "Text fixture", "openai"), isCustom = true)
        var config by mutableStateOf(ProviderConfig(instances = mutableListOf(provider), modelEntries = mutableListOf(image)))
        var visible by mutableStateOf(true)
        val actions = mutableListOf<String>()
        ui.setContent { MinisTheme(darkTheme = false) {
            if (visible) ChatModelSelectionSheet(emptyList(), null, null, config, app.providerRepository,
                onSelectGroup = { actions.add("group:$it") },
                onSelectGroupEntry = { group, entry -> actions.add("group:$group:$entry") },
                onSelectEntry = { actions.add("entry:$it") },
                onDismiss = { actions.add("dismiss"); visible = false },
                onEditGroups = { actions.add("edit") })
        } }
        fun click(label: String) {
            ui.waitUntil(15_000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(label).performTouchInput { click() }
        }
        click("Image fixture")
        click(app.getString(R.string.model_picker_non_text_warning_choose_other))
        ui.runOnIdle { assertTrue(visible); assertTrue(actions.isEmpty()) }
        click("Image fixture")
        click(app.getString(R.string.model_picker_non_text_warning_use_anyway))
        ui.runOnIdle {
            assertEquals(listOf("entry:${image.id}", "dismiss"), actions)
            actions.clear()
            config = ProviderConfig(instances = mutableListOf(provider), modelEntries = mutableListOf(text))
            visible = true
        }
        click("Text fixture")
        ui.runOnIdle { assertEquals(listOf("entry:${text.id}", "dismiss"), actions); assertFalse(visible) }
    }
}
