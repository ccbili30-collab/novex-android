package com.openminis.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.novex.domain.*
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.model.*
import com.openminis.app.ui.navigation.AppNavigation
import com.openminis.app.ui.navigation.Routes
import com.openminis.app.ui.theme.MinisTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexJourneyInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    private fun app(): MinisApp = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp).also {
        runBlocking { it.startupCoordinator.ensureRuntime().getOrThrow() }
    }
    private fun screenshot(name: String) {
        // Finish navigation/expansion animation before copying the physical display frame.
        ui.mainClock.advanceTimeBy(400)
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(250)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val target = File(instrumentation.targetContext.cacheDir, "journey-ui/$name.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun savedCardButtonNavigatesThroughTheRealGraphToTheExistingCard() {
        val app = app()
        // The production graph routes unconfigured accounts to model setup.
        // A local-only fixture enables navigation; this test never sends a model request.
        val providerId = "journey-navigation-${java.util.UUID.randomUUID()}"
        val model = LLMModel("journey-local", "本地界面验收", "openai", supportsTools = true)
        val requestedEntry = ModelEntry(providerId, model, isCustom = true)
        app.providerRepository.addInstance(ProviderInstance(providerId, "本地界面验收", ProviderType.openAI,
            ProviderCredential.apiKey, customBaseURL = "http://127.0.0.1:1"))
        app.providerRepository.addEntry(requestedEntry)
        val entry = app.providerRepository.entriesFor(providerId).single { it.model.id == model.id }
        val savedEntry = app.providerRepository.entriesFor(providerId).single { it.model.id == model.id }
        val group = ModelGroup(name = "本地界面验收", memberEntryIds = mutableListOf(savedEntry.id))
        app.providerRepository.addGroup(group)
        assertTrue(app.providerRepository.config.value.hasUsableNovexModel())
        val character = runBlocking { app.novexWorkspace.apply(NovexCommand.SaveCharacterPage(null, null, null, false,
            "验收邮差", "本体", """{"name":"验收邮差"}""", modules = listOf(
                NovexModuleDraft("journey-profile", ContentModuleType.CUSTOM, "邮差档案", """{"text":"替亡者送信，害怕鬼。"}""", false)))).requireCharacter() }
        val session = runBlocking {
            val created = app.chatRepository.createSession(model.id, title = "卡片成果验收", memoryEnabled = false)
            app.chatRepository.updateSessionBinding(created.id, JSONObject().put("type", "entry").put("entryId", savedEntry.id).toString(), model.id)
            app.chatRepository.appendMessage(created.id, "user", """[{"type":"text","value":"创建验收邮差角色"}]""")
            created
        }
        try {
            val value = JSONObject().put("status", "saved_verified").put("label", "已保存 1 张卡片")
                .put("cards", JSONArray().put(JSONObject().put("kind", "character_version").put("id", character.original.id).put("name", "验收邮差")))
            runBlocking { app.chatRepository.appendMessage(session.id, "assistant", JSONArray().put(JSONObject().put("type", "text").put("value", "验收邮差已保存。"))
                .put(JSONObject().put("type", "novexCardTask").put("value", value)).toString()) }
            ui.setContent { MinisTheme(darkTheme = false) {
                AppNavigation(app.chatRepository, app.providerRepository, initialRoute = Routes.chat(session.id))
            } }
            try {
                ui.waitUntil(30_000) { ui.onAllNodesWithText("打开《验收邮差》").fetchSemanticsNodes().isNotEmpty() }
            } finally { screenshot("card-before-open") }
            ui.onNodeWithText("打开《验收邮差》").performTouchInput { click() }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("邮差档案").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("创建角色").assertDoesNotExist()
            ui.onNodeWithText("邮差档案").assertIsDisplayed()
            screenshot("created-card-open")
        } finally {
            runBlocking { app.novexWorkspace.apply(NovexCommand.DeleteCharacter(character.character.id)); app.chatRepository.deleteSession(session.id) }
            app.providerRepository.removeGroup(group.id)
            app.providerRepository.removeInstance(providerId)
        }
    }
    @Test fun expandedPanelDisplaysActualStateAndMalformedSavedPanelIsExplicit() {
        app()
        ui.setContent { MinisTheme(darkTheme = false) { Column {
            NovexPanel("""{"title":"当前状况","summary":"地点与线索","collapsed":false,"blocks":[{"type":"stats","items":[{"label":"地点","value":"槐香镇邮局"}]},{"type":"markdown","content":"线索：蓝色信封"}]}""", "state", remember { PanelExpansionState() }, {})
            NovexPanel("""{"title":"旧空面板","blocks":[]}""", "empty", remember { PanelExpansionState() }, {})
        } } }
        ui.waitUntil(15_000) { ui.onAllNodesWithText("线索：蓝色信封", substring = true).fetchSemanticsNodes().isNotEmpty() }
        screenshot("actual-panel-before-check")
        ui.onNodeWithText("槐香镇邮局").assertIsDisplayed()
        ui.onNodeWithText("线索：蓝色信封", substring = true).assertIsDisplayed()
        ui.onNodeWithText("这份面板缺少有效内容，请重新生成。").assertIsDisplayed()
        screenshot("actual-panel")
    }
    @Test fun reopenedCheckpointStoreShowsTheSavedContentInTheActualListAndDetails() {
        val root = File(app().cacheDir, "journey-checkpoint-${java.util.UUID.randomUUID()}")
        try {
            val scope = NovexConversationWorkspaceScope("journey-chat", listOf("user", "reply"), "reply")
            val config = NovexConversationConfigurationSnapshot("journey-chat", activePlaythroughId = "play",
                activeInteractiveFiction = ActiveInteractiveFictionSnapshot("game", "snapshot", "邮差入镇"))
            val checkpoint = NovexPlaythroughCheckpointFactory.create("opening", config, scope.visibleBranchIds,
                "reply", "邮差入镇·开场", "地点：槐香镇邮局；线索：蓝色信封", "{}", System.currentTimeMillis())
            NovexPlaythroughCheckpointWriter(FileNovexConversationWorkspaceStore(root)).save(scope, checkpoint,
                NovexWorkspaceProvenance("journey-chat", "reply", "reply", "save-call"))
            val records = NovexCheckpointContinuation(FileNovexConversationWorkspaceStore(root)).inspect(scope)
            ui.setContent { MinisTheme(darkTheme = false) { NovexCheckpointDetails(records, {}) } }
            ui.onNodeWithText("本分支尚无正式存档").assertDoesNotExist()
            ui.onNodeWithText("邮差入镇·开场").performTouchInput { click() }
            ui.onNodeWithText("地点：槐香镇邮局；线索：蓝色信封").assertIsDisplayed()
            screenshot("checkpoint-reopened")
        } finally { root.deleteRecursively() }
    }
}
