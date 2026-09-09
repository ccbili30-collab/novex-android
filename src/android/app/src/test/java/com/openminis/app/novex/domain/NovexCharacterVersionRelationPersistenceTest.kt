package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexCharacterVersionRelationPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `branch of imported version exports distinct identities and restores its parallel relation`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"_novexSourceId":"foreign-version","summary":"原卡资料"}""")).requireCharacter()
            val world = workspace.apply(NovexCommand.CreateWorld("平行世界")).requireWorld()
            workspace.apply(NovexCommand.LinkCharacterVersion(world.id, person.original.id, 0))
            val branch = workspace.apply(NovexCommand.SaveAsWorldVariant(person.original.id, world.id)).requireVersion()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("branch-background", NovexContentAddress.characterVersion(branch.id),
                NovexReferenceTarget(NovexContentAddress.characterVersion(person.original.id)), NovexReferencePurpose.BACKGROUND)))
            val exported = workspace.apply(NovexCommand.ExportNativeCharacter(person.character.id)).requireNativeCard()
            val imported = workspace.apply(NovexCommand.ImportNativeCard(com.openminis.app.data.character.NovexCardTransferParser.parse(exported))).requireNativeImport()
            val copy = workspace.character(imported.localId)!!.character
            assertEquals(2, copy.allVersions.size)
            assertEquals(copy.original.id, workspace.versionRelations(copy.variants.single().id).single().targetVersionId)
            assertEquals(NovexContentAddress.characterVersion(copy.original.id),
                workspace.referencesFrom(NovexContentAddress.characterVersion(copy.variants.single().id)).single().target.subject)
        } finally { database.close() }
    }

    @Test
    fun `copy keeps internal module and purposeful links within the copied person`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val young = workspace.apply(NovexCommand.CreateVariant(person.character.id, "青年")).requireVersion()
            val owner = com.openminis.app.data.character.ModuleOwner.characterVersion(person.original.id)
            val otherOwner = com.openminis.app.data.character.ModuleOwner.characterVersion(young.id)
            workspace.apply(NovexCommand.AddModule(owner, com.openminis.app.data.character.ContentModuleType.CUSTOM, "成年记录", id = "adult-module"))
            workspace.apply(NovexCommand.AddModule(otherOwner, com.openminis.app.data.character.ContentModuleType.CUSTOM, "青年记录", id = "young-module"))
            workspace.apply(NovexCommand.AddModuleReference("adult-module", com.openminis.app.data.character.ModuleReferenceTarget.module("young-module"), 0))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("earlier-notes", NovexContentAddress.characterVersion(person.original.id),
                NovexReferenceTarget(NovexContentAddress.characterVersion(young.id), "young-module"), NovexReferencePurpose.BACKGROUND,
                sourceModuleId = "adult-module")))
            val copy = workspace.apply(NovexCommand.DuplicateCharacter(person.character.id)).requireCharacter()
            val copiedAdult = workspace.modules(com.openminis.app.data.character.ModuleOwner.characterVersion(copy.original.id)).modules.single()
            val copiedYoung = workspace.modules(com.openminis.app.data.character.ModuleOwner.characterVersion(copy.variants.single().id)).modules.single()
            val relation = workspace.referencesFrom(NovexContentAddress.characterVersion(copy.original.id)).single()
            assertEquals(copiedAdult.id, relation.sourceModuleId)
            assertEquals(copy.variants.single().id, relation.target.subject.id)
            assertEquals(copiedYoung.id, relation.target.moduleId)
            assertEquals(copiedYoung.id, workspace.module(copiedAdult.id)!!.references.single().targetId)
            workspace.apply(NovexCommand.DeleteCharacter(person.character.id))
            assertEquals(NovexReferenceTargetStatus.AVAILABLE, workspace.referenceStatus(relation.target))
        } finally { database.close() }
    }

    @Test
    fun `management can edit owned version relations without granting access to another version`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val young = workspace.apply(NovexCommand.CreateVariant(person.character.id, "青年", """{"summary":"青年私有经历"}""")).requireVersion()
            val source = NovexContentAddress.characterVersion(person.original.id)
            val configuration = NovexConversationConfigurationSnapshot("chat", managedSubjects = listOf(ManagedSubject(source, ManagedAccess.EDIT)))
            val artifacts = object : NovexManagementArtifactPort {
                override suspend fun exists(artifactId: String) = false
                override suspend fun describe(artifactId: String): NovexManagedArtifactDescription? = null
                override suspend fun attach(attachment: CreativeArtifactAttachment) = error("不操作文件")
                override suspend fun detach(attachment: CreativeArtifactAttachment) = error("不操作文件")
            }
            val service = NovexManagementService(workspace, artifacts)
            val changes = """[{"operation":"put_version_relation","relation_id":"stages","source_version_id":"${person.original.id}","target_version_id":"${young.id}","relation_kind":"earlier_stage"}]"""
            val plan = service.propose(configuration, changes, "把青年设为更早人生阶段", "plan")
            service.apply(configuration, plan, "")
            val receipt = service.inspect(configuration, source, null).toToolJson()
            assertEquals(1, receipt.getJSONArray("version_relations").length())
            assertFalse(receipt.toString().contains("青年私有经历"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.inspect(configuration, NovexContentAddress.characterVersion(young.id), null)
            } }
            val remove = service.propose(configuration, """[{"operation":"remove_version_relation","relation_id":"stages","source_version_id":"${person.original.id}"}]""", "移除这项关系", "remove")
            service.apply(configuration, remove, "")
            assertTrue(workspace.versionRelations(person.original.id).isEmpty())
            assertNotNull(workspace.characterForVersion(young.id))
        } finally { database.close() }
    }

    @Test
    fun `whole person copy remaps stages and world branch records a parallel origin`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val young = workspace.apply(NovexCommand.CreateVariant(person.character.id, "青年")).requireVersion()
            workspace.apply(NovexCommand.PutVersionRelation(NovexCharacterVersionRelation("stages", person.original.id, young.id,
                NovexCharacterVersionRelationKind.EARLIER_STAGE)))
            val copy = workspace.apply(NovexCommand.DuplicateCharacter(person.character.id)).requireCharacter()
            val copiedRelation = workspace.versionRelations(copy.original.id).single()
            assertEquals(copy.variants.single().id, copiedRelation.targetVersionId)
            assertNotEquals("stages", copiedRelation.id)
            val world = workspace.apply(NovexCommand.CreateWorld("另一个世界")).requireWorld()
            workspace.apply(NovexCommand.LinkCharacterVersion(world.id, young.id, 0))
            val branch = workspace.apply(NovexCommand.SaveAsWorldVariant(sourceVersionId = young.id, worldId = world.id)).requireVersion()
            val branchRelation = workspace.versionRelations(branch.id).single()
            assertEquals(young.id, branchRelation.targetVersionId)
            assertEquals(NovexCharacterVersionRelationKind.PARALLEL, branchRelation.kind)
            assertEquals(young.profileJson, branch.profileJson)
            assertEquals(1, workspace.versionRelations(person.original.id).size)
        } finally { database.close() }
    }

    @Test
    fun `missing version stays missing through exchange even if foreign id matches another local version`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val young = workspace.apply(NovexCommand.CreateVariant(person.character.id, "青年")).requireVersion()
            workspace.apply(NovexCommand.PutVersionRelation(NovexCharacterVersionRelation("stages", person.original.id, young.id,
                NovexCharacterVersionRelationKind.EARLIER_STAGE, """{"future":{"keep":true}}""")))
            workspace.apply(NovexCommand.DeleteVariant(young.id))
            val exported = workspace.apply(NovexCommand.ExportNativeCharacter(person.character.id)).requireNativeCard()
            val decoded = com.openminis.app.data.character.NovexCardPackageCodec.decode(com.openminis.app.data.character.NovexCardPackageCodec.encode(exported))
            val imported = workspace.apply(NovexCommand.ImportNativeCard(com.openminis.app.data.character.NovexCardTransferParser.parse(decoded))).requireNativeImport()
            val copy = workspace.character(imported.localId)!!.character
            val relation = workspace.versionRelations(copy.original.id).single()
            assertEquals(young.id, relation.unresolvedTargetVersionId)
            assertNotEquals(young.id, relation.targetVersionId)
            assertNull(workspace.characterForVersion(relation.targetVersionId))
            assertTrue(org.json.JSONObject(relation.preservedJson).getJSONObject("future").getBoolean("keep"))
        } finally { database.close() }
    }

    @Test
    fun `native exchange maps every version relation into the imported person`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val young = workspace.apply(NovexCommand.CreateVariant(person.character.id, "青年")).requireVersion()
            workspace.apply(NovexCommand.PutVersionRelation(NovexCharacterVersionRelation("stages", person.original.id, young.id,
                NovexCharacterVersionRelationKind.EARLIER_STAGE)))
            val exported = workspace.apply(NovexCommand.ExportNativeCharacter(person.character.id)).requireNativeCard()
            val decoded = com.openminis.app.data.character.NovexCardPackageCodec.decode(com.openminis.app.data.character.NovexCardPackageCodec.encode(exported))
            val imported = workspace.apply(NovexCommand.ImportNativeCard(com.openminis.app.data.character.NovexCardTransferParser.parse(decoded))).requireNativeImport()
            val copy = workspace.character(imported.localId)!!.character
            val relation = workspace.versionRelations(copy.original.id).single()
            assertEquals(copy.original.id, relation.sourceVersionId)
            assertEquals(copy.variants.single().id, relation.targetVersionId)
            assertNotEquals(young.id, relation.targetVersionId)
            assertNotEquals("stages", relation.id)
            assertEquals(NovexCharacterVersionRelationKind.EARLIER_STAGE, relation.kind)
            workspace.apply(NovexCommand.DeleteVariant(young.id))
            assertEquals(listOf(relation), workspace.versionRelations(copy.original.id))
        } finally { database.close() }
    }

    @Test
    fun `migration adds relationship storage without inventing stages for existing variants`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "migration.db").absolutePath).addMigrations(AppDatabase.MIGRATION_27_28, AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31, AppDatabase.MIGRATION_31_32, AppDatabase.MIGRATION_32_33, AppDatabase.MIGRATION_33_34, AppDatabase.MIGRATION_34_35).allowMainThreadQueries().build()
        var database = open()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("既有人物", """{"summary":"原有经历"}""")).requireCharacter()
            val variant = workspace.apply(NovexCommand.CreateVariant(person.character.id, "旧版本")).requireVersion()
            database.openHelper.writableDatabase.execSQL("DROP TABLE novex_character_version_relations")
            restoreVersion31LibraryTable(database.openHelper.writableDatabase)
            database.openHelper.writableDatabase.version = 27
            database.close()
            database = open()
            val restored = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            assertEquals(person.original.profileJson, restored.character(person.character.id)!!.character.original.profileJson)
            assertEquals(variant.id, restored.character(person.character.id)!!.character.variants.single().id)
            assertTrue(restored.versionRelations(variant.id).isEmpty())
        } finally { database.close() }
    }

    @Test
    fun `version relations persist separately from profiles and retain missing targets after deletion`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "versions.db").absolutePath).allowMainThreadQueries().build()
        var database = open()
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"summary":"本体的成年经历"}""")).requireCharacter()
            val young = workspace.apply(NovexCommand.CreateVariant(person.character.id, "青年", """{"summary":"青年自己的经历"}""")).requireVersion()
            val relation = NovexCharacterVersionRelation("stages", person.original.id, young.id, NovexCharacterVersionRelationKind.EARLIER_STAGE)
            workspace.apply(NovexCommand.PutVersionRelation(relation))
            database.close()
            database = open()
            workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            assertEquals(listOf(relation), workspace.versionRelations(person.original.id))
            assertEquals(listOf(relation), workspace.versionRelations(young.id))
            assertEquals(person.original.profileJson, workspace.character(person.character.id)!!.character.original.profileJson)
            assertEquals(young.profileJson, workspace.character(person.character.id)!!.character.allVersions.single { it.id == young.id }.profileJson)
            workspace.apply(NovexCommand.DeleteVariant(young.id))
            assertEquals(listOf(relation), workspace.versionRelations(person.original.id))
            assertNull(workspace.characterForVersion(young.id))
            workspace.apply(NovexCommand.DeleteCharacter(person.character.id))
            assertTrue(workspace.versionRelations(person.original.id).isEmpty())
        } finally { database.close() }
    }
}
