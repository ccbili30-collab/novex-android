package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.creative.CreativeArtifactFileStore
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexCardFileServiceTest {
    @get:Rule val folder = TemporaryFolder()
    @Test(timeout = 60000) fun `private directory supports repeated creation editing order and links with exact readback`() = runBlocking<Unit> {
        var db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, File(folder.root, "cards.db").absolutePath).allowMainThreadQueries().build()
        try {
            var workspace = NovexWorkspaceFactory.create(db, File(folder.root, "media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("mine"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("other"))
            val config = NovexConversationConfigurationSnapshot("mine")
            fun management() = NovexManagementService(workspace, CreativeArtifactRepository(db, CreativeArtifactFileStore(File(folder.root, "artifacts"))))
            fun service() = NovexCardFileService(workspace, management(), NovexCardFileOperations(NovexCardSourceModules(NovexDocumentSnapshotStore { null }) { false }),
                NovexManagementTransaction { block -> db.withTransaction { block() } })
            val empty = workspace.conversationDrafts("mine")!!.cards
            empty.forEach { assertNotNull(management().inspect(config, it.subject, null).selectedSubject) }
            val other = workspace.conversationDrafts("other")!!.cards.first().subject
            assertThrows(IllegalArgumentException::class.java) { runBlocking { management().inspect(config, other, null) } }
            val args = JSONObject("""{"kind":"game","name":"主控说明书","modules":[{"name":"第一章","text":"门已上锁。人物仍能自行开门。"},{"name":"第二章","text":" 倒水后尚未喝水。\n围巾已归还。 "}]}""")
            val first = service().execute(config, "novex_write_card", args, listOf("创建文游卡"), "first")
            assertEquals("saved_verified", first.payload.getString("status"))
            val game = first.applied!!.createdSubjects.single()
            val modules = management().inspect(config, game, null).modules
            assertEquals(2, modules.size)
            val repeat = service().execute(config, "novex_write_card", args, listOf("继续"), "first")
            assertTrue(repeat.applied!!.replayed)
            assertEquals(game, repeat.applied.createdSubjects.single())
            val regeneratedCallId = service().execute(config, "novex_write_card", args, listOf("创建文游卡", "继续"), "provider-reissued-id")
            assertTrue(regeneratedCallId.applied!!.replayed)
            assertEquals(game, regeneratedCallId.applied.createdSubjects.single())
            val second = service().execute(config, "novex_write_card", args, listOf("再创建一张文游卡"), "second")
            assertNotEquals(game, second.applied!!.createdSubjects.single())
            assertEquals(4, workspace.conversationDrafts("mine")!!.cards.size)
            val edited = service().execute(config, "novex_write_module", JSONObject().put("module_id", modules.first().id).put("name", "门与人物"), listOf("把第一章改名为门与人物"), "rename")
            assertEquals("saved_verified", edited.payload.getString("status"))
            assertEquals(modules.first().contentJson, workspace.module(modules.first().id)!!.module.contentJson)
            service().execute(config, "novex_move_module", JSONObject().put("module_id", modules.last().id).put("position", 0), listOf("把第二章移动到第一位"), "move")
            val reordered = management().inspect(config, game, null).modules
            assertEquals(modules.last().id, reordered.first().id)
            service().execute(config, "novex_link_cards", JSONObject().put("kind", "game").put("card_id", game.id).put("reference_id", "other-rules")
                .put("target_kind", "game").put("target_id", second.applied.createdSubjects.single().id).put("purpose", "rules"), listOf("关联另一张文游作为规则参考"), "link")
            assertNull(first.configuration.activeInteractiveFiction)
            assertEquals(AnswerIdentity.Nova, first.configuration.answerIdentity)
            db.close()
            db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, File(folder.root, "cards.db").absolutePath).allowMainThreadQueries().build()
            workspace = NovexWorkspaceFactory.create(db, File(folder.root, "media"))
            assertEquals(reordered, management().inspect(config, game, null).modules)
            assertEquals(1, workspace.referencesFrom(game).size)
            assertEquals(5, workspace.conversationDrafts("mine")!!.completedWrites.size)
        } finally { db.close() }
    }
    @Test fun `explicit plural request can create identical cards and does not hit a one card ceiling`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(folder.root, "plural-media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("plural"))
            val management = NovexManagementService(workspace, CreativeArtifactRepository(db, CreativeArtifactFileStore(File(folder.root, "plural-artifacts"))))
            val service = NovexCardFileService(workspace, management, NovexCardFileOperations(NovexCardSourceModules(NovexDocumentSnapshotStore { null }) { false }),
                NovexManagementTransaction { work -> db.withTransaction { work() } })
            val locked = workspace.conversationDrafts("plural")!!.cards.single { it.subject.kind == NovexContentKind.INTERACTIVE_FICTION }.subject
            val config = NovexConversationConfigurationSnapshot("plural", managedSubjects = listOf(ManagedSubject(locked, ManagedAccess.READ_ONLY)))
            val args = JSONObject("""{"kind":"game","name":"相同的文游草稿","modules":[{"name":"规则","text":"保持相同正文"}]}""")
            val users = listOf("创建两张一样的文游卡")
            val first = service.execute(config, "novex_write_card", args, users, "plural-first")
            val second = service.execute(first.configuration, "novex_write_card", args, users, "plural-second")
            assertFalse(second.applied!!.replayed)
            assertNotEquals(first.applied!!.createdSubjects, second.applied.createdSubjects)
            assertEquals(5, workspace.conversationDrafts("plural")!!.cards.size)
            assertNotEquals(locked, first.applied.createdSubjects.single())
            assertTrue(management.inspect(config, locked, null).modules.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.execute(config, "novex_write_module", JSONObject().put("kind", "game").put("card_id", locked.id)
                    .put("name", "不得写入").put("text", "不能绕过只读"), listOf("添加这个模块"), "readonly-denied")
            } }
        } finally { db.close() }
    }

}
