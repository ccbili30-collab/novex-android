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
    @Test fun `content executor rolls back cards with failed configuration save and replays committed receipt`() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(folder.root, "executor-media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("chat"))
            fun executor() = NovexContentToolExecutor(workspace,
                NovexManagementService(workspace, CreativeArtifactRepository(db, CreativeArtifactFileStore(File(folder.root, "executor-artifacts")))),
                NovexCardFileOperations(NovexCardSourceModules(NovexDocumentSnapshotStore { null }) { false }),
                NovexManagementTransaction { work -> db.withTransaction { work() } })
            val request = NovexContentToolExecutor.Request(NovexConversationConfigurationSnapshot("chat"),
                listOf("把这段设定存成世界卡"), "reply", "call", "user")
            val args = """{"creation_key":"world-a","kind":"world","name":"浮岛","modules":[{"name":"地理","text":"所有岛屿都悬浮在云海之上。"}]}"""
            val failed = executor().execute("novex_write_card", args, request) { error("模拟对话配置存储失败") }
            assertFalse(failed.tool.success)
            assertTrue(workspace.worlds().isEmpty())
            assertTrue(workspace.conversationDrafts("chat")!!.completedWrites.isEmpty())
            var saves = 0
            val saved = executor().execute("novex_write_card", args, request) { saves++ }
            assertTrue(saved.tool.output, saved.tool.success)
            assertEquals(1, saves)
            assertEquals("浮岛", workspace.worlds().single().world.name)
            assertEquals(AnswerIdentity.Nova, saved.configuration.answerIdentity)
            assertNull(saved.configuration.activeInteractiveFiction)
            val retried = executor().execute("novex_write_card", args,
                request.copy(configuration = saved.configuration, callId = "reissued")) { saves++ }
            assertTrue(retried.tool.success)
            assertTrue(JSONObject(retried.tool.output).getBoolean("replayed"))
            assertEquals(1, saves)
            assertEquals(1, workspace.worlds().size)
        } finally { db.close() }
    }
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
            val args = JSONObject("""{"creation_key":"game-1","kind":"game","name":"主控说明书","modules":[{"name":"第一章","text":"门已上锁。人物仍能自行开门。"},{"name":"第二章","text":" 倒水后尚未喝水。\n围巾已归还。 "}]}""")
            val first = service().execute(config, "novex_write_card", args, listOf("创建文游卡"), "first")
            assertEquals("saved_verified", first.payload.getString("status"))
            val receipt = first.payload.getJSONArray("created_cards").getJSONObject(0)
            assertEquals("主控说明书", receipt.getString("name"))
            assertEquals("卡片仓库", receipt.getString("location"))
            assertTrue(receipt.getBoolean("open_in_app"))
            val game = first.applied!!.createdSubjects.single()
            val modules = management().inspect(config, game, null).modules
            assertEquals(2, modules.size)
            val repeat = service().execute(config, "novex_write_card", args, listOf("创建文游卡"), "first")
            assertTrue(repeat.applied!!.replayed)
            assertEquals(game, repeat.applied.createdSubjects.single())
            val regeneratedCallId = service().execute(config, "novex_write_card", args, listOf("创建文游卡"), "provider-reissued-id")
            assertTrue(regeneratedCallId.applied!!.replayed)
            assertEquals(game, regeneratedCallId.applied.createdSubjects.single())
            val second = service().execute(config, "novex_write_card", args, listOf("再创建一张文游卡"), "second")
            assertNotEquals(game, second.applied!!.createdSubjects.single())
            assertEquals(4, workspace.conversationDrafts("mine")!!.cards.size)
            val edited = service().execute(config, "novex_write_module", JSONObject().put("module_id", modules.first().id).put("name", "门与人物"), listOf("把第一章改名为门与人物"), "rename")
            assertEquals("saved_verified", edited.payload.getString("status"))
            val changed = edited.payload.getJSONArray("saved_modules").getJSONObject(0)
            assertEquals(modules.first().id, changed.getString("module_id"))
            assertEquals("门与人物", changed.getString("name"))
            assertEquals(0, changed.getInt("position"))
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
    @Test fun `new module receipt survives storage reopen and exact operation replay`() = runBlocking<Unit> {
        var db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, File(folder.root, "module-replay.db").absolutePath).allowMainThreadQueries().build()
        try {
            var workspace = NovexWorkspaceFactory.create(db, File(folder.root, "replay-media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("chat"))
            val config = NovexConversationConfigurationSnapshot("chat")
            fun service() = NovexCardFileService(workspace,
                NovexManagementService(workspace, CreativeArtifactRepository(db, CreativeArtifactFileStore(File(folder.root, "replay-artifacts")))),
                NovexCardFileOperations(NovexCardSourceModules(NovexDocumentSnapshotStore { null }) { false }),
                NovexManagementTransaction { block -> db.withTransaction { block() } })
            val target = workspace.conversationDrafts("chat")!!.cards.single { it.subject.kind == NovexContentKind.WORLD }.subject
            val args = JSONObject().put("kind", "world").put("card_id", target.id).put("name", "规则").put("text", "门保持打开。")
            val first = service().execute(config, "novex_write_module", args, listOf("加入规则"), "add-rule")
            val saved = first.payload.getJSONArray("saved_modules").getJSONObject(0)
            assertEquals("规则", saved.getString("name")); assertEquals(0, saved.getInt("position"))
            val id = saved.getString("module_id")
            db.close()
            db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, File(folder.root, "module-replay.db").absolutePath).allowMainThreadQueries().build()
            workspace = NovexWorkspaceFactory.create(db, File(folder.root, "replay-media"))
            assertEquals(listOf(id), workspace.conversationDrafts("chat")!!.completedWrites.single().changedModuleIds)
            val replay = service().execute(config, "novex_write_module", args, listOf("加入规则"), "add-rule")
            assertTrue(replay.applied!!.replayed)
            assertEquals("saved_verified", replay.payload.getString("status"))
            assertEquals(saved.toString(), replay.payload.getJSONArray("saved_modules").getJSONObject(0).toString())
            assertEquals(1, workspace.modules(com.openminis.app.data.character.ModuleOwner.world(target.id)).modules.size)
            // An old receipt remains readable without inventing an identifier from a module name.
            val raw = JSONObject(NovexConversationDraftCodec.encode(workspace.conversationDrafts("chat")!!))
            raw.getJSONArray("completedWrites").getJSONObject(0).remove("changedModuleIds")
            assertTrue(NovexConversationDraftCodec.decode(raw.toString()).completedWrites.single().changedModuleIds.isEmpty())
        } finally { db.close() }
    }

    @Test fun `new references need no invented global identifier and retain explicit edit identifiers`() {
        val operations = NovexCardFileOperations(NovexCardSourceModules(NovexDocumentSnapshotStore { null }) { false })
        fun link(source: String, id: String? = null) = JSONObject().put("kind", "game").put("card_id", source)
            .put("target_kind", "world").put("target_id", "same-world").put("purpose", "background").apply { id?.let { put("reference_id", it) } }
        fun reference(args: JSONObject) = org.json.JSONArray(operations.link(args)).getJSONObject(0).getString("reference_id")
        val first = reference(link("first"))
        assertEquals(first, reference(link("first")))
        assertNotEquals(first, reference(link("second")))
        assertEquals("existing", reference(link("first", "existing")))
        assertThrows(IllegalArgumentException::class.java) { reference(link("first").put("remove", true)) }
        assertEquals("existing", reference(link("first", "existing").put("remove", true)))
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
