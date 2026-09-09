package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.creative.CreativeArtifactFileStore
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory
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
class NovexCardEditingContinuityTest {
    @get:Rule val folder = TemporaryFolder()
    private suspend fun fixture(work: suspend (NovexWorkspace, NovexManagementService, NovexCardFileService) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexTestWorkspaceFactory.create(db, File(folder.root, "media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("chat"))
            val transaction = NovexManagementTransaction { block -> db.withTransaction { block() } }
            val management = NovexManagementService(workspace,
                CreativeArtifactRepository(db, CreativeArtifactFileStore(File(folder.root, "artifacts"))), transaction)
            val service = NovexCardFileService(workspace, management,
                NovexCardFileOperations(NovexCardSourceModules(NovexDocumentSnapshotStore { null }) { false }), transaction)
            work(workspace, management, service)
        } finally { db.close() }
    }
    private val config = NovexConversationConfigurationSnapshot("chat")
    private suspend fun create(service: NovexCardFileService, kind: String) = service.execute(config, "novex_write_card",
        JSONObject("""{"creation_key":"$kind","kind":"$kind","name":"旧名称","modules":[{"name":"规则","text":"第一条：门只是上锁，仍能打开。"},{"name":"人物","text":"两人尚未见面。"}]}"""),
        listOf("创建卡片"), "create-$kind").applied!!.createdSubjects.single()

    @Test fun headerEditsKeepTheSameCardsAndModulesAndAllowStalePlanRejection() = runBlocking {
        fixture { workspace, management, service ->
            for (kind in listOf("world", "character", "game")) {
                val card = create(service, kind)
                val before = management.inspect(config, card, null).modules
                val args = JSONObject().put("kind", card.kind.managementWireName()).put("card_id", card.id)
                    .put("name", "新名称").put("summary", "新简介")
                if (kind == "game") args.put("launch_mode", "co_create_world")
                val originalArgs = args.toString()
                val result = service.execute(config, "novex_update_card", args, listOf("修改名称和简介"), "edit-$kind")
                assertTrue(result.payload.toString(), result.payload.getJSONObject("verification").getBoolean("verified"))
                assertTrue(result.applied!!.createdSubjects.isEmpty())
                assertEquals(card.id, result.payload.getJSONArray("updated_cards").getJSONObject(0).getString("id"))
                assertEquals(before.map { it.id to it.contentJson }, management.inspect(config, card, null).modules.map { it.id to it.contentJson })
                val changes = NovexCardFileOperations(NovexCardSourceModules(NovexDocumentSnapshotStore { null }) { false })
                    .updateCard(args.put("name", "过期名称"))
                val pending = management.propose(config, changes, "改名", "pending-$kind")
                service.execute(config, "novex_update_card", args.put("name", "最新名称"), listOf("改名"), "latest-$kind")
                val failure = assertThrows(IllegalArgumentException::class.java) { runBlocking { management.apply(config, pending, "") } }
                assertTrue(failure.message.orEmpty().contains("已被修改"))
                // Reissuing an old successful operation is a receipt, never an overwrite.
                val beforeReplay = NovexCardHeaderEdit.fingerprint(workspace, card)
                val replay = service.execute(config, "novex_update_card", JSONObject(originalArgs), listOf("修改名称和简介"), "edit-$kind")
                assertTrue(replay.applied!!.replayed)
                assertEquals(beforeReplay, NovexCardHeaderEdit.fingerprint(workspace, card))
            }
            assertEquals(1, workspace.worlds().size)
            assertEquals(3, workspace.conversationDrafts("chat")!!.cards.size)
        }
    }

    @Test fun appendAndRetryKeepOneModuleAndOutOfRangeMoveDoesNotMutateIt() = runBlocking {
        fixture { workspace, management, service ->
            val card = create(service, "world")
            val original = management.inspect(config, card, null).modules
            val id = original.first().id
            val args = JSONObject().put("module_id", id).put("mode", "append").put("text", "第二条：水已经倒好，但还没喝。")
            val first = service.execute(config, "novex_write_module", args, listOf("补充第二条"), "append")
            assertTrue(first.payload.getJSONObject("verification").getBoolean("verified"))
            assertTrue(first.plan.reviewText().contains("第一条：门只是上锁，仍能打开。"))
            assertTrue(first.plan.reviewText().contains("第二条：水已经倒好，但还没喝。"))
            assertTrue(service.execute(config, "novex_write_module", args, listOf("补充第二条"), "append").applied!!.replayed)
            assertEquals("第一条：门只是上锁，仍能打开。\n第二条：水已经倒好，但还没喝。", JSONObject(workspace.module(id)!!.module.contentJson).getString("text"))
            val beforeMove = management.inspect(config, card, null).modules
            val failure = assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.execute(config, "novex_move_module", JSONObject().put("module_id", id).put("position", 3), listOf("合并这些资料"), "bad-order")
            } }
            assertTrue(failure.message.orEmpty().contains("当前卡"))
            assertEquals(beforeMove, management.inspect(config, card, null).modules)
            val duplicate = JSONObject().put("kind", "world").put("card_id", card.id).put("name", "规则").put("text", "另一个规则版本")
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.execute(config, "novex_write_module", duplicate, listOf("补充规则"), "duplicate")
            } }
            assertEquals(original.size, management.inspect(config, card, null).modules.size)
            service.execute(config, "novex_write_module", duplicate.put("allow_duplicate_name", true), listOf("保留两个同名版本"), "intentional-duplicate")
            assertEquals(original.size + 1, management.inspect(config, card, null).modules.size)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.execute(config.copy(executionMode = NovexExecutionMode.READ_ONLY), "novex_update_card",
                    JSONObject().put("kind", "world").put("card_id", card.id).put("name", "不能写入"), listOf("改名"), "readonly")
            } }
        }
    }

    @Test fun kindOnlyDirectoryUsesOnlyTheConversationsOwnAndMountedObjects() = runBlocking {
        fixture { workspace, management, service ->
            val world = create(service, "world")
            create(service, "game")
            workspace.apply(NovexCommand.CreateWorld("外部未挂载世界"))
            val listing = management.inspect(config, null, null, kindFilter = NovexContentKind.WORLD)
            assertEquals(listOf(world), listing.subjects.map { it.subject })
            assertTrue(listing.draftTargets.all { it.subject.kind == NovexContentKind.WORLD })
            assertTrue(management.inspect(config, null, null, kindFilter = NovexContentKind.CREATIVE_ARTIFACT).subjects.isEmpty())
        }
    }

    /** Original incident's operation shapes, with neutral text instead of user-private content. */
    @Test fun originalCaseSequenceKeepsMultipleCardsAndEditsOneWithoutDuplicates() = runBlocking {
        fixture { workspace, management, service ->
            var current = config
            val cards = mutableListOf<NovexContentAddress>()
            repeat(4) { index ->
                val args = JSONObject().put("creation_key", "world-$index").put("kind", "world")
                    .put("name", "世界$index").put("modules", org.json.JSONArray()
                        .put(JSONObject().put("name", "规则").put("text", "原始规则$index"))
                        .put(JSONObject().put("name", "人物").put("text", "原始人物$index")))
                val created = service.execute(current, "novex_write_card", args,
                    listOf("创建四张独立世界卡"), "create-$index", "request-create")
                current = created.configuration
                cards.add(created.applied!!.createdSubjects.single())
                val retry = service.execute(current, "novex_write_card", args,
                    listOf("创建四张独立世界卡"), "retry-$index", "request-create")
                assertTrue(retry.applied!!.replayed)
                assertEquals(cards.last(), retry.applied!!.createdSubjects.single())
            }
            assertEquals(4, workspace.worlds().size)
            val untouched = cards.drop(1).associateWith { management.inspect(current, it, null).modules }
            val target = cards.first()
            val original = management.inspect(current, target, null).modules
            val rule = original.first().id
            val person = original.last().id
            for (badPosition in listOf(3, 4, 5)) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    service.execute(current, "novex_move_module", JSONObject().put("module_id", rule)
                        .put("position", badPosition), listOf("整理模块顺序"), "bad-$badPosition")
                } }
                assertEquals(original, management.inspect(current, target, null).modules)
            }
            val duplicate = JSONObject().put("kind", "world").put("card_id", target.id)
                .put("name", "规则").put("text", "补充内容")
            val duplicateError = assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.execute(current, "novex_write_module", duplicate, listOf("补充已有规则"), "duplicate")
            } }
            assertTrue(duplicateError.message.orEmpty().contains(rule))
            val append = JSONObject().put("module_id", rule).put("mode", "append").put("text", "补充内容")
            service.execute(current, "novex_write_module", append, listOf("补充已有规则"), "append-one")
            service.execute(current, "novex_write_module", append, listOf("补充已有规则"), "append-one")
            assertEquals("原始规则0\n补充内容", JSONObject(workspace.module(rule)!!.module.contentJson).getString("text"))
            service.execute(current, "novex_move_module", JSONObject().put("module_id", person).put("position", 0),
                listOf("人物排在规则前面"), "move-valid")
            assertEquals(listOf(person, rule), management.inspect(current, target, null).modules.map { it.id })
            val deletion = org.json.JSONArray().put(JSONObject().put("operation", "delete_module").put("module_id", person)).toString()
            val plan = management.propose(current, deletion, "删除人物模块", "delete-plan")
            management.apply(current, plan, "删除人物模块")
            assertTrue(management.apply(current, plan, "删除人物模块").replayed)
            for (wrongId in listOf(person, "delete-plan")) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    management.propose(current, org.json.JSONArray().put(JSONObject().put("operation", "delete_module")
                        .put("module_id", wrongId)).toString(), "删除模块", "retry-delete-$wrongId")
                } }
            }
            assertEquals(listOf(rule), management.inspect(current, target, null).modules.map { it.id })
            assertEquals("原始规则0\n补充内容", JSONObject(workspace.module(rule)!!.module.contentJson).getString("text"))
            untouched.forEach { (card, modules) -> assertEquals(modules, management.inspect(current, card, null).modules) }
            val listed = management.inspect(current, null, null, kindFilter = NovexContentKind.WORLD)
            assertEquals(cards.toSet(), listed.subjects.map { it.subject }.toSet())
            assertEquals(4, workspace.worlds().size)
        }
    }

}
