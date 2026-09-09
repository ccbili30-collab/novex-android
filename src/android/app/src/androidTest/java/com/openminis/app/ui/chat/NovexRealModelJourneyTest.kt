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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opt-in synthetic journey. The loopback bridge holds no provider secret on the test device. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexRealModelJourneyTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    @Test fun naturalCreationRoleGameAndSaveArePersisted() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realModelBridge") == "enabled")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val providerId = "real-journey-${UUID.randomUUID()}"
        val model = LLMModel("deepseek-v4-flash", "真实模型验收", "openai", contextWindow = 128000, maxOutputTokens = 4096, supportsTools = true, supportsReasoning = false)
        app.providerRepository.addInstance(ProviderInstance(providerId, "真实模型验收", ProviderType.openAI, ProviderCredential.apiKey,
            customBaseURL = "http://10.0.2.2:17777/", appendV1Suffix = true))
        app.providerRepository.saveApiKey(providerId, "local-fixture-only")
        app.providerRepository.addEntry(ModelEntry(providerId, model, isCustom = true))
        val entry = app.providerRepository.entriesFor(providerId).single { it.model.id == model.id }
        val session = runBlocking {
            val created = app.chatRepository.createSession(model.id, title = "真实中文旅程验收")
            app.chatRepository.updateSessionBinding(created.id, JSONObject().put("type", "entry").put("entryId", entry.id).toString(), model.id)
            app.chatRepository.updateConversationSettings(created.id, ConversationSettingsSnapshot("", novexConfigurationJson =
                NovexConversationConfigurationCodec.encode(NovexConversationConfigurationSnapshot(created.id, executionMode = NovexExecutionMode.FREE))))
            created
        }
        var visible by mutableStateOf(true)
        fun configuration() = runBlocking { NovexConversationConfigurationCodec.decode(app.chatRepository.getSession(session.id)!!.novexConfigurationJson, session.id) }
        fun send(text: String) {
            ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
            val previous = runBlocking { app.chatRepository.loadActiveMessages(session.id) }.map { it.id }.toSet()
            ui.onNode(hasSetTextAction()).performTextInput(text)
            ui.onNodeWithContentDescription("Send").performTouchInput { click() }
            ui.waitUntil(240_000) {
                runBlocking { app.chatRepository.loadActiveMessages(session.id) }.any { it.role == "assistant" && it.id !in previous } &&
                    ui.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().isNotEmpty()
            }
        }
        try {
            val preExistingGames = runBlocking { app.novexWorkspace.interactiveFictions() }.map { it.project.id }.toSet()
            val preExistingCharacters = runBlocking { app.novexWorkspace.characters() }.map { it.character.character.id }.toSet()
            ui.setContent { MinisTheme(darkTheme = false) {
                if (visible) ChatScreen(session.id, app.chatRepository, app.providerRepository, onBack = { visible = false }, onBackReturnsToList = true)
                else Text("已返回列表")
            } }
            send("请创建一张角色卡，名字叫验收邮差阿予。她替亡者送信，却害怕鬼；说话温和，随身带一盏蓝灯。按人物档案、能力、恐惧来源分成三个模块，直接存入角色库。先不要扮演。")
            val role = runBlocking { app.novexWorkspace.characters() }.single {
                it.character.character.name == "验收邮差阿予" && it.character.character.id !in preExistingCharacters
            }
            val detail = runBlocking { app.novexWorkspace.character(role.character.character.id) }!!
            assertTrue(detail.modulesByVersion[role.character.original.id].orEmpty().size >= 3)
            send("现在想和验收邮差阿予聊天，请让她和我打个招呼。")
            assertEquals(AnswerIdentity.CharacterVersion(role.character.original.id), configuration().answerIdentity)
            send("再把这个送信设定做成一张文游卡，叫验收邮差入镇，并现在开始玩。我是刚到镇上的新邮差，你来主持；不要替我决定行动。开场地点在邮局，桌上有一封蓝色信封。")
            assertEquals("One requested game must not be recreated after saving the player identity", 1,
                runBlocking { app.novexWorkspace.interactiveFictions() }.count { it.project.id !in preExistingGames })
            assertTrue("The user's freely stated identity must be saved", configuration().playerIdentity?.description?.contains("刚到镇上") == true)
            val active = requireNotNull(configuration().activeInteractiveFiction)
            assertEquals("验收邮差入镇", runBlocking { app.novexWorkspace.interactiveFiction(active.projectId) }!!.project.name)
            assertFalse("要求主持后，背景邮差不能仍是主要回答身份", configuration().answerIdentity is AnswerIdentity.CharacterVersion)
            send("保存当前进度，存档名叫验收开场，并告诉我现在的位置和已经看到的线索。不要推进剧情。")
            val rows = runBlocking { app.chatRepository.loadActiveMessages(session.id) }
            val scope = NovexConversationWorkspaceScope(session.id, rows.map { it.id }, NovexConversationWorkspaceScope.ROOT_BRANCH)
            assertTrue(NovexCheckpointContinuation(app.conversationWorkspaceStore).inspect(scope).any { it.checkpoint?.name == "验收开场" })
        } finally {
            runBlocking { ChatViewModelStore.stopAndJoin(session.id) }
            val root = File(app.cacheDir, "real-model-journey").also { it.mkdirs() }
            runBlocking { app.chatRepository.loadActiveMessages(session.id) }.let { rows ->
                File(root, "messages.jsonl").writeText(rows.joinToString("\n") { JSONObject().put("id", it.id).put("role", it.role).put("parts_json", it.partsJson).toString() })
            }
            File(root, "session-id.txt").writeText(session.id)
            ui.runOnIdle { visible = false }
            runBlocking { ChatViewModelStore.finishDeletion(session.id, false) }
            app.providerRepository.removeInstance(providerId)
        }
    }
}
