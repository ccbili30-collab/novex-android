package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.data.model.*
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.theme.MinisTheme
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexConversationControlsInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test fun contextSliderAndDirectPermissionsPersistInTheSameConversation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow(); app.providerRepository.awaitConfigLoaded() }
        val provider = "context-fixture-${UUID.randomUUID()}"
        val model = LLMModel("million-fixture", "百万容量检查", "openai", contextWindow = 1_000_000, maxOutputTokens = 4096)
        app.providerRepository.addInstance(ProviderInstance(provider, "本地容量检查", ProviderType.openAI,
            ProviderCredential.apiKey, customBaseURL = "http://127.0.0.1:9", appendV1Suffix = true))
        app.providerRepository.saveApiKey(provider, "local-fixture-only")
        app.providerRepository.addEntry(ModelEntry(provider, model, isCustom = true))
        val entry = app.providerRepository.entriesFor(provider).single()
        val session = runBlocking {
            val created = app.chatRepository.createSession(model.id, title = "对话容量检查", memoryEnabled = false)
            app.chatRepository.updateSessionBinding(created.id, JSONObject().put("type", "entry").put("entryId", entry.id).toString(), model.id)
            app.chatRepository.updateConversationSettings(created.id, ConversationSettingsSnapshot("",
                novexConfigurationJson = NovexConversationConfigurationCodec.encode(
                    NovexConversationConfigurationSnapshot(created.id, executionMode = NovexExecutionMode.APPROVAL,
                        playerIdentity = ConversationPlayerIdentity("kept-player", "原玩家", "已有身份不能被快速设置覆盖")))))
            created
        }
        var visible by mutableStateOf(true)
        fun stored() = runBlocking { app.chatRepository.getSession(session.id)!! }.let {
            NovexConversationConfigurationCodec.decode(it.novexConfigurationJson, it.id)
        }
        try {
            runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                val modelState = androidx.lifecycle.ViewModelProvider(
                    ChatViewModelStore.ownerFor(session.id),
                    ChatViewModel.factory(session.id, app.chatRepository, app.providerRepository, app, null, null),
                )[ChatViewModel::class.java]
                modelState.saveConversationExecutionMode(NovexExecutionMode.APPROVAL)
            } }
            assertEquals("kept-player", stored().playerIdentity?.id)
            ui.setContent { MinisTheme(darkTheme = false) {
                if (visible) ChatScreen(session.id, app.chatRepository, app.providerRepository, onBack = {}, onSettings = {})
            } }
            ui.waitUntil(30_000) { ui.onAllNodesWithText("工具：逐项批准").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("工具：逐项批准").performClick()
            ui.onNodeWithText("自由执行", useUnmergedTree = true).performClick()
            ui.waitUntil(10_000) { stored().executionMode == NovexExecutionMode.FREE }
            screenshot(app, "conversation-permission.png")
            ui.onNodeWithContentDescription("更多操作").performClick()
            ui.onNodeWithText("上下文与用量").performClick()
            ui.waitUntil(15_000) { ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(1_000_000f) }
            ui.onNodeWithText("应用到本对话").performScrollTo().performClick()
            ui.waitUntil(10_000) { stored().contextLimitTokens == 1_000_000 }
            screenshot(app, "conversation-million-context.png")
            ui.runOnIdle { visible = false }
            runBlocking { ChatViewModelStore.stopAndJoin(session.id); ChatViewModelStore.finishDeletion(session.id, false) }
            ui.runOnIdle { visible = true }
            ui.waitUntil(30_000) { ui.onAllNodesWithText("工具：自由执行").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("更多操作").performClick()
            ui.onNodeWithText("上下文与用量").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("本对话容量：1,000,000 词元").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("应用到本对话").assertIsNotEnabled()
            screenshot(app, "conversation-million-context.png")
            assertEquals(0, runBlocking { app.chatRepository.messageCount(session.id) })
            assertEquals(1_000_000, stored().contextLimitTokens)
        } finally {
            ui.runOnIdle { visible = false }
            runBlocking { ChatViewModelStore.stopAndJoin(session.id); ChatViewModelStore.finishDeletion(session.id, false); app.chatRepository.deleteSession(session.id) }
            app.providerRepository.removeInstance(provider)
        }
    }
    private fun screenshot(app: MinisApp, name: String) {
        ui.waitForIdle()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = java.io.File(app.cacheDir, "post-release-controls").also { it.mkdirs() }
        java.io.File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

}
