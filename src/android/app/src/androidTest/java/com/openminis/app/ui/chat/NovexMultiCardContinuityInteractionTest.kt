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

/** Native replay of the incident's operation shapes; neutral source, deterministic provider. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexMultiCardContinuityInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    @Test fun readCreateRetryAppendAndReorderPreserveFourIndependentWorlds() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow(); app.providerRepository.awaitConfigLoaded() }
        val prefix = "多卡验收" + UUID.randomUUID().toString().take(6)
        val sourceText = (0..3).joinToString("\n") { "世界$it：规则是原始规则$it；人物是原始人物$it。" }
        val finalText = "四张世界卡已保存，第一张的规则已补充，人物已排到最前。"
        val requests = CopyOnWriteArrayList<JSONObject>()
        val index = AtomicInteger()
        var sourceRef = ""
        fun target() = runBlocking { app.novexWorkspace.worlds().single { it.world.name == prefix + "0" } }
        fun modules() = runBlocking { app.novexWorkspace.modules(ModuleOwner.world(target().world.id)).modules }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                if (!body.optBoolean("stream")) return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"多卡整理验收"}}]}""")
                requests += body
                val step = index.getAndIncrement()
                val calls: List<Pair<String, JSONObject>> = when (step) {
                    0 -> listOf("workspace_read" to JSONObject().put("workspace_ref", sourceRef))
                    1, 2 -> (0..3).map { n -> "novex_write_card" to JSONObject().put("kind", "world")
                        .put("creation_key", "world-$n").put("name", prefix + n)
                        .put("modules", JSONArray().put(JSONObject().put("name", "规则").put("text", "原始规则$n"))
                            .put(JSONObject().put("name", "人物").put("text", "原始人物$n"))) }
                    3 -> listOf("novex_inspect_content" to JSONObject().put("subject_kind", "world").put("subject_id", target().world.id))
                    4 -> listOf("novex_write_module" to JSONObject().put("kind", "world").put("card_id", target().world.id)
                        .put("name", "规则").put("text", "补充内容"))
                    5 -> listOf("novex_write_module" to JSONObject().put("module_id", modules().first { it.name == "规则" }.id)
                        .put("mode", "append").put("text", "补充内容"))
                    6 -> listOf("novex_move_module" to JSONObject().put("module_id", modules().first { it.name == "人物" }.id).put("position", 0))
                    7 -> listOf("novex_inspect_content" to JSONObject().put("subject_kind", "world").put("subject_id", target().world.id))
                    else -> emptyList()
                }
                val delta = JSONObject().put("role", "assistant")
                if (calls.isEmpty()) delta.put("content", finalText)
                else delta.put("tool_calls", JSONArray(calls.mapIndexed { n, call -> JSONObject().put("index", n)
                    .put("id", "multi-$step-$n").put("type", "function").put("function", JSONObject()
                        .put("name", call.first).put("arguments", call.second.toString())) }))
                fun chunk(value: JSONObject, finish: String? = null) = JSONObject().put("id", "multi-response")
                    .put("object", "chat.completion.chunk").put("model", "multi-fixture")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", value)
                        .put("finish_reason", finish ?: JSONObject.NULL))).toString()
                return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: ${chunk(delta)}\n\ndata: ${chunk(JSONObject(), if (calls.isEmpty()) "stop" else "tool_calls")}\n\ndata: [DONE]\n\n")
            }
        }
        server.start()
        val providerId = "multi-${UUID.randomUUID()}"
        val model = LLMModel("multi-fixture", "本地多卡验收", "openai", contextWindow = 128000,
            maxOutputTokens = 4096, supportsTools = true, supportsReasoning = false)
        app.providerRepository.addInstance(ProviderInstance(providerId, "隔离多卡验收", ProviderType.openAI,
            ProviderCredential.apiKey, customBaseURL = server.url("/").toString(), appendV1Suffix = true))
        app.providerRepository.saveApiKey(providerId, "local-fixture-only")
        app.providerRepository.addEntry(ModelEntry(providerId, model, isCustom = true))
        val entry = app.providerRepository.entriesFor(providerId).single { it.model.id == model.id }
        val session = runBlocking {
            val created = app.chatRepository.createSession(model.id, title = prefix, memoryEnabled = false)
            app.chatRepository.updateSessionBinding(created.id, JSONObject().put("type", "entry").put("entryId", entry.id).toString(), model.id)
            app.chatRepository.updateConversationSettings(created.id, ConversationSettingsSnapshot("", novexConfigurationJson =
                NovexConversationConfigurationCodec.encode(NovexConversationConfigurationSnapshot(created.id, executionMode = NovexExecutionMode.FREE))))
            created
        }
        val scope = NovexConversationWorkspaceScope(session.id, emptyList(), "root")
        app.conversationWorkspaceStore.importArtifact(scope, NovexWorkspaceArea.SOURCES, "四个世界.txt",
            sourceText.toByteArray(), "text/plain", NovexWorkspaceProvenance(session.id, "root"))
        sourceRef = "novex://workspaces/${session.id}/branches/root/sources/四个世界.txt"
        var visible by mutableStateOf(true)
        var opened: Pair<String, String>? = null
        try {
            ui.setContent { MinisTheme(darkTheme = false) { if (visible)
                ChatScreen(session.id, app.chatRepository, app.providerRepository, onBack = {}, onBackReturnsToList = true,
                    onOpenCreatedCard = { kind, id -> opened = kind to id }) } }
            ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            ui.onNode(hasSetTextAction()).performTextInput("读取仓库里的四个世界，分别创建四张世界卡，再补充第一张的规则并把人物移到第一位。")
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            ui.waitUntil(90_000) { ui.onAllNodesWithText(finalText).fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(15_000) { ui.onAllNodesWithContentDescription("Stop").fetchSemanticsNodes().isEmpty() }
            assertEquals(9, requests.size)
            val firstRead = requests[1].getJSONArray("messages").toString()
            assertTrue("实际工具回复必须带回完整来源", (0..3).all { firstRead.contains("原始规则$it") && firstRead.contains("原始人物$it") })
            val worlds = runBlocking { app.novexWorkspace.worlds().filter { it.world.name.startsWith(prefix) } }
            assertEquals("重复创建请求不得产生额外世界", 4, worlds.size)
            worlds.forEach { world ->
                val n = world.world.name.removePrefix(prefix).toInt()
                val saved = runBlocking { app.novexWorkspace.modules(ModuleOwner.world(world.world.id)).modules }
                assertEquals(2, saved.size)
                assertEquals(if (n == 0) listOf("人物", "规则") else listOf("规则", "人物"), saved.map { it.name })
                assertEquals("原始人物$n", JSONObject(saved.single { it.name == "人物" }.contentJson).getString("text"))
                assertEquals("原始规则$n" + if (n == 0) "\n补充内容" else "",
                    JSONObject(saved.single { it.name == "规则" }.contentJson).getString("text"))
            }
            val rows = runBlocking { app.chatRepository.loadActiveMessages(session.id) }
            val parts = rows.flatMap { row -> JSONArray(row.partsJson).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
            val results = parts.filter { it.optString("type") == "toolResult" }.map { it.getJSONObject("value") }
            assertEquals("只有一次同名新增被拒，随后按原编号补充成功", 1, results.count { !it.optBoolean("success") })
            assertTrue(parts.any { it.optString("type") == "novexCardTask" && it.getJSONObject("value").getJSONArray("cards").length() == 4 })
            ui.onAllNodesWithText("打开《${prefix}0》").onLast().performTouchInput { click() }
            ui.runOnIdle { assertEquals("world" to target().world.id, opened) }
            ui.runOnIdle { visible = false }
            runBlocking { ChatViewModelStore.stopAndJoin(session.id); ChatViewModelStore.finishDeletion(session.id, false) }
            ui.runOnIdle { visible = true }
            ui.waitUntil(30_000) { ui.onAllNodesWithText(finalText).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(4, runBlocking { app.novexWorkspace.worlds().count { it.world.name.startsWith(prefix) } })
        } finally {
            val evidence = File(app.cacheDir, "multi-card-continuity").apply { mkdirs() }
            File(evidence, "requests.json").writeText(JSONArray(requests).toString(2))
            File(evidence, "messages.json").writeText(JSONArray(runBlocking { app.chatRepository.loadActiveMessages(session.id) }
                .map { JSONObject().put("parts", JSONArray(it.partsJson)) }).toString(2))
            File(evidence, "tree.txt").writeText(ui.onRoot().printToString())
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            bitmap?.let { File(evidence, "screen.png").outputStream().use { out -> it.compress(Bitmap.CompressFormat.PNG, 100, out) }; it.recycle() }
            ui.runOnIdle { visible = false }
            runBlocking {
                app.conversationDeletion.delete(session.id)
                app.novexWorkspace.worlds().filter { it.world.name.startsWith(prefix) }.forEach { app.novexWorkspace.apply(NovexCommand.DeleteWorld(it.world.id)) }
            }
            app.providerRepository.removeEntry(entry.id)
            app.providerRepository.removeInstance(providerId)
            server.shutdown()
        }
    }
}
