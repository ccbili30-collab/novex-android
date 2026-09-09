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

/** Exercises pagination across persisted tool replies and readable Unicode paths in the actual chat loop. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexWorkspacePagingPipelineTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test fun nextPageAndDisplayedChineseReferenceSurviveToolReplyPersistence() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<JSONObject>()
        val results = CopyOnWriteArrayList<JSONObject>()
        val step = AtomicInteger()
        var displayedRef = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                if (!body.optBoolean("stream")) return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"仓库分页验收\"}}]}")
                requests += body
                val index = step.getAndIncrement()
                val messages = body.getJSONArray("messages")
                val lastTool = (messages.length() - 1 downTo 0).map { messages.getJSONObject(it) }
                    .firstOrNull { it.optString("role") == "tool" }?.let { JSONObject(it.getString("content")) }
                if (lastTool != null) results += lastTool
                val failed = lastTool?.optBoolean("ok") == false
                val arguments = when (index) {
                    0 -> JSONObject().put("area", "sources").put("max_entries", 1)
                    1 -> JSONObject().put("area", "sources").put("max_entries", 1)
                        .put("cursor", lastTool?.optJSONObject("data")?.optString("next_cursor"))
                    2 -> JSONObject().put("workspace_ref", displayedRef)
                    else -> null
                }
                val call = !failed && arguments != null
                val delta = JSONObject().put("role", "assistant")
                if (call) delta.put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "paging-$index")
                    .put("type", "function").put("function", JSONObject().put("name", if (index == 2) "workspace_read" else "workspace_inspect")
                        .put("arguments", arguments.toString()))))
                else delta.put("content", "仓库检查已结束")
                fun chunk(value: JSONObject, finish: String? = null) = JSONObject().put("id", "paging-response")
                    .put("object", "chat.completion.chunk").put("model", "paging-fixture")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", value)
                        .put("finish_reason", finish ?: JSONObject.NULL))).toString()
                return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: ${chunk(delta)}\n\ndata: ${chunk(JSONObject(), if (call) "tool_calls" else "stop")}\n\ndata: [DONE]\n\n")
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
        val scope = NovexConversationWorkspaceScope(session.id, emptyList(), "root")
        for (path in listOf("a.txt", "b_人物 🕊.txt")) app.conversationWorkspaceStore.importArtifact(scope, NovexWorkspaceArea.SOURCES,
            path, "林川出生于桥北村。".toByteArray(), "text/plain", NovexWorkspaceProvenance(session.id, "root"))
        displayedRef = "novex://workspaces/${session.id}/branches/root/sources/b_人物 🕊.txt"
        var visible by mutableStateOf(true)
        try {
            ui.setContent { MinisTheme(darkTheme = false) { if (visible)
                ChatScreen(session.id, app.chatRepository, app.providerRepository, onBack = {}, onBackReturnsToList = true) } }
            ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            ui.onNode(hasSetTextAction()).performTextInput("请逐页查看仓库，再读取人物资料。")
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            ui.waitUntil(60_000) { ui.onAllNodesWithText("仓库检查已结束").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Expected one result for each of two inventory pages and one read", 3, results.size)
            assertTrue(results.joinToString("\n"), results.all { it.getBoolean("ok") })
            val second = results[1].getJSONObject("data")
            assertEquals("b_人物 🕊.txt", second.getJSONArray("entries").getJSONObject(0).getString("path"))
            assertFalse(second.getBoolean("truncated"))
            assertTrue(results[2].toString().contains("林川出生于桥北村"))
        } finally {
            runBlocking { ChatViewModelStore.stopAndJoin(session.id) }
            File(app.cacheDir, "workspace-paging-requests.json").writeText(JSONArray(requests).toString(2))
            ui.runOnIdle { visible = false }
            runBlocking { ChatViewModelStore.finishDeletion(session.id, false) }
            app.providerRepository.removeInstance(providerId)
            server.shutdown()
        }
    }
}
