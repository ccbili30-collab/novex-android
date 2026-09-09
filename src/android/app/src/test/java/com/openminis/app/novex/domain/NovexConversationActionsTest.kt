package com.openminis.app.novex.domain

import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.novex.adapter.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexConversationActionsTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun startingWithUserDescriptionSavesOneRunAndRestoresTheActualPreviousIdentity() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "direct-player-media"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "入镇", "测试", playerIdentity = "旧居民")).requireInteractiveFiction()
            val actions = NovexConversationActions(workspace, userStatements = listOf("我是刚到镇上的新邮差。\n不要替我决定行动。", "在这里住了几日的邮差", "我现在是送信学徒"))
            val initial = NovexConversationConfigurationSnapshot("direct-player")
            val original = "我是刚到镇上的新邮差。"
            val args = JSONObject().put("project_id", game.id).put("player_description", original)
            try {
                actions.startGame(initial, JSONObject().put("project_id", game.id)
                    .put("player_description", "我是刚到镇上的新邮差，持有角色的蓝灯。"))
                fail("Startup must not add a character's equipment to a quoted user identity")
            } catch (_: IllegalArgumentException) { }
            // The separate identity editor can still save a user-requested generated identity.
            val generated = NovexConversationActions(workspace).setPlayerIdentity(initial,
                JSONObject().put("description", "受用户委托创作的星海领航员"))
            assertEquals("受用户委托创作的星海领航员", generated.playerIdentity!!.description)
            val started = actions.startGame(initial, args)
            assertEquals(original, started.playerIdentity!!.description)
            assertEquals(started.playerIdentity, started.activeInteractiveFiction!!.playerIdentity)
            assertNull(started.preGamePlayerIdentity)
            assertEquals(started, actions.startGame(started, args))
            val restored = NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(started), initial.conversationId)
            assertEquals(started.playerIdentity, restored.playerIdentity)
            assertNull(NovexConversationConfiguration.open(restored).apply(NovexConversationCommand.DeactivateInteractiveFiction).snapshot.playerIdentity)
            val prior = initial.copy(playerIdentity = ConversationPlayerIdentity("self", "我的身份", "原身份"))
            try { actions.startGame(prior, args); fail("No silent replacement of an existing player") }
            catch (_: IllegalArgumentException) { }
            val replaced = actions.startGame(prior, JSONObject(args.toString()).put("replace_player_identity", true))
            assertEquals(original, replaced.playerIdentity!!.description)
            assertEquals(prior.playerIdentity, NovexConversationConfiguration.open(replaced)
                .apply(NovexConversationCommand.DeactivateInteractiveFiction).snapshot.playerIdentity)
        } finally { db.close() }
    }

    @Test fun creationDoesNotActivateButExplicitActionsPersistAndRepeatedStartKeepsTheRun() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, File(files.root, "journey.db").absolutePath).allowMainThreadQueries().build()
        var db = open()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val session = ChatRepository(db.chatDao()).createSession("model")
            val character = workspace.apply(NovexCommand.CreateCharacter("阿予", """{"name":"阿予","description":"替亡者送信，害怕鬼"}""")).requireCharacter()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "邮差入镇", "诡异小镇冒险")).requireInteractiveFiction()
            val initial = NovexConversationConfigurationSnapshot(session.id,
                managedSubjects = listOf(ManagedSubject(NovexContentAddress.characterVersion(character.original.id), ManagedAccess.EDIT)))
            assertEquals(AnswerIdentity.Nova, initial.answerIdentity)
            assertNull(initial.activeInteractiveFiction)
            val actions = NovexConversationActions(workspace, userStatements = listOf("我是刚到镇上的新邮差。\n不要替我决定行动。", "在这里住了几日的邮差", "我现在是送信学徒"))
            val role = actions.selectIdentity(initial, JSONObject().put("kind", "character").put("version_id", character.original.id))
            assertEquals(AnswerIdentity.CharacterVersion(character.original.id), role.answerIdentity)
            assertTrue(role.adoptedContexts.any { it.acting && it.root.id == character.original.id })
            assertEquals(initial.managedSubjects, role.managedSubjects)
            val active = actions.startGame(role, JSONObject().put("project_id", game.id))
            assertEquals(game.id, active.activeInteractiveFiction!!.projectId)
            assertEquals(NovexPersonaPresets.gameHost, active.answerIdentity)
            assertEquals(role.answerIdentity, active.preGameAnswerIdentity)
            assertEquals(active, actions.startGame(active, JSONObject().put("project_id", game.id)))
            val encoded = NovexConversationConfigurationCodec.encode(active)
            ChatRepository(db.chatDao()).updateConversationSettings(session.id, ConversationSettingsSnapshot(conversationPrompt = "", novexConfigurationJson = encoded))
            db.close(); db = open()
            val saved = db.chatDao().getSession(session.id)!!
            val reopened = NovexConversationConfigurationCodec.decode(saved.novexConfigurationJson, session.id)
            assertEquals(active.effectivePlaythroughId, reopened.effectivePlaythroughId)
            assertEquals(active.answerIdentity, reopened.answerIdentity)
            val ended = NovexConversationConfiguration.open(reopened).apply(NovexConversationCommand.DeactivateInteractiveFiction).snapshot
            assertEquals(role.answerIdentity, ended.answerIdentity)
            assertNull(ended.activeInteractiveFiction)
        } finally { db.close() }
    }
    @Test fun freePlayerIdentityPersistsWithoutChangingSpeakerAndExplicitlySurvivesGamePreset() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "player.db").absolutePath).allowMainThreadQueries().build()
        var db = open()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "player-media"))
            val session = ChatRepository(db.chatDao()).createSession("model")
            val initial = NovexConversationConfigurationSnapshot(session.id)
            val actions = NovexConversationActions(workspace, userStatements = listOf("我是刚到镇上的新邮差。\n不要替我决定行动。", "在这里住了几日的邮差", "我现在是送信学徒"))
            val description = "  我是刚到镇上的新邮差。\n不要替我决定行动。  "
            val request = JSONObject().put("description", description)
            val configured = actions.setPlayerIdentity(initial, request)
            assertEquals(description, configured.playerIdentity!!.description)
            assertEquals(initial.answerIdentity, configured.answerIdentity)
            assertNull(configured.activeInteractiveFiction)
            assertEquals(configured, actions.setPlayerIdentity(configured, request))
            try {
                actions.setPlayerIdentity(configured, JSONObject().put("description", "在这里住了几日的邮差"))
                fail("A different player identity must not silently replace the user's selection")
            } catch (_: IllegalArgumentException) { }
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "邮差入镇", "小镇冒险",
                playerIdentity = "居住多年的居民")).requireInteractiveFiction()
            try {
                actions.startGame(configured, JSONObject().put("project_id", game.id))
                fail("A preset must not silently overwrite the current player identity")
            } catch (_: IllegalArgumentException) { }
            val active = actions.startGame(configured, JSONObject().put("project_id", game.id)
                .put("use_current_player_identity", true))
            assertEquals(configured.playerIdentity, active.playerIdentity)
            assertEquals(configured.playerIdentity, active.activeInteractiveFiction!!.playerIdentity)
            assertEquals(active, actions.startGame(active, JSONObject().put("project_id", game.id)))
            ChatRepository(db.chatDao()).updateConversationSettings(session.id, ConversationSettingsSnapshot(
                conversationPrompt = "", novexConfigurationJson = NovexConversationConfigurationCodec.encode(active)))
            db.close(); db = open()
            val reopened = NovexConversationConfigurationCodec.decode(db.chatDao().getSession(session.id)!!.novexConfigurationJson, session.id)
            assertEquals(description, reopened.playerIdentity!!.description)
            assertEquals(active.effectivePlaythroughId, reopened.effectivePlaythroughId)
            val ended = NovexConversationConfiguration.open(reopened)
                .apply(NovexConversationCommand.DeactivateInteractiveFiction).snapshot
            assertEquals(configured.playerIdentity, ended.playerIdentity)
            assertEquals(initial.answerIdentity, ended.answerIdentity)
            val reopenedActions = NovexConversationActions(NovexWorkspaceFactory.create(db, File(files.root, "player-media")), userStatements = listOf("我现在是送信学徒"))
            val changed = reopenedActions.setPlayerIdentity(ended, JSONObject().put("description", "我现在是送信学徒")
                .put("replace_existing", true))
            assertEquals(ended.playerIdentity!!.id, changed.playerIdentity!!.id)
            try {
                reopenedActions.setPlayerIdentity(changed, JSONObject().put("description", "").put("clear", true))
                fail("Clearing an existing identity requires explicit replacement")
            } catch (_: IllegalArgumentException) { }
            val cleared = reopenedActions.setPlayerIdentity(changed, JSONObject().put("description", "")
                .put("clear", true).put("replace_existing", true))
            assertNull(cleared.playerIdentity)
            assertEquals(initial.answerIdentity, cleared.answerIdentity)
        } finally { db.close() }
    }

}
