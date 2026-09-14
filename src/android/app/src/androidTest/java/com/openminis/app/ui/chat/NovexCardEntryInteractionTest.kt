package com.openminis.app.ui.chat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.theme.MinisTheme
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NovexCardEntryInteractionTest {
    @get:Rule val ui = createComposeRule(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test fun missingGameKeepsFailureVisibleAndDoesNotCreateOrdinaryChat() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow(); app.providerRepository.awaitConfigLoaded() }
        val before = runBlocking { app.chatRepository.dao.listSessions().map { it.id }.toSet() }
        var returned = false
        val route = "__new__game__missing-${UUID.randomUUID()}__${UUID.randomUUID()}"
        ui.setContent { MinisTheme(darkTheme = false) {
            ChatScreen(route, app.chatRepository, app.providerRepository, onBack = { returned = true }, onSettings = {})
        } }
        ui.waitUntil(30_000) { ui.onAllNodesWithText("文游尚未启动").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("重新尝试").performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithText("文游尚未启动").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("返回卡片").performClick()
        assertTrue(returned)
        assertEquals(before, runBlocking { app.chatRepository.dao.listSessions().map { it.id }.toSet() })
    }

    @Test fun gameEntryResolvesPlayerChoiceBeforeOpeningOrdinaryConversation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        runBlocking { app.startupCoordinator.ensureRuntime().getOrThrow(); app.providerRepository.awaitConfigLoaded() }
        val role = runBlocking { app.novexWorkspace.apply(NovexCommand.CreateCharacter("入口检查角色", "{}")).requireCharacter() }
        val game = runBlocking { app.novexWorkspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "入口检查文游", playerIdentity = "文游指定旅人")).requireInteractiveFiction() }
        val before = runBlocking { app.chatRepository.dao.listSessions().map { it.id }.toSet() }
        try {
            runBlocking {
                app.novexWorkspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(role.original.id), ContentModuleType.ROLE_PLAYER_IDENTITY,
                    "角色配套记言人", """{"text":"角色指定记言人"}"""))
                app.novexWorkspace.apply(NovexCommand.PutCardReference(NovexCardReference(UUID.randomUUID().toString(),
                    NovexContentAddress.interactiveFiction(game.id), NovexReferenceTarget(NovexContentAddress.characterVersion(role.original.id)),
                    NovexReferencePurpose.ANSWER_IDENTITY)))
            }
            val route = "__new__game__${game.id}__${UUID.randomUUID()}"
            ui.setContent { MinisTheme(darkTheme = false) {
                ChatScreen(route, app.chatRepository, app.providerRepository, onBack = {}, onSettings = {})
            } }
            ui.waitUntil(30_000) { ui.onAllNodesWithText("选择本局玩家身份").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("选择本局玩家身份").assertIsDisplayed()
            ui.onNodeWithText("角色配套记言人").performClick()
            ui.waitUntil(30_000) { runBlocking {
                app.chatRepository.dao.listSessions().filter { it.id !in before }.any {
                    val cfg = NovexConversationConfigurationCodec.decode(it.novexConfigurationJson, it.id)
                    cfg.activeInteractiveFiction?.projectId == game.id && cfg.playerIdentity?.description == "角色指定记言人"
                }
            } }
            val session = runBlocking { app.chatRepository.dao.listSessions().first { it.id !in before } }
            assertEquals(0, runBlocking { app.chatRepository.messageCount(session.id) })
            val saved = NovexConversationConfigurationCodec.decode(session.novexConfigurationJson, session.id)
            assertEquals(AnswerIdentity.CharacterVersion(role.original.id), saved.answerIdentity)
            assertEquals("角色指定记言人", saved.playerIdentity?.description)
        } finally {
            runBlocking {
                app.chatRepository.dao.listSessions().filter { it.id !in before }.forEach { app.chatRepository.deleteSession(it.id) }
                app.novexWorkspace.apply(NovexCommand.DeleteInteractiveFiction(game.id))
                app.novexWorkspace.apply(NovexCommand.DeleteCharacter(role.character.id))
            }
        }
    }
}
