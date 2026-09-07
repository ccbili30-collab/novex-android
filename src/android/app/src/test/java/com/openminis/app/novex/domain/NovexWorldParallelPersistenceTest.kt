package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.*
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
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
@Config(application = Application::class, sdk = [28])
class NovexWorldParallelPersistenceTest {
    @get:Rule val files = TemporaryFolder()
    private fun openDatabase() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
        File(files.root, "parallel.db").absolutePath).allowMainThreadQueries().build()

    @Test(timeout = 60_000) fun `batch parallel route keeps shared people and remaps selected same person versions after reopen`() = runBlocking {
        var database = openDatabase()
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("儒家世界", "原路线内容")).requireWorld()
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"name":"伏生","summary":"年轻时的记忆"}""")).requireCharacter()
            val older = workspace.apply(NovexCommand.CreateVariant(person.character.id, "晚年", """{"name":"伏生","summary":"晚年独有经历"}""")).requireVersion()
            val shared = workspace.apply(NovexCommand.CreateCharacter("记言人", "{}" )).requireCharacter()
            val unrelated = workspace.apply(NovexCommand.CreateCharacter("另一作品伏生", "{}" )).requireCharacter()
            listOf(person.original.id, older.id, shared.original.id).forEachIndexed { index, id -> workspace.apply(NovexCommand.LinkCharacterVersion(world.id, id, index)) }
            val oldModule = workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(older.id), ContentModuleType.CUSTOM, "晚年资料", """{"kind":"article","text":"不能同步到早年版本"}""")).requireModule()
            val worldModule = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "制度原文", """{"kind":"article","text":"完整制度"}""")).requireModule()
            workspace.apply(NovexCommand.AddModuleReference(oldModule.id, ModuleReferenceTarget.module(worldModule.id), 0))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("rule", NovexContentAddress.world(world.id), NovexReferenceTarget(NovexContentAddress.characterVersion(older.id), oldModule.id), NovexReferencePurpose.RULES)))
            val plan = workspace.prepareWorldParallel(world.id)
            assertEquals(setOf(person.original.id, older.id, shared.original.id), plan.people.map { it.versionId }.toSet())
            assertFalse(plan.people.any { it.characterId == unrelated.character.id })
            val result = (workspace.apply(NovexCommand.CreateParallelWorld(plan, "法家路线", setOf(person.original.id, older.id))) as NovexChange.WorldParallelCreated).result
            database.close(); database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val copy = workspace.world(result.worldId)!!
            assertEquals("法家路线", copy.world.name)
            assertEquals("原路线内容", copy.world.overview)
            assertEquals(setOf(result.versions.getValue(person.original.id), result.versions.getValue(older.id), shared.original.id), copy.versions.map { it.id }.toSet())
            assertEquals(setOf(person.original.id, older.id, shared.original.id), workspace.world(world.id)!!.versions.map { it.id }.toSet())
            val all = workspace.character(person.character.id)!!.character.allVersions
            assertEquals(4, all.size)
            assertTrue(all.single { it.id == result.versions.getValue(person.original.id) }.profileJson.contains("年轻时的记忆"))
            assertTrue(all.single { it.id == result.versions.getValue(older.id) }.profileJson.contains("晚年独有经历"))
            result.versions.forEach { (source, target) -> assertEquals(source, workspace.versionRelations(target).single().targetVersionId) }
            val targetModule = workspace.modules(ModuleOwner.characterVersion(result.versions.getValue(older.id))).modules.single()
            assertEquals(copy.modules.single().id, workspace.module(targetModule.id)!!.references.single().targetId)
            val ref = workspace.referencesFrom(NovexContentAddress.world(copy.world.id)).single()
            assertEquals(result.versions.getValue(older.id), ref.target.subject.id)
            assertEquals(targetModule.id, ref.target.moduleId)
            assertEquals(JSONObject(copy.world.legacySnapshotJson!!).getJSONObject("_novexWorldSeries").getString("id"),
                JSONObject(workspace.world(world.id)!!.world.legacySnapshotJson!!).getJSONObject("_novexWorldSeries").getString("id"))
            val exported = workspace.apply(NovexCommand.ExportNativeSelection(NovexCardCopyKey(NovexCardKind.WORLD, copy.world.id), includeDependencies = true)).requireNativeCard()
            val importedId = workspace.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(exported))).requireNativeImport().localId
            val importedSeries = JSONObject(workspace.world(importedId)!!.world.legacySnapshotJson!!).getJSONObject("_novexWorldSeries").getString("id")
            assertNotEquals(result.seriesId, importedSeries)
            val members = workspace.worlds().filter { card -> runCatching {
                JSONObject(card.world.legacySnapshotJson ?: "{}").optJSONObject("_novexWorldSeries")?.optString("id") == importedSeries
            }.getOrDefault(false) }
            assertEquals(2, members.size)
            val detached = (workspace.apply(NovexCommand.CopyCard(workspace.prepareCardCopy(NovexCardCopyKey(NovexCardKind.WORLD, copy.world.id)))) as NovexChange.CardsCopied).result
            assertFalse(JSONObject(workspace.world(detached.root.id)!!.world.legacySnapshotJson!!).has("_novexWorldSeries"))
            workspace.apply(NovexCommand.DeleteWorld(world.id))
            assertNotNull(workspace.world(copy.world.id))
            assertTrue(workspace.cardRevisions(NovexContentAddress.world(copy.world.id)).isNotEmpty())
        } finally { database.close() }
    }

    @Test(timeout = 60_000) fun `changed companion aborts old parallel preview before creating a world or series`() = runBlocking {
        val database = openDatabase()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("测试世界")).requireWorld()
            val person = workspace.apply(NovexCommand.CreateCharacter("测试人物", "{}" )).requireCharacter()
            workspace.apply(NovexCommand.LinkCharacterVersion(world.id, person.original.id, 0))
            val plan = workspace.prepareWorldParallel(world.id)
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(person.original.id), ContentModuleType.CUSTOM, "刚补充的资料"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                workspace.apply(NovexCommand.CreateParallelWorld(plan, "旧预览", setOf(person.original.id)))
            } }
            assertEquals(listOf(world.id), workspace.worlds().map { it.world.id })
            assertNull(workspace.world(world.id)!!.world.legacySnapshotJson)
            assertEquals(1, workspace.character(person.character.id)!!.character.allVersions.size)
        } finally { database.close() }
    }
}
