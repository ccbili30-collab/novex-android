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
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Actual streaming loop, permissions, database, context refresh and save UI; deterministic local provider. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexJourneyPipelineTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    @Test fun identityMemoryGameAndSaveUseRealStateAcrossTheStreamingLoopAndReopen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val role = runBlocking { app.novexWorkspace.apply(NovexCommand.CreateCharacter("贯通邮差", """{"name":"贯通邮差","summary":"角色暗号：雾灯七号。替亡者送信，害怕鬼。"}""")).requireCharacter() }
        val game = runBlocking { app.novexWorkspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "贯通邮差文游", "开局在邮局，线索是蓝色信封")).requireInteractiveFiction() }
        val world = runBlocking { app.novexWorkspace.apply(NovexCommand.CreateWorld("贯通世界书", "本局可采用的镇规")).requireWorld() }
        runBlocking {
            app.novexWorkspace.apply(NovexCommand.AddModule(com.openminis.app.data.character.ModuleOwner.world(world.id),
                com.openminis.app.data.character.ContentModuleType.CUSTOM, "请求命中", """{"text":"本轮应采用的密语是银鸦九号","contextTrigger":{"keys":["保存开场"]}}"""))
            app.novexWorkspace.apply(NovexCommand.AddModule(com.openminis.app.data.character.ModuleOwner.world(world.id),
                com.openminis.app.data.character.ContentModuleType.CUSTOM, "隐藏关键词不激活", """{"text":"不应泄漏的密语是黑树三号","contextTrigger":{"keys":["蓝色信封"]}}"""))
            app.novexWorkspace.apply(NovexCommand.PutCardReference(NovexCardReference(UUID.randomUUID().toString(),
                NovexContentAddress.interactiveFiction(game.id), NovexReferenceTarget(NovexContentAddress.world(world.id)), NovexReferencePurpose.RULES, targetLabel = "贯通世界书")))
        }
        val picture = runBlocking {
            val module = app.novexWorkspace.apply(NovexCommand.AddModule(com.openminis.app.data.character.ModuleOwner.interactiveFiction(game.id),
                com.openminis.app.data.character.ContentModuleType.CUSTOM, "雾中邮局插图",
                NovexStoryIllustrations.set("""{"text":"邮局景象"}""", com.openminis.app.data.character.ContentModuleType.CUSTOM, "main",
                    NovexStoryIllustrations.Rule(keys = listOf("已保存邮局开场"))))).requireModule()
            val png = java.io.ByteArrayOutputStream().also { stream ->
                val bitmap = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bitmap).apply {
                    drawColor(android.graphics.Color.rgb(36, 63, 88))
                    drawText("雾中邮局", 70f, 190f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 56f })
                }
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle()
            }.toByteArray()
            app.novexWorkspace.apply(NovexCommand.AttachImage(com.openminis.app.data.character.ModuleOwner.contentModule(module.id),
                com.openminis.app.data.character.MediaAssetSlot.MODULE_IMAGE, png, "image/png"))
            module
        }
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<JSONObject>()
        val count = AtomicInteger()
        val memory = "验收约定：正文不超过一千字"
        val finalText = "贯通验收完成，已保存邮局开场。"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                if (!body.optBoolean("stream")) return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"贯通验收"}}]}""")
                requests += body
                val index = count.getAndIncrement()
                fun call(name: String, args: JSONObject): Pair<String, JSONObject> = name to args
                val calls = when (index) {
                    0 -> listOf(call("select_answer_identity", JSONObject().put("kind", "character").put("version_id", role.original.id)))
                    1 -> listOf(call("novex_propose_memory_changes", JSONObject().put("changes", JSONArray().put(JSONObject().put("operation", "add").put("content", memory)).toString())))
                    2 -> {
                        val messages = body.getJSONArray("messages")
                        val proposal = (0 until messages.length()).map { messages.getJSONObject(it) }
                            .last { it.optString("role") == "tool" }.getString("content")
                        listOf(call("novex_apply_memory_changes", JSONObject().put("proposal_id", JSONObject(proposal).getJSONObject("data").getString("proposal_id"))))
                    }
                    3 -> listOf(call("novex_inspect_memory", JSONObject()))
                    4 -> listOf(call("start_interactive_fiction", JSONObject().put("project_id", game.id)))
                    5 -> listOf(
                        call("workspace_write", JSONObject().put("area", "notes").put("path", "蓝色信封.md").put("content", "蓝色信封需要送往钟楼")),
                        call("save_checkpoint", JSONObject().put("name", "贯通邮差·开场")),
                        call("render_panel", JSONObject().put("title", "当前状况").put("summary", "地点与线索").put("collapsed", false)
                            .put("blocks", """[{"type":"stats","items":[{"label":"地点","value":"邮局"}]},{"type":"markdown","content":"蓝色信封"}]""")))
                    6 -> listOf(call("inspect_worldbook_choices", JSONObject()))
                    7 -> {
                        val messages = body.getJSONArray("messages")
                        val choices = JSONObject((0 until messages.length()).map { messages.getJSONObject(it) }
                            .last { it.optString("role") == "tool" }.getString("content"))
                        listOf(call("set_current_worldbooks", JSONObject().put("expected_revision", choices.getString("revision"))
                            .put("changes", JSONArray().put(JSONObject().put("reference_id", choices.getJSONArray("choices").getJSONObject(0).getString("reference_id")).put("enabled", false)))))
                    }
                    9 -> listOf(call("inspect_story_images", JSONObject()))
                    10 -> {
                        val messages = body.getJSONArray("messages")
                        val result = JSONObject((0 until messages.length()).map { messages.getJSONObject(it) }
                            .last { it.optString("role") == "tool" }.getString("content"))
                        listOf(call("select_story_image", JSONObject().put("image_id", result.getJSONArray("images").getJSONObject(0).getString("image_id"))))
                    }
                    12 -> {
                        val messages = body.getJSONArray("messages")
                        val instructions = (0 until messages.length()).map { messages.getJSONObject(it) }
                            .filter { it.optString("role") == "system" }.joinToString("\n") { it.getString("content") }
                        val playthroughId = instructions.lineSequence().first { it.startsWith("本局编号：") }
                            .substringAfter("本局编号：").trim()
                        listOf(call("end_interactive_fiction", JSONObject().put("playthrough_id", playthroughId)))
                    }
                    else -> emptyList()
                }
                val delta = JSONObject().put("role", "assistant")
                if (calls.isEmpty()) delta.put("content", if (index >= 13) "本局已结束，恢复贯通邮差身份。" else if (index >= 11) "按你的要求，再展示这张图。" else finalText) else delta.put("tool_calls", JSONArray(calls.mapIndexed { i, (name, args) ->
                    JSONObject().put("index", i).put("id", "journey-$index-$i").put("type", "function")
                        .put("function", JSONObject().put("name", name).put("arguments", args.toString()))
                }))
                fun chunk(value: JSONObject, finish: String? = null) = JSONObject().put("id", "journey-response-$index").put("object", "chat.completion.chunk")
                    .put("model", "journey-local").put("choices", JSONArray().put(JSONObject().put("index", 0).put("delta", value).put("finish_reason", finish ?: JSONObject.NULL))).toString()
                return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: ${chunk(delta)}\n\ndata: ${chunk(JSONObject(), if (calls.isEmpty()) "stop" else "tool_calls")}\n\ndata: [DONE]\n\n")
            }
        }
        server.start()
        val providerId = "journey-pipeline-${UUID.randomUUID()}"
        val model = LLMModel("journey-local", "本地贯通验收", "openai", contextWindow = 128000, maxOutputTokens = 4096, supportsTools = true, supportsReasoning = false)
        val requestedEntry = ModelEntry(providerId, model, isCustom = true)
        app.providerRepository.addInstance(ProviderInstance(providerId, "本地贯通验收", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = server.url("/").toString(), appendV1Suffix = true))
        app.providerRepository.saveApiKey(providerId, "local-fixture-only")
        app.providerRepository.addEntry(requestedEntry)
        val entry = app.providerRepository.entriesFor(providerId).single { it.model.id == model.id }
        val savedEntry = app.providerRepository.entriesFor(providerId).single { it.model.id == model.id }
        val session = runBlocking {
            val created = app.chatRepository.createSession(model.id, title = "贯通验收", memoryEnabled = true)
            app.chatRepository.updateSessionBinding(created.id, JSONObject().put("type", "entry").put("entryId", savedEntry.id).toString(), model.id)
            app.chatRepository.updateConversationSettings(created.id, ConversationSettingsSnapshot("", novexConfigurationJson =
                NovexConversationConfigurationCodec.encode(NovexConversationConfigurationSnapshot(created.id, executionMode = NovexExecutionMode.FREE))))
            created
        }
        var visible by mutableStateOf(true)
        try {
            ui.setContent { MinisTheme(darkTheme = false) {
                if (visible) ChatScreen(session.id, app.chatRepository, app.providerRepository, onBack = { visible = false }, onBackReturnsToList = true)
                else Text("已返回列表")
            } }
            ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            ui.onNode(hasSetTextAction()).performTextInput("先切换为贯通邮差，记住正文不超过一千字，再启动贯通邮差文游并保存开场，展示状态，不推进剧情，最后关闭本局世界书。")
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            ui.waitUntil(60_000) { ui.onAllNodesWithText(finalText, substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(15_000) { runBlocking { app.chatRepository.loadActiveMessages(session.id) }.any { it.partsJson.contains(finalText) } }
            assertEquals(9, requests.size)
            fun system(index: Int): String = requests[index].getJSONArray("messages").let { messages ->
                (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.optString("role") == "system" }.joinToString { it.optString("content") }
            }
            assertTrue("Role must affect the next actual model request", system(1).contains("雾灯七号"))
            assertTrue("Applied memory must affect the next actual model request", system(3).contains(memory))
            assertTrue("Activated game must affect the next actual model request", system(5).contains("贯通邮差文游"))
            assertFalse("Role memory must not leak after switching to the game host", system(5).contains(memory))
            assertTrue("可见请求的关键词应激活世界书", system(5).contains("银鸦九号"))
            assertFalse("隐藏设定不能自行激活世界书", system(5).contains("黑树三号"))
            assertFalse("工具结果不能自行激活世界书", system(6).contains("黑树三号"))
            assertFalse("关闭本局引用后下一次请求不得再采用", system(8).contains("银鸦九号"))
            val rows = runBlocking { app.chatRepository.loadActiveMessages(session.id) }
            val raw = rows.joinToString { it.partsJson }
            assertTrue("完成的回答应保存剧情插图", raw.contains(NOVEX_STORY_IMAGE))
            assertTrue(raw.contains("雾中邮局插图"))
            assertTrue("图片像素不得进入模型请求", requests.none { it.toString().contains("data:image") })
            assertTrue(raw.contains("memory.applied")); assertTrue(raw.contains("memory.ready")); assertTrue(raw.contains(memory))
            val saved = runBlocking { app.chatRepository.getSession(session.id) }!!
            val config = NovexConversationConfigurationCodec.decode(saved.novexConfigurationJson, session.id)
            assertEquals(game.id, config.activeInteractiveFiction!!.projectId)
            assertEquals(NovexPersonaPresets.gameHost, config.answerIdentity)
            val scope = NovexConversationWorkspaceScope(session.id, rows.map { it.id }, NovexConversationWorkspaceScope.ROOT_BRANCH)
            val checkpoints = NovexCheckpointContinuation(app.conversationWorkspaceStore).inspect(scope)
            assertEquals(1, checkpoints.size)
            assertTrue(checkpoints.single().checkpoint!!.sourceCaptureRecorded)
            assertEquals("{}", checkpoints.single().checkpoint!!.stateJson)
            assertTrue(checkpoints.single().checkpoint!!.sourceEvents.isNotEmpty())
            assertTrue(checkpoints.single().checkpoint!!.adoptedConfigurationJson!!.contains(game.id))
            assertTrue(checkpoints.single().checkpoint!!.branchId in rows.map { it.id })
            val files = com.openminis.app.tools.NovexWorkspaceAgentTools(app.conversationWorkspaceStore).execute("workspace_search",
                JSONObject().put("query", "蓝色信封需要送往钟楼").toString(), scope,
                NovexWorkspaceProvenance(session.id, scope.writeBranchId), runBlocking { app.creativeArtifactRepository.visibleSourcePaths(session.id) })
            assertTrue(files.output, files.success)
            assertEquals("本轮写入的文件在持久分支里必须可检索：${files.output}", 1,
                JSONObject(files.output).getJSONObject("data").getJSONArray("entries").length())
            ui.onNode(hasSetTextAction()).performTextInput("请再展示雾中邮局插图，不推进故事。")
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            ui.waitUntil(60_000) { runBlocking { app.chatRepository.loadActiveMessages(session.id) }.any { it.partsJson.contains("按你的要求，再展示这张图。") && it.partsJson.contains(NOVEX_STORY_IMAGE) } }
            assertEquals("显式选图只增加查看、选择和完成三次模型请求", 12, requests.size)
            val illustrated = runBlocking { app.chatRepository.loadActiveMessages(session.id) }.filter { it.role == "assistant" && it.partsJson.contains(NOVEX_STORY_IMAGE) }
            assertEquals(2, illustrated.size)
            assertTrue(requests.none { it.toString().contains("data:image") })
            runBlocking { app.novexWorkspace.apply(NovexCommand.DetachImage(com.openminis.app.data.character.ModuleOwner.contentModule(picture.id), com.openminis.app.data.character.MediaAssetSlot.MODULE_IMAGE)) }
            val persistedReply = runBlocking { app.chatRepository.loadActiveMessages(session.id) }.last { it.role == "assistant" }.id
            val legacyScope = NovexConversationWorkspaceScope(session.id, listOf("assistant_123456"), "assistant_123456")
            val legacyFile = app.conversationWorkspaceStore.writeText(legacyScope, NovexWorkspaceArea.NOTES,
                "旧版遗留.md", "旧版钟楼线索", "text/markdown",
                NovexWorkspaceProvenance(session.id, legacyScope.writeBranchId, messageId = persistedReply))
            ui.runOnIdle { visible = false }
            runBlocking { ChatViewModelStore.stopAndJoin(session.id); ChatViewModelStore.finishDeletion(session.id, false) }
            ui.runOnIdle { visible = true }
            val recoveredScope = NovexConversationWorkspaceScope(session.id, listOf(persistedReply), persistedReply)
            ui.waitUntil(30_000) { app.conversationWorkspaceStore.inspect(recoveredScope).entries.any { it.workspaceRef.relativePath == "旧版遗留.md" } }
            val recoveredFile = app.conversationWorkspaceStore.inspect(recoveredScope).entries.single { it.workspaceRef.relativePath == "旧版遗留.md" }
            assertEquals("旧版钟楼线索", app.conversationWorkspaceStore.readBytes(recoveredScope, recoveredFile.workspaceRef).toString(Charsets.UTF_8))
            assertNotNull(app.conversationWorkspaceStore.find(legacyScope, legacyFile.workspaceRef))
            ui.waitUntil(30_000) { ui.onAllNodesWithContentDescription("更多操作").fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(15_000) { ui.onAllNodesWithContentDescription("雾中邮局插图").fetchSemanticsNodes().isNotEmpty() }
            val pictureCapture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(app.cacheDir, "journey-ui").mkdirs()
            java.io.File(app.cacheDir, "journey-ui/persisted-story-image.png").outputStream().use { pictureCapture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; pictureCapture.recycle()
            ui.onNodeWithContentDescription("更多操作").performTouchInput { click() }
            ui.onNodeWithText("资料与存档").performTouchInput { click() }
            ui.onNodeWithText("文游存档").performTouchInput { click() }
            ui.waitUntil(15_000) { ui.onAllNodesWithText("贯通邮差·开场").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("贯通邮差·开场").performTouchInput { click() }
            ui.onNodeWithText("已保存当前对话、采用的设定与本局状态。").assertIsDisplayed()
            ui.onNodeWithText("novex://workspaces/", substring = true).assertDoesNotExist()
            ui.onNodeWithText("当时没有数值字段").assertDoesNotExist()
            ui.onNodeWithText("保存信息").performTouchInput { click() }
            ui.waitUntil(10_000) { ui.onAllNodesWithText("文件位置").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("novex://workspaces/", substring = true).assertIsDisplayed()
            ui.onNodeWithText("返回存档详情").performTouchInput { click() }
            ui.waitUntil(10_000) { ui.onAllNodesWithText("保存说明 · 以原始记录为准").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("novex://workspaces/", substring = true).assertDoesNotExist()
            // End a reopened game through the real tool entry, then verify the next request.
            ui.runOnIdle { visible = false }
            runBlocking { ChatViewModelStore.stopAndJoin(session.id); ChatViewModelStore.finishDeletion(session.id, false) }
            ui.runOnIdle { visible = true }
            ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            ui.onNode(hasSetTextAction()).performTextInput("结束本局文游，恢复开始前的身份。")
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            ui.waitUntil(60_000) { ui.onAllNodesWithText("本局已结束，恢复贯通邮差身份。").fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(15_000) { ui.onAllNodesWithContentDescription("Stop").fetchSemanticsNodes().isEmpty() }
            val ended = runBlocking { app.chatRepository.getSession(session.id) }!!
            val endedConfig = NovexConversationConfigurationCodec.decode(ended.novexConfigurationJson, session.id)
            assertNull(endedConfig.activeInteractiveFiction)
            assertEquals(AnswerIdentity.CharacterVersion(role.original.id), endedConfig.answerIdentity)
            assertEquals(14, requests.size)
            assertTrue("恢复身份必须进入下一次真实请求", system(13).contains("雾灯七号"))
            assertTrue("原角色的记忆随原作用范围恢复", system(13).contains(memory))
        } finally {
            val root = java.io.File(app.cacheDir, "journey-ui").also { it.mkdirs() }
            java.io.File(root, "pipeline-requests.json").writeText(JSONArray(requests).toString())
            java.io.File(root, "pipeline-semantics.txt").writeText(ui.onAllNodes(isRoot()).printToString())
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(root, "pipeline-final.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
            ui.runOnIdle { visible = false }
            runBlocking {
                ChatViewModelStore.stopAndJoin(session.id); ChatViewModelStore.finishDeletion(session.id, false)
                app.chatRepository.deleteSession(session.id)
                app.novexWorkspace.apply(NovexCommand.DeleteCharacter(role.character.id))
                app.novexWorkspace.apply(NovexCommand.DeleteInteractiveFiction(game.id))
                app.novexWorkspace.apply(NovexCommand.DeleteWorld(world.id))
            }
            app.providerRepository.removeInstance(providerId)
            server.shutdown()
        }
    }
}
