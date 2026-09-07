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
class NovexCardSelectionExportTest {
    @get:Rule val files = TemporaryFolder()

    @Test(timeout = 60_000) fun `selected parallel version exports only its content with unresolved source relation and independent import`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val character = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"name":"伏生","summary":"本体专属内容，不应导出"}""")).requireCharacter()
            val selected = workspace.apply(NovexCommand.CreateVariant(character.character.id, "法家路线", """{"name":"法家伏生","summary":"法家正文","unknownTestExtension":{"keep":"完整扩展"}}""")).requireVersion()
            workspace.apply(NovexCommand.PutVersionRelation(NovexCharacterVersionRelation("parallel", selected.id, character.original.id, NovexCharacterVersionRelationKind.PARALLEL)))
            val world = workspace.apply(NovexCommand.CreateWorld("配套世界")).requireWorld()
            workspace.apply(NovexCommand.LinkCharacterVersion(world.id, selected.id, 0))
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(selected.id), ContentModuleType.CUSTOM, "制度", """{"kind":"article","text":"完整制度原文"}"""))
            val preview = workspace.apply(NovexCommand.ExportNativeSelection(NovexCardCopyKey(NovexCardKind.CHARACTER, character.character.id), setOf(selected.id))).requireNativeCard()
            assertFalse(preview.documentJson.contains("本体专属内容"))
            val json = JSONObject(preview.documentJson)
            assertEquals(1, json.getJSONArray("versions").length())
            assertEquals("法家正文", json.getString("summary"))
            assertTrue(json.getJSONArray("_novexExportDiagnostics").length() >= 2)
            val originalDocument = preview.documentJson
            workspace.apply(NovexCommand.SaveCharacterPage(character.character.id, selected.id, null, false, "伏生", "法家路线", """{"name":"修改后的共享原件"}"""))
            assertEquals(originalDocument, preview.documentJson)
            val imported = workspace.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(preview))))).requireNativeImport()
            val copy = workspace.character(imported.localId)!!
            assertEquals(1, copy.character.allVersions.size)
            assertNotEquals(selected.id, copy.character.original.id)
            assertTrue(copy.worldsByVersion[copy.character.original.id].orEmpty().isEmpty())
            assertTrue(copy.character.original.profileJson.contains("完整扩展"))
            assertTrue(copy.modulesByVersion.getValue(copy.character.original.id).single().contentJson.contains("完整制度原文"))
            val relation = workspace.versionRelations(copy.character.original.id).single()
            assertTrue(relation.targetVersionId.startsWith("missing:"))
            assertEquals(character.original.id, relation.unresolvedTargetVersionId)
        } finally { database.close() }
    }

    @Test(timeout = 60_000) fun `explicit dependency export remaps legacy cross card module links and preserves chosen version limit through a cycle`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val character = workspace.apply(NovexCommand.CreateCharacter("同名人物", "{}" )).requireCharacter()
            val selected = workspace.apply(NovexCommand.CreateVariant(character.character.id, "所选分身", "{}" )).requireVersion()
            val unselected = workspace.apply(NovexCommand.CreateVariant(character.character.id, "未选分身", "{}" )).requireVersion()
            val world = workspace.apply(NovexCommand.CreateWorld("关联世界")).requireWorld()
            workspace.apply(NovexCommand.LinkCharacterVersion(world.id, selected.id, 0))
            workspace.apply(NovexCommand.LinkCharacterVersion(world.id, unselected.id, 1))
            val characterModule = workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(selected.id), ContentModuleType.CUSTOM, "人物经历")).requireModule()
            val worldModule = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "世界制度")).requireModule()
            workspace.apply(NovexCommand.AddModuleReference(characterModule.id, ModuleReferenceTarget.module(worldModule.id), 0))
            workspace.apply(NovexCommand.AddModuleReference(worldModule.id, ModuleReferenceTarget.characterVersion(selected.id), 0))
            val preview = workspace.apply(NovexCommand.ExportNativeSelection(NovexCardCopyKey(NovexCardKind.CHARACTER, character.character.id), setOf(selected.id), true)).requireNativeCard()
            val json = JSONObject(preview.documentJson)
            assertEquals(1, json.getJSONArray("versions").length())
            assertEquals(2, json.getJSONObject("referenceBundle").getJSONArray("cards").length())
            workspace.apply(NovexCommand.DeleteWorld(world.id))
            workspace.apply(NovexCommand.DeleteCharacter(character.character.id))
            val unrelated = workspace.apply(NovexCommand.CreateCharacter("同名人物", "{}" )).requireCharacter()
            val imported = workspace.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(preview))).requireNativeImport()
            val copy = workspace.character(imported.localId)!!
            val newVersion = copy.character.original.id
            val newWorld = copy.worldsByVersion.getValue(newVersion).single()
            val copiedModule = copy.modulesByVersion.getValue(newVersion).single()
            val copiedWorldModule = workspace.world(newWorld.id)!!.modules.single()
            assertEquals(listOf(ModuleReferenceTarget.module(copiedWorldModule.id)), workspace.module(copiedModule.id)!!.references.map { it.target })
            assertEquals(listOf(ModuleReferenceTarget.characterVersion(newVersion)), workspace.module(copiedWorldModule.id)!!.references.map { it.target })
            assertEquals(listOf(newVersion), workspace.world(newWorld.id)!!.versions.map { it.id })
            assertNotEquals(unrelated.original.id, newVersion)
            assertFalse(copiedModule.contentJson.contains("_novexPendingReferences"))
        } finally { database.close() }
    }
}
