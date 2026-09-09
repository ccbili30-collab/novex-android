package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.data.model.*
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.theme.MinisTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Captures the actual provider request after reopening an old-identity conversation. No external model. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexHistoryPrivacyInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test fun switchedIdentityDoesNotReplayPrivateHistoricalTools() = requestAfterSwitch(false)
    @Test fun switchedIdentityDoesNotReplayLegacyCompactSummary() = requestAfterSwitch(true)

    @Test fun switchedIdentityKeepsSanitizedExecutionPairsInProviderRequest() = requestAfterSwitch(false, true)

    private fun requestAfterSwitch(withCompactSummary: Boolean, withReceipts: Boolean = false) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val captured = CompletableFuture<String>()
        val worker = thread(isDaemon = true) {
            try { server.accept().use { socket ->
                socket.soTimeout = 30_000
                val input = socket.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val byte = input.read(); require(byte >= 0); header.append(byte.toChar())
                    require(header.length < 32_768)
                }
                val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) { val count = input.read(bytes, offset, length - offset); require(count > 0); offset += count }
                captured.complete(bytes.toString(Charsets.UTF_8))
                val chunk = JSONObject().put("id", "privacy-response").put("object", "chat.completion.chunk")
                    .put("choices", JSONArray().put(JSONObject().put("index", 0)
                        .put("delta", JSONObject().put("role", "assistant").put("content", "你好，继续整理即可。"))
                        .put("finish_reason", "stop")))
                val body = "data: $chunk\n\ndata: [DONE]\n\n".toByteArray()
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() }
            } } catch (error: Throwable) { captured.completeExceptionally(error) }
        }
        val providerId = "privacy-fixture-${UUID.randomUUID()}"
        val model = LLMModel("privacy-fixture", "历史隔离验收", "openai", contextWindow = 128000,
            maxOutputTokens = 1024, supportsTools = true, supportsReasoning = false)
        app.providerRepository.addInstance(ProviderInstance(providerId, "历史隔离验收", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "http://127.0.0.1:${server.localPort}/", appendV1Suffix = true))
        app.providerRepository.saveApiKey(providerId, "local-fixture-only")
        app.providerRepository.addEntry(ModelEntry(providerId, model, isCustom = true))
        val entry = app.providerRepository.entriesFor(providerId).single()
        val secret = "旧身份私有口令_紫色雨伞_仅此一次"
        val session = runBlocking {
            val session = app.chatRepository.createSession(model.id, title = "历史隔离验收")
            app.chatRepository.updateSessionBinding(session.id, JSONObject().put("type", "entry").put("entryId", entry.id).toString(), model.id)
            app.chatRepository.updateConversationSettings(session.id, ConversationSettingsSnapshot("", novexConfigurationJson =
                NovexConversationConfigurationCodec.encode(NovexConversationConfigurationSnapshot(session.id, executionMode = NovexExecutionMode.FREE))))
            fun text(value: String) = JSONArray().put(JSONObject().put("type", "text").put("value", value)).toString()
            val user = app.chatRepository.appendMessage(session.id, "user", text("请保存这份工作，下一次继续整理邮局资料。"))
            val call = JSONObject().put("type", "toolUse").put("value", JSONObject().put("toolUseId", "private-read")
                .put("name", "novex_context_read").put("input", JSONObject().put("source_id", "private-role").toString()))
            val assistant = app.chatRepository.appendMessage(session.id, "assistant", JSONArray().put(call).toString(), reasoningContent = secret)
            app.chatRepository.appendMessage(session.id, "user", JSONArray().put(JSONObject().put("type", "toolResult").put("value",
                JSONObject().put("toolUseId", "private-read").put("name", "novex_context_read").put("output", secret).put("success", true))).toString())
            if (withReceipts) {
                val calls = JSONArray()
                val results = JSONArray()
                for ((id, name, success) in listOf(Triple("old-player", "set_player_identity", true),
                    Triple("old-start", "start_interactive_fiction", false))) {
                    calls.put(JSONObject().put("type", "toolUse").put("value", JSONObject()
                        .put("toolUseId", id).put("name", name).put("input", JSONObject().put("private", secret).toString())))
                    results.put(JSONObject().put("type", "toolResult").put("value", JSONObject()
                        .put("toolUseId", id).put("name", name).put("success", success)
                        .put("output", JSONObject().put("ok", success).put("code", "conversation.configured")
                            .put("private", secret).toString())))
                }
                app.chatRepository.appendMessage(session.id, "assistant", calls.toString(), reasoningContent = secret)
                app.chatRepository.appendMessage(session.id, "user", results.toString())
            }
            val formal = app.chatRepository.appendMessage(session.id, "assistant", text("已经保存邮局资料，下一次可以继续。"))
            app.chatRepository.recordNovexContextUsage(session.id, ContextUsageRecord("old-identity-${session.id}", user.id,
                responseMessageId = assistant.id, branchId = assistant.id,
                answerIdentity = AnswerIdentity.PersonaPreset("old-private", "旧身份", secret), includedSources = emptyList(),
                usedTokens = 100, effectiveWindowTokens = 128000))
            if (withCompactSummary) app.chatRepository.dao.insertCompactMarker(com.openminis.app.data.db.CompactMarkerEntity(
                id = "legacy-private-${session.id}", sessionId = session.id, summary = "旧环境的压缩摘要：$secret",
                firstKeptSortOrder = Int.MAX_VALUE, compactedCount = 4, createdAt = System.currentTimeMillis(),
                lastCompactedMessageId = formal.id, version = 2))
            session
        }
        var visible by mutableStateOf(true)
        try {
            ui.setContent { MinisTheme(darkTheme = false) {
                if (visible) ChatScreen(session.id, app.chatRepository, app.providerRepository, onBack = { visible = false })
                else Text("已关闭")
            } }
            ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            ui.onNode(hasSetTextAction()).performTextInput("你好，继续整理邮局资料。")
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            ui.waitUntil(30_000) { captured.isDone }
            val request = captured.get(1, TimeUnit.SECONDS)
            java.io.File(app.cacheDir, "history-privacy").also { it.mkdirs() }.resolve(
                if (withReceipts) "paired-request.json" else if (withCompactSummary) "summary-request.json" else "history-request.json").writeText(request)
            assertFalse("新身份的实际请求不应含旧工具结果或推理", request.contains(secret))
            assertTrue("公开任务不能因切换身份丢失", request.contains("下一次继续整理邮局资料"))
            assertTrue("用户可见的完成答复应保留", request.contains("已经保存邮局资料"))
            if (withReceipts) {
                val messages = JSONObject(request).getJSONArray("messages")
                val entries = (0 until messages.length()).map { messages.getJSONObject(it) }
                for ((id, expected) in listOf("old-player" to "已完成一次保存玩家身份", "old-start" to "启动文游尝试未完成")) {
                    val result = entries.single { it.optString("role") == "tool" && it.optString("tool_call_id") == id }
                    assertTrue(result.getString("content").contains(expected))
                    val call = entries.flatMap { entry -> entry.optJSONArray("tool_calls")?.let { array ->
                        (0 until array.length()).map { array.getJSONObject(it) }
                    }.orEmpty() }.single { it.getString("id") == id }
                    assertTrue(JSONObject(call.getJSONObject("function").getString("arguments"))
                        .getBoolean("_history_arguments_omitted"))
                    assertFalse(entries.any { it.optString("role") == "assistant" && it.optString("content").contains(expected) })
                }
            }

        } finally {
            runBlocking { ChatViewModelStore.stopAndJoin(session.id) }
            ui.runOnIdle { visible = false }
            runBlocking { ChatViewModelStore.finishDeletion(session.id, false) }
            app.providerRepository.removeInstance(providerId)
            server.close(); worker.join(1000)
        }
    }
}
