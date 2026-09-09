package com.openminis.app.ui.chat

import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.data.model.*
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.theme.MinisTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual streamed rendering, explicit following and manual history reading in the chat screen. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexStreamCompletionViewportTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test fun followsLatestAfterStreamingSettles() = verifyViewport(false)
    @Test fun manualHistoryReadingSurvivesCompletion() = verifyViewport(true)
    @Test fun manualHistoryReadingDuringVisibleOutputSurvivesCompletion() = verifyViewport(true, waitForBody = true)
    private fun verifyViewport(readHistory: Boolean, waitForBody: Boolean = false) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow(); app.providerRepository.awaitConfigLoaded() }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                if (!body.optBoolean("stream")) return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Viewport fixture\"}}]}")
                fun chunk(text: String, finish: String? = null): String = "data: " + JSONObject()
                    .put("id", "viewport-response").put("object", "chat.completion.chunk").put("model", "paging-fixture")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0)
                        .put("delta", JSONObject().put("content", text)).put("finish_reason", finish ?: JSONObject.NULL))) + "\n\n"
                val payload = buildString {
                    repeat(40) { index -> append(chunk("\n\nParagraph ${index + 1}. " + "The post office waits beside the river. ".repeat(5))) }
                    append(chunk("\n\nVIEWPORTENDMARKER"))
                    append(chunk("", "stop")); append("data: [DONE]\n\n")
                }
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(payload)
                    .throttleBody(512, 350, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        server.start()
        val providerId = "paging-${UUID.randomUUID()}"
        val model = LLMModel("paging-fixture", "仓库验收", "openai", contextWindow = 128000, maxOutputTokens = 4096,
            supportsTools = true, supportsReasoning = false)
        val requestedEntry = ModelEntry(providerId, model, isCustom = true)
        app.providerRepository.addInstance(ProviderInstance(providerId, "隔离仓库验收", ProviderType.openAI,
            ProviderCredential.apiKey, customBaseURL = server.url("/").toString(), appendV1Suffix = true))
        app.providerRepository.saveApiKey(providerId, "local-fixture-only")
        app.providerRepository.addEntry(requestedEntry)
        val entry = app.providerRepository.entriesFor(providerId).single { it.model.id == model.id }
        val session = runBlocking {
            val created = app.chatRepository.createSession(model.id, title = "仓库分页验收")
            app.chatRepository.updateSessionBinding(created.id, JSONObject().put("type", "entry").put("entryId", entry.id).toString(), model.id)
            app.chatRepository.updateConversationSettings(created.id, ConversationSettingsSnapshot("", novexConfigurationJson =
                NovexConversationConfigurationCodec.encode(NovexConversationConfigurationSnapshot(created.id, executionMode = NovexExecutionMode.FREE))))
            created
        }
        if (readHistory) runBlocking {
            fun textPart(value: String) = JSONArray().put(JSONObject().put("type", "text").put("value", value)).toString()
            app.chatRepository.appendMessage(session.id, "user", textPart("Earlier conversation"))
            app.chatRepository.appendMessage(session.id, "assistant", textPart((1..24).joinToString("\n\n") {
                "Earlier paragraph $it. " + "A quiet street leads toward the river. ".repeat(5)
            }))
        }
        var visible by mutableStateOf(true)
        var observedVm: ChatViewModel? = null
        try {
            ui.setContent { MinisTheme(darkTheme = false) { if (visible)
                ChatScreen(session.id, app.chatRepository, app.providerRepository, onBack = {}, onBackReturnsToList = true) } }
            lateinit var vm: ChatViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                vm = androidx.lifecycle.ViewModelProvider(ChatViewModelStore.ownerFor(session.id),
                    ChatViewModel.factory(session.id, app.chatRepository, app.providerRepository, app, null, null))[ChatViewModel::class.java]
            }
            observedVm = vm
            ui.waitUntil(30_000) { vm.conversationSettingsReady.value && vm.activeEntryId.value == entry.id &&
                ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            File(app.cacheDir, "stream-viewport").also { it.mkdirs() }.resolve(
                if (waitForBody) "body-fixture.json" else if (readHistory) "early-fixture.json" else "following-fixture.json"
            ).writeText(JSONObject().put("session", session.id).put("entry", entry.id)
                .put("base_url", server.url("/").toString()).toString())
            ui.onNode(hasSetTextAction()).performTextInput("Viewport completion check")
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            if (readHistory) {
                ui.waitUntil(45_000) { ui.onAllNodesWithContentDescription("Stop").fetchSemanticsNodes().isNotEmpty() }
                if (waitForBody) ui.waitUntil(30_000) {
                    ui.onAllNodes(hasText("Paragraph 8.", substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
                ui.onNodeWithContentDescription("Stop").assertExists()
                ui.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
                val prefix = if (waitForBody) "body-history" else "early-history"
                val root = File(app.cacheDir, "stream-viewport").also { it.mkdirs() }
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(root, "$prefix-after-drag.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            if (!readHistory) {
                ui.waitUntil(30_000) { ui.onAllNodes(hasText("Paragraph 8.", substring = true)).fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithContentDescription("Stop").assertExists()
                ui.onAllNodes(hasText("Paragraph 8.", substring = true)).onFirst().assertIsDisplayed()
            }
            ui.waitUntil(60_000) {
                runBlocking { app.chatRepository.loadActiveMessages(session.id) }.any {
                    it.role == "assistant" && it.partsJson.contains("VIEWPORTENDMARKER")
                } && ui.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().isNotEmpty()
            }
            ui.waitForIdle()
            val tail = ui.onAllNodes(hasText("VIEWPORTENDMARKER", substring = true))
            if (readHistory) {
                tail.fetchSemanticsNodes().forEach { node ->
                    ui.onNode(hasText("VIEWPORTENDMARKER", substring = true)).assertIsNotDisplayed()
                }
            } else {
                ui.waitUntil(15_000) { tail.fetchSemanticsNodes().isNotEmpty() }
                tail.onFirst().assertIsDisplayed()
            }
        } finally {
            val captureRoot = File(app.cacheDir, "stream-viewport").also { it.mkdirs() }
            File(captureRoot, "binding-diagnostic.json").writeText(JSONObject()
                .put("expected_entry", entry.id).put("expected_provider", providerId)
                .put("expected_url", server.url("/").toString())
                .put("active_entry", observedVm?.activeEntryId?.value)
                .put("ready", observedVm?.conversationSettingsReady?.value)
                .put("entry_exists", app.providerRepository.config.value.modelEntries.any { it.id == entry.id })
                .put("provider_exists", app.providerRepository.instance(providerId) != null)
                .put("credential_exists", app.providerRepository.instance(providerId)?.let { app.providerRepository.usableApiKey(it) != null })
                .put("stored_binding", runBlocking { app.chatRepository.getSession(session.id) }?.modelBinding)
                .put("provider_url", app.providerRepository.instance(providerId)?.customBaseURL)
                .toString(2))
            val label = if (waitForBody) "body-history" else if (readHistory) "history" else "following"
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(captureRoot, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            runCatching { ui.onRoot().printToString() }.getOrNull()?.let { File(captureRoot, "$label-tree.txt").writeText(it) }
            File(captureRoot, "$label-messages.jsonl").writeText(runBlocking { app.chatRepository.loadActiveMessages(session.id) }
                .joinToString("\n") { JSONObject().put("role", it.role).put("parts", it.partsJson).toString() })
            runBlocking { ChatViewModelStore.stopAndJoin(session.id) }
            ui.runOnIdle { visible = false }
            runBlocking { ChatViewModelStore.finishDeletion(session.id, false) }
            app.providerRepository.removeInstance(providerId)
            server.shutdown()
        }
    }
}
