package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.creative.CreativeArtifactFileStore
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
@Config(application = Application::class, sdk = [28])
class NovexManagementRecoveryTest {
    @get:Rule val files = TemporaryFolder()
    private fun openDatabase() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        File(files.root, "recovery.db").absolutePath).allowMainThreadQueries().build()
    private fun service(database: AppDatabase, workspace: NovexWorkspace, interrupt: Boolean = false) = NovexManagementService(
        workspace, CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))),
        NovexManagementTransaction { work -> database.withTransaction {
            work()
            if (interrupt) error("提交前中断")
        } })

    @Test fun `three complete chapters survive lost receipt and restart without another side effect`() = runBlocking {
        var database = openDatabase()
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("chat"))
            val config = NovexConversationConfigurationSnapshot("chat")
            val chapters = listOf("第一章\n门已上锁，人物仍可自行开门。", "第二章\n倒水后尚未喝水。", "第三章\n围巾借出后已经归还，时间仍是傍晚。")
            val operations = JSONArray().put(JSONObject().put("operation", "create_game").put("name", "测试文游")
                .put("modules", JSONArray(chapters.mapIndexed { i, content -> JSONObject().put("module_type", "custom")
                    .put("name", "第 ${i + 1} 章").put("content_json", JSONObject().put("kind", "article").put("text", content)) }))).toString()
            val plan = service(database, workspace).propose(config, operations, "把三章做成文游卡", "stable-operation")
            assertEquals(plan, service(database, workspace).propose(config, operations, "把三章做成文游卡", plan.id))
            val before = workspace.conversationDrafts("chat")!!.cards
            val first = service(database, workspace).apply(config, plan, "把三章做成文游卡")
            val target = first.createdSubjects.single()
            val revisions = workspace.cardRevisions(target)
            // Simulate a committed write with its tool response never delivered.
            database.close(); database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val restored = service(database, workspace).planForExecution(config, plan.id)!!
            assertNull(service(database, workspace).pendingPlan(config, plan.id))
            val replay = service(database, workspace).apply(config, restored, "继续检查刚才的结果")
            assertTrue(replay.replayed)
            assertEquals(first.appliedChanges, replay.appliedChanges)
            assertEquals(first.createdSubjects, replay.createdSubjects)
            assertTrue(replay.changes.isEmpty())
            assertEquals(before.map { if (it.subject == target) it.copy(isPrivate = false) else it }, workspace.conversationDrafts("chat")!!.cards)
            assertEquals(revisions, workspace.cardRevisions(target))
            assertEquals(1, workspace.conversationDrafts("chat")!!.completedWrites.size)
            val managed = config.copy(managedSubjects = listOf(ManagedSubject(target, ManagedAccess.READ_ONLY)))
            val modules = service(database, workspace).inspect(managed, target, null).modules
            assertEquals(chapters.size, modules.size)
            modules.forEachIndexed { i, module ->
                val read = service(database, workspace).inspect(managed, target, module.id)
                assertEquals(chapters[i], JSONObject(read.selectedModule!!.module.contentJson).getString("text"))
            }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service(database, workspace).propose(config, """[{"operation":"create_game","name":"另一内容"}]""", "创建文游", plan.id)
            } }
            assertEquals(before.map { if (it.subject == target) it.copy(isPrivate = false) else it }, workspace.conversationDrafts("chat")!!.cards)
            assertNull(service(database, workspace).planForExecution(config.copy(conversationId = "other"), plan.id))
        } finally { database.close() }
    }

    @Test fun `two conversations cannot apply a confirmed stale shared module overwrite`() = runBlocking {
        val database = openDatabase()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("共享测试世界")).requireWorld()
            val module = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM,
                "规则", """{"kind":"article","text":"原文"}""")).requireModule()
            for (id in listOf("a", "b")) workspace.apply(NovexCommand.EnsureConversationDrafts(id))
            val a = NovexConversationConfigurationSnapshot("a", managedSubjects = listOf(ManagedSubject(NovexContentAddress.world(world.id), ManagedAccess.EDIT)))
            val b = a.copy(conversationId = "b")
            val service = service(database, workspace)
            fun change(text: String) = """[{"operation":"update_module","module_id":"${module.id}","content_json":{"kind":"article","text":"$text"}}]"""
            val first = service.propose(a, change("甲修改"), "修改规则", "first")
            val stale = service.propose(b, change("乙旧覆盖"), "修改规则", "stale")
            service.apply(a, first, "")
            val after = workspace.module(module.id)!!.module
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.apply(b, stale, "") } }
            assertEquals(after, workspace.module(module.id)!!.module)
            assertTrue(workspace.conversationDrafts("b")!!.completedWrites.isEmpty())
            assertNotNull(service.pendingPlan(b, stale.id))
            val fresh = service.propose(b, change("乙根据新文修改"), "修改规则", "fresh")
            service.apply(b, fresh, "")
            assertEquals("乙根据新文修改", JSONObject(workspace.module(module.id)!!.module.contentJson).getString("text"))
        } finally { database.close() }
    }

    @Test fun `rolled back write also rolls back completion and remains retryable`() = runBlocking {
        var database = openDatabase()
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("chat"))
            val config = NovexConversationConfigurationSnapshot("chat")
            val plan = service(database, workspace).propose(config,
                """[{"operation":"create_world","name":"重试世界","overview":"完整正文"}]""", "创建世界卡", "rollback-plan")
            assertThrows(IllegalStateException::class.java) { runBlocking {
                service(database, workspace, interrupt = true).apply(config, plan, "创建世界卡")
            } }
            database.close(); database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            assertTrue(workspace.conversationDrafts("chat")!!.completedWrites.isEmpty())
            assertEquals(plan, service(database, workspace).pendingPlan(config, plan.id))
            val result = service(database, workspace).apply(config, plan, "创建世界卡")
            assertFalse(result.replayed)
            assertEquals("完整正文", workspace.world(result.createdSubjects.single().id)!!.world.overview)
            assertEquals(1, workspace.conversationDrafts("chat")!!.completedWrites.size)
        } finally { database.close() }
    }
}
