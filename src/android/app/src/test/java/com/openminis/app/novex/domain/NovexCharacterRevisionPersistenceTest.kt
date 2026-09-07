package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
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
class NovexCharacterRevisionPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `revision capture preserves legacy plain text modules instead of rejecting their save`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("旧角色")).requireCharacter()
            val module = workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(person.original.id), ContentModuleType.CUSTOM,
                "旧式笔记", "原有的纯文本正文")).requireModule()
            assertEquals("原有的纯文本正文", workspace.module(module.id)!!.module.contentJson)
            assertTrue(workspace.characterRevisions(person.original.id).last().contentJson.contains("原有的纯文本正文"))
        } finally { database.close() }
    }

    @Test
    fun `migration starts history at first observed edit and a failed page save leaves no revision`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "migration.db").absolutePath).addMigrations(AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31).allowMainThreadQueries().build()
        var database = open()
        try {
            val original = NovexWorkspaceFactory.create(database, File(files.root, "media"))
                .apply(NovexCommand.CreateCharacter("旧角色", """{"summary":"已有资料"}""")).requireCharacter()
            database.openHelper.writableDatabase.execSQL("DROP TABLE novex_character_revisions")
            database.openHelper.writableDatabase.version = 28
            database.close()
            database = open()
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            assertTrue(workspace.characterRevisions(original.original.id).isEmpty())
            try {
                workspace.apply(NovexCommand.SaveCharacterPage(original.character.id, original.original.id, null, false,
                    "旧角色", "本体", "{}"))
                fail("A page without a character name must fail")
            } catch (_: IllegalArgumentException) { }
            assertTrue(workspace.characterRevisions(original.original.id).isEmpty())
            workspace.apply(NovexCommand.SaveCharacterVersion(original.character.id, original.original.id, "旧角色", "本体",
                """{"summary":"新资料"}"""))
            val revisions = workspace.characterRevisions(original.original.id)
            assertEquals(2, revisions.size)
            assertTrue(revisions.first().contentJson.contains("已有资料"))
            val parallel = workspace.apply(NovexCommand.CreateVariant(original.character.id, "平行经历")).requireVersion()
            assertEquals(1, workspace.characterRevisions(parallel.id).size)
            assertEquals(revisions, workspace.characterRevisions(original.original.id))
            workspace.apply(NovexCommand.DeleteVariant(parallel.id))
            assertTrue(workspace.characterRevisions(parallel.id).isEmpty())
        } finally { database.close() }
    }

    @Test
    fun `editing one version persists revisions without creating stages or recording unchanged saves`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "history.db").absolutePath).allowMainThreadQueries().build()
        var database = open()
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val person = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"summary":"初稿"}""", now = 10)).requireCharacter()
            val version = person.original
            workspace.apply(NovexCommand.SaveCharacterVersion(person.character.id, version.id, "伏生", version.label,
                """{"summary":"修订后的经历"}""", now = 20))
            workspace.apply(NovexCommand.SaveCharacterVersion(person.character.id, version.id, "伏生", version.label,
                """{ "summary" : "修订后的经历" }""", now = 30))
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(version.id), ContentModuleType.CUSTOM,
                "生平", """{"text":"新增史料"}""", now = 40, id = "biography"))
            val revisions = workspace.characterRevisions(version.id)
            assertEquals(listOf(1, 2, 3), revisions.map { it.sequence })
            assertEquals(listOf(10L, 20L, 40L), revisions.map { it.savedAt })
            assertEquals("初稿", JSONObject(revisions.first().contentJson).getJSONObject("profile").getString("summary"))
            assertTrue(revisions.last().contentJson.contains("新增史料"))
            assertEquals(listOf(version.id), workspace.character(person.character.id)!!.character.allVersions.map { it.id })
            assertTrue(workspace.versionRelations(version.id).isEmpty())
            database.close()
            database = open()
            workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            assertEquals(revisions, workspace.characterRevisions(version.id))
        } finally { database.close() }
    }
}
