package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.creative.CreativeArtifactFileStore
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.adapter.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A real database/workspace sequence; scripted assertions do not substitute for model continuity review. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexMixedConversationPersistenceTest {
    @get:Rule val files = TemporaryFolder()
    @Test(timeout = 60_000) fun `acting managing and checkpoint continuation stay separate through reopen and game end`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "mixed.db").absolutePath).allowMainThreadQueries().build()
        var database = open()
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            var repository = ChatRepository(database.chatDao())
            val session = repository.createSession("test-model")
            val role = workspace.apply(NovexCommand.CreateCharacter("儒家伏生", """{"name":"儒家伏生","summary":"儒家公开资料"}""")).requireCharacter()
            val managed = workspace.apply(NovexCommand.CreateVariant(role.character.id, "法家伏生", """{"name":"法家伏生","summary":"法家专有知识"}""")).requireVersion()
            val module = workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(managed.id), ContentModuleType.CUSTOM,
                "法家制度", """{"kind":"article","text":"旧制度"}""")).requireModule()
            val world = workspace.apply(NovexCommand.CreateWorld("测试背景", "旧采用的地理事实")).requireWorld()
            workspace.apply(NovexCommand.EnsureConversationDrafts(session.id))
            val before = NovexConversationContextAdoption(workspace).adopt(NovexConversationConfigurationSnapshot(session.id,
                answerIdentity = AnswerIdentity.CharacterVersion(role.original.id), playerIdentity = ConversationPlayerIdentity("player", "门下记言人"),
                backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.world(world.id))),
                managedSubjects = listOf(ManagedSubject(NovexContentAddress.characterVersion(managed.id), ManagedAccess.EDIT))))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "测试文游")).requireInteractiveFiction()
            var configuration = NovexConversationConfiguration.open(before)
                .apply(NovexConversationCommand.ActivateInteractiveFiction(NovexGameSnapshotAssembler(workspace).create(game.id)))
                .apply(NovexConversationCommand.SetAnswerIdentity(before.answerIdentity))
                .apply(NovexConversationCommand.SetPlaythroughValue("answer", "hp", PlaythroughValue.Number(8.0))).snapshot
            configuration = NovexConversationContextAdoption(workspace).adopt(configuration)
            val playthrough = configuration.effectivePlaythroughId
            val service = NovexManagementService(workspace,
                CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))),
                NovexManagementTransaction { work -> database.withTransaction { work() } })
            val plan = service.propose(configuration,
                """[{"operation":"update_module","module_id":"${module.id}","content_json":{"kind":"article","text":"法家新制度"}}]""", "修改法家制度", "mixed-plan")
            service.apply(configuration, plan, "")
            assertTrue(service.inspect(configuration, null, module.id).selectedModule!!.module.contentJson.contains("法家新制度"))
            val runtime = WorkspaceNovexContextLoader(workspace).load(configuration).joinToString("\n") { it.content }
            assertFalse(runtime.contains("法家新制度"))
            assertFalse(runtime.contains("法家专有知识"))
            assertEquals(before.answerIdentity, configuration.answerIdentity)
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "原件的新地理事实")))
            assertTrue(WorkspaceNovexContextLoader(workspace).load(configuration).any { it.content.contains("旧采用的地理事实") })
            configuration = NovexConversationContextAdoption(workspace).refresh(configuration, NovexContentAddress.world(world.id), false)
            assertEquals(playthrough, configuration.effectivePlaythroughId)
            repository.appendMessage(session.id, "user", """[{"type":"text","value":"傍晚，我先借出围巾，现在已经收回。倒了水但没有喝。"}]""", messageId = "event")
            repository.appendMessage(session.id, "assistant", """[{"type":"text","value":"围巾已归还；水仍未喝。"}]""", messageId = "answer")
            val path = repository.loadActiveMessages(session.id).map { it.id }
            val scope = NovexConversationWorkspaceScope(session.id, path, "answer")
            val store = FileNovexConversationWorkspaceStore(File(files.root, "workspaces"))
            val checkpoint = NovexPlaythroughCheckpointFactory.create("checkpoint", configuration, path, "answer", "原始依据",
                "错误摘要：从未借围巾，而且已经喝水。", "{}", 10, NovexCheckpointSourceCapture.capture(session.id, path, repository.loadMessages(session.id)))
            NovexPlaythroughCheckpointWriter(store).save(scope, checkpoint, NovexWorkspaceProvenance(session.id, "answer", "event", "save"))
            workspace.apply(NovexCommand.FinalizeConversationDrafts(session.id))
            repository.updateConversationSettings(session.id, com.openminis.app.data.ConversationSettingsSnapshot(
                conversationPrompt = "", novexConfigurationJson = NovexConversationConfigurationCodec.encode(configuration)))
            database.close(); database = open(); workspace = NovexWorkspaceFactory.create(database, File(files.root, "media")); repository = ChatRepository(database.chatDao())
            val reopened = NovexConversationConfigurationCodec.decode(database.chatDao().getSession(session.id)!!.novexConfigurationJson, session.id)
            assertEquals(playthrough, reopened.effectivePlaythroughId)
            assertEquals(configuration.playthroughStates, reopened.playthroughStates)
            val continuation = NovexCheckpointContinuation(FileNovexConversationWorkspaceStore(File(files.root, "workspaces"))).prepare(reopened, scope)!!
            assertTrue(continuation.content.contains("傍晚"))
            assertTrue(continuation.content.contains("先借出围巾"))
            assertFalse(continuation.content.contains("从未借围巾"))
            repository.forkReplyFrom(session.id, "event")
            assertNull(NovexCheckpointContinuation(store).prepare(reopened, NovexConversationWorkspaceScope(session.id, listOf("event"), "alternative")))
            val ended = NovexConversationConfiguration.open(reopened).apply(NovexConversationCommand.DeactivateInteractiveFiction).snapshot
            assertNull(ended.activeInteractiveFiction)
            assertEquals(before.answerIdentity, ended.answerIdentity)
            assertEquals(before.playerIdentity, ended.playerIdentity)
            assertEquals(1, ended.completedPlaythroughs.size)
        } finally { database.close() }
    }
}
