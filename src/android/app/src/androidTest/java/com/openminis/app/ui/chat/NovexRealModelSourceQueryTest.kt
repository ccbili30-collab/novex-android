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

/** Captures real chat answers to unchanged failed source questions; semantic acceptance is reviewed separately. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexRealModelSourceQueryTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test fun capturesOriginalQuestionsThroughProductionChatAndWorkspaceTools() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sourceQueryBridge") == "enabled")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow() }
        val root = File(app.cacheDir, "source-query-native").also { it.mkdirs() }
        val seed = JSONObject(File(root, "seed.json").readText())
        val providerId = "source-query-${UUID.randomUUID()}"
        val model = LLMModel("deepseek-v4-flash", "来源查询验收", "openai", contextWindow = 128000,
            maxOutputTokens = 4096, supportsTools = true, supportsReasoning = false)
        app.providerRepository.addInstance(ProviderInstance(providerId, "来源查询验收", ProviderType.openAI,
            ProviderCredential.apiKey, customBaseURL = "http://10.0.2.2:17777/", appendV1Suffix = true))
        app.providerRepository.saveApiKey(providerId, "local-fixture-only")
        app.providerRepository.addEntry(ModelEntry(providerId, model, isCustom = true))
        val entry = app.providerRepository.entriesFor(providerId).single { it.model.id == model.id }
        var activeSession by mutableStateOf<String?>(null)
        ui.setContent { MinisTheme(darkTheme = false) {
            activeSession?.let { ChatScreen(it, app.chatRepository, app.providerRepository, onBack = {}, onBackReturnsToList = true) }
        } }
        try {
            val questions = seed.getJSONArray("questions")
            for (i in 0 until questions.length()) {
                val question = questions.getJSONObject(i)
                val id = question.getString("id")
                val session = runBlocking {
                    val created = app.chatRepository.createSession(model.id, title = "原题来源查询 $id")
                    app.chatRepository.updateSessionBinding(created.id, JSONObject().put("type", "entry")
                        .put("entryId", entry.id).toString(), model.id)
                    app.chatRepository.updateConversationSettings(created.id, ConversationSettingsSnapshot("",
                        novexConfigurationJson = NovexConversationConfigurationCodec.encode(
                            NovexConversationConfigurationSnapshot(created.id, executionMode = NovexExecutionMode.FREE))))
                    created
                }
                val scope = NovexConversationWorkspaceScope(session.id, emptyList(), NovexConversationWorkspaceScope.ROOT_BRANCH)
                val provenance = NovexWorkspaceProvenance(session.id, scope.writeBranchId)
                val sources = seed.getJSONArray("sources")
                for (j in 0 until sources.length()) {
                    val source = sources.getJSONObject(j)
                    app.conversationWorkspaceStore.importArtifact(scope, NovexWorkspaceArea.SOURCES,
                        source.getString("name"), source.getString("text").toByteArray(Charsets.UTF_8), "text/plain", provenance)
                }
                val notes = seed.getJSONArray("notes")
                for (j in 0 until notes.length()) {
                    val note = notes.getJSONObject(j)
                    app.conversationWorkspaceStore.writeText(scope, NovexWorkspaceArea.NOTES,
                        note.getString("name"), note.getString("text"), "text/markdown", provenance)
                }
                assertEquals(sources.length() + notes.length(), app.conversationWorkspaceStore.inspect(scope).entries.size)
                try {
                    ui.runOnIdle { activeSession = session.id }
                    ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
                    ui.onNode(hasSetTextAction()).performTextInput("请从本对话仓库中查找依据，回答下面的问题并说明来源：" + question.getString("question"))
                    ui.onNodeWithContentDescription("Send").performTouchInput { click() }
                    ui.waitUntil(240_000) {
                        runBlocking { app.chatRepository.loadActiveMessages(session.id) }.any { it.role == "assistant" } &&
                            ui.onAllNodesWithContentDescription("Send").fetchSemanticsNodes().isNotEmpty()
                    }
                } finally {
                    runBlocking { ChatViewModelStore.stopAndJoin(session.id) }
                    val rows = runBlocking { app.chatRepository.loadActiveMessages(session.id) }
                    File(root, "$id-messages.jsonl").writeText(rows.joinToString("\n") {
                        JSONObject().put("id", it.id).put("role", it.role).put("parts_json", it.partsJson).toString()
                    })
                    File(root, "$id-evidence.json").writeText(JSONObject().put("session_id", session.id)
                        .put("question", question).put("seed_source_hash", seed.getString("source_state_sha256"))
                        .put("semantic_acceptance", "requires_separate_review").toString(2))
                    ui.runOnIdle { activeSession = null }
                    runBlocking { ChatViewModelStore.finishDeletion(session.id, false) }
                }
            }
        } finally {
            app.providerRepository.removeInstance(providerId)
        }
    }
}
