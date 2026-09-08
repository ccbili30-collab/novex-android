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

/** Real ChatScreen -> streamed provider -> common gate -> Room -> UI, using an isolated local endpoint.
 * This tests product wiring, not a model's ability to understand arbitrary natural language. */
@RunWith(AndroidJUnit4::class)
class NovexChatPipelineInteractionTest {
    @get:Rule val ui = createComposeRule()

    @Test fun approvalFromChatPersistsCardAndReopensBeforeRealMenuDeletion() = exercise(NovexExecutionMode.APPROVAL)
    @Test fun freeConversationSavesWithoutApproval() = exercise(NovexExecutionMode.FREE)
    @Test fun readonlySendsNoToolsAndRejectsUnexpectedProviderCall() = exercise(NovexExecutionMode.READ_ONLY)

    private fun exercise(mode: NovexExecutionMode) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val server = MockWebServer()
        val streams = CopyOnWriteArrayList<JSONObject>()
        val streamCount = AtomicInteger()
        val title = "贯通验收${mode.name}"
        val finalText = if (mode == NovexExecutionMode.READ_ONLY) "当前为只读，资料尚未保存。" else "已保存到文游仓库：《$title》。"
        val workText = "正在整理测试资料并保存卡片。"
        val sortedText = "已将开局移到第一位，正文保持不变。"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                if (!body.optBoolean("stream")) return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                        JSONObject().put("role", "assistant").put("content", title)))).toString())
                streams += body
                val responseIndex = streamCount.getAndIncrement()
                val first = responseIndex == 0
                val sorting = mode == NovexExecutionMode.APPROVAL && responseIndex == 2
                val args = JSONObject().put("creation_key", "one-game").put("kind", "game").put(if (mode == NovexExecutionMode.APPROVAL) "nane" else "name", title)
                    .put("modules", JSONArray().put(JSONObject().put("name", "规则").put("text", "查看状态不推进剧情。"))
                        .put(JSONObject().put("name", "开局").put("text", "你来到雾港，蓝灯仍亮着。")))
                val sortArgs = if (sorting) runBlocking {
                    val game = app.novexWorkspace.interactiveFictions().single { it.project.name == title }
                    val module = app.novexWorkspace.modules(ModuleOwner.interactiveFiction(game.project.id)).modules.last()
                    JSONObject().put("module_id", module.id).put("position", 0)
                } else null
                val delta = if (first || sorting) JSONObject().put("role", "assistant").put("content", workText)
                    .put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", if (sorting) "fixture-sort" else "fixture-call")
                        .put("type", "function").put("function", JSONObject().put("name", if (sorting) "novex_move_module" else "novex_write_card").put("arguments", (sortArgs ?: args).toString()))))
                    else JSONObject().put("role", "assistant").put("content", if (responseIndex >= 3) sortedText else finalText)
                fun chunk(d: JSONObject, finish: String? = null) = JSONObject().put("id", "fixture-response")
                    .put("object", "chat.completion.chunk").put("model", "foundation-fixture")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", d)
                        .put("finish_reason", finish ?: JSONObject.NULL))).toString()
                return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: ${chunk(delta)}\n\ndata: ${chunk(JSONObject(), if (first || sorting) "tool_calls" else "stop")}\n\ndata: [DONE]\n\n")
            }
        }
        server.start()
        val instanceId = "foundation-${UUID.randomUUID()}"
        val model = LLMModel("foundation-fixture", "本地验收模型", "openai", contextWindow = 128000,
            maxOutputTokens = 4096, supportsReasoning = false, supportsTools = true)
        val entry = ModelEntry(instanceId, model, isCustom = true)
        app.providerRepository.addInstance(ProviderInstance(instanceId, "隔离验收接口", ProviderType.openAI,
            ProviderCredential.apiKey, customBaseURL = server.url("/").toString(), appendV1Suffix = true))
        app.providerRepository.saveApiKey(instanceId, "local-fixture-only")
        app.providerRepository.addEntry(entry)
        val session = runBlocking {
            val created = app.chatRepository.createSession(model.id, title = "完整聊天路径验收", memoryEnabled = false)
            app.chatRepository.updateSessionBinding(created.id,
                JSONObject().put("type", "entry").put("entryId", entry.id).toString(), model.id)
            app.chatRepository.updateConversationSettings(created.id, ConversationSettingsSnapshot("",
                novexConfigurationJson = NovexConversationConfigurationCodec.encode(
                    NovexConversationConfigurationSnapshot(created.id, executionMode = NovexExecutionMode.READ_ONLY))))
            created
        }
        var visible by mutableStateOf(true)
        var settings by mutableStateOf(false)
        var opened: Pair<String, String>? = null
        try {
            ui.setContent {
                MinisTheme(darkTheme = false) {
                    if (visible && settings) ConversationSettingsScreen(session.id, app.chatRepository, app.providerRepository,
                        onBack = { settings = false })
                    else if (visible) ChatScreen(session.id, app.chatRepository, app.providerRepository,
                        onSettings = { settings = true },
                        onBack = { visible = false }, onBackReturnsToList = true,
                        onOpenCreatedCard = { kind, id -> opened = kind to id })
                    else Text("已返回对话列表")
                }
            }
            ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            ui.onNodeWithContentDescription("更多操作").performTouchInput { click() }
            ui.onNodeWithText("对话设置").performTouchInput { click() }
            ui.waitUntil(30_000) { ui.onAllNodesWithText("执行权限").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("执行权限").performTouchInput { click() }
            NovexExecutionMode.entries.forEach { ui.onNode(hasText(it.label) and isSelectable()).assertExists() }
            ui.onNode(hasText(mode.label) and isSelectable()).performTouchInput { click() }
            ui.onNodeWithText("保存").performTouchInput { click() }
            ui.waitUntil(30_000) { ui.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasSetTextAction()).performTextInput("把这两段资料做成文游卡：规则是查看状态不推进剧情；开局是你来到雾港，蓝灯仍亮着。")
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            if (mode == NovexExecutionMode.APPROVAL) {
                ui.waitUntil(30_000) { ui.onAllNodesWithText("同意执行这一次").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(runBlocking { app.novexWorkspace.interactiveFictions().none { it.project.name == title } })
                screenshot(app, "chat-approval")
                // Simulate losing the live runtime while waiting, then approve the durable request.
                ui.runOnIdle { visible = false }
                runBlocking {
                    ChatViewModelStore.stopAndJoin(session.id)
                    ChatViewModelStore.finishDeletion(session.id, false)
                }
                ui.runOnIdle { visible = true }
                ui.waitUntil(30_000) { ui.onAllNodesWithText("同意执行这一次").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(runBlocking { app.novexWorkspace.interactiveFictions().none { it.project.name == title } })
                ui.onNode(isToggleable()).performTouchInput { click() }
            }
            ui.waitUntil(45_000) { ui.onAllNodesWithText(finalText, substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(15_000) {
                runBlocking { app.chatRepository.loadActiveMessages(session.id) }.any { it.partsJson.contains(finalText) }
            }
            ui.onNodeWithText("同意执行这一次").assertDoesNotExist()
            ui.onNodeWithText(workText).assertDoesNotExist()
            val games = runBlocking { app.novexWorkspace.interactiveFictions().filter { it.project.name == title } }
            if (mode == NovexExecutionMode.READ_ONLY) {
                assertTrue(games.isEmpty())
                assertTrue(streams.isNotEmpty())
                streams.forEach { assertEquals(0, it.optJSONArray("tools")?.length() ?: 0) }
            } else {
                val game = games.single()
                ui.onNodeWithText("打开《$title》").performTouchInput { click() }
                ui.runOnIdle { assertEquals(game.project.id, opened?.second) }
                assertTrue(streams.any { (it.optJSONArray("tools")?.length() ?: 0) > 0 })
                val raw = runBlocking { app.chatRepository.loadActiveMessages(session.id) }.joinToString { it.partsJson }
                assertTrue(raw.contains(workText))
                assertTrue(raw.contains("saved_verified"))
                assertTrue(raw.contains("execution"))
                ui.onNodeWithText("保存卡片内容").assertDoesNotExist()
                screenshot(app, "chat-saved-${mode.name}")
            }
            if (mode == NovexExecutionMode.APPROVAL) {
                val storedCalls = runBlocking { app.chatRepository.loadActiveMessages(session.id) }.flatMap { row ->
                    val parts = JSONArray(row.partsJson)
                    (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.optString("type") == "toolUse" }
                }
                val firstCall = storedCalls.first().getJSONObject("value")
                assertEquals(title, JSONObject(firstCall.getString("input")).getString("nane"))
                assertEquals(title, JSONObject(firstCall.getString("executionInput")).getString("name"))
                val game = games.single()
                val before = runBlocking { app.novexWorkspace.modules(ModuleOwner.interactiveFiction(game.project.id)).modules }
                ui.onNode(hasSetTextAction()).performTextInput("把开局移到第一位，正文不要改。")
                ui.onNodeWithContentDescription("Send").performTouchInput { click() }
                ui.waitUntil(30_000) { ui.onAllNodesWithText("同意执行这一次").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(before, runBlocking { app.novexWorkspace.modules(ModuleOwner.interactiveFiction(game.project.id)).modules })
                ui.runOnIdle { visible = false }
                runBlocking {
                    ChatViewModelStore.stopAndJoin(session.id)
                    ChatViewModelStore.finishDeletion(session.id, false)
                }
                ui.runOnIdle { visible = true }
                ui.waitUntil(30_000) { ui.onAllNodesWithText("同意执行这一次").fetchSemanticsNodes().isNotEmpty() }
                ui.onNode(isToggleable()).performTouchInput { click() }
                ui.waitUntil(45_000) { ui.onAllNodesWithText(sortedText, substring = true).fetchSemanticsNodes().isNotEmpty() }
                val after = runBlocking { app.novexWorkspace.modules(ModuleOwner.interactiveFiction(game.project.id)).modules }
                assertEquals(before.reversed().map { it.id }, after.map { it.id })
                assertEquals(before.associate { it.id to it.contentJson }, after.associate { it.id to it.contentJson })
                val pendingRows = runBlocking { app.chatRepository.loadActiveMessages(session.id) }
                val sortValues = pendingRows.flatMap { row ->
                    val parts = JSONArray(row.partsJson)
                    (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.optString("type") == "toolUse" }
                }.map { it.getJSONObject("value") }.single { it.getString("toolUseId") == "fixture-sort" }
                assertTrue(JSONObject(sortValues.getString("input")).get("position") is Int)
                assertTrue(JSONObject(sortValues.getString("executionInput")).get("position") is Int)
                // The existing transcript policy preserves reading position until explicit navigation.
                // Exercise the real latest button and verify the whole answer clears the composer.
                ui.onNodeWithContentDescription("Scroll to bottom").performTouchInput { click() }
                try {
                    ui.waitUntil(15_000) {
                        ui.onAllNodesWithContentDescription("Scroll to bottom").fetchSemanticsNodes().isEmpty()
                    }
                    val answer = ui.onNodeWithText(sortedText, substring = true).getUnclippedBoundsInRoot()
                    val input = ui.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
                    // The existing field has 12 dp inner padding plus 4 dp card top padding.
                    assertTrue("The full reply must clear the composer card", answer.bottom < input.top - 16.dp)
                } catch (failure: Throwable) {
                    screenshot(app, "chat-sort-latest-failure")
                    throw failure
                }
                screenshot(app, "chat-sort-reopened")
            }
            // Recreate the actual page runtime from durable records, not the live message list.
            ui.runOnIdle { visible = false; ChatViewModelStore.release(session.id) }
            ui.waitForIdle()
            ui.runOnIdle { visible = true }
            val reopenedText = if (mode == NovexExecutionMode.APPROVAL) sortedText else finalText
            ui.waitUntil(30_000) { ui.onAllNodesWithText(reopenedText, substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(workText).assertDoesNotExist()
            ui.onNodeWithContentDescription("更多操作").performTouchInput { click() }
            ui.onNodeWithText("删除对话").performTouchInput { click() }
            ui.onNode(hasText("删除对话") and hasClickAction()).performTouchInput { click() }
            ui.waitUntil(30_000) { ui.onAllNodesWithText("已返回对话列表").fetchSemanticsNodes().isNotEmpty() }
            assertNull(runBlocking { app.chatRepository.getSession(session.id) })
            if (mode != NovexExecutionMode.READ_ONLY) assertEquals(1,
                runBlocking { app.novexWorkspace.interactiveFictions().count { it.project.name == title } })
        } finally {
            ui.runOnIdle { visible = false }
            runBlocking { app.conversationDeletion.delete(session.id) }
            runBlocking { app.novexWorkspace.interactiveFictions().filter { it.project.name == title }.forEach {
                app.novexWorkspace.apply(NovexCommand.DeleteInteractiveFiction(it.project.id))
            } }
            app.providerRepository.removeEntry(entry.id)
            app.providerRepository.removeInstance(instanceId)
            server.shutdown()
        }
    }

    private fun screenshot(app: MinisApp, name: String) {
        ui.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        assertEquals(app.packageName, instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(app.cacheDir, "foundation-ui/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
