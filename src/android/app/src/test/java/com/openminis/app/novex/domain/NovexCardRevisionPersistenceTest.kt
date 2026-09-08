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
class NovexCardRevisionPersistenceTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun `world and game edits preserve content order and references across reopen without duplicating unchanged saves`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "history.db").absolutePath).allowMainThreadQueries().build()
        var db = open()
        try {
            var workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("原世界", "原正文", now = 10)).requireWorld()
            val address = NovexContentAddress.world(world.id)
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "新正文"), now = 20))
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "新正文"), now = 30))
            assertEquals(listOf(10L, 20L), workspace.cardRevisions(address).map { it.savedAt })
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "甲", "原文甲", id = "a"))
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "乙", "原文乙", id = "b"))
            val before = workspace.cardRevisions(address).last()
            workspace.apply(NovexCommand.MoveModule("b", 0))
            val after = workspace.cardRevisions(address).last()
            val diff = NovexRevisionDifference.compare(before.contentJson, after.contentJson)
            assertTrue(diff.any { it.path == "/modules/0/id" && it.after == "\"b\"" })
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "游戏", "原规则", now = 50)).requireInteractiveFiction()
            val gameAddress = NovexContentAddress.interactiveFiction(game.id)
            workspace.apply(NovexCommand.SaveInteractiveFictionPage(game.id, "游戏", "新规则", playerIdentity = "记言人", now = 60))
            val reference = NovexCardReference("background", gameAddress, NovexReferenceTarget(address), NovexReferencePurpose.BACKGROUND)
            workspace.apply(NovexCommand.PutCardReference(reference))
            assertEquals(1, JSONObject(workspace.cardRevisions(gameAddress).last().contentJson).getJSONArray("references").length())
            workspace.apply(NovexCommand.RemoveCardReference(reference.id))
            assertEquals(0, JSONObject(workspace.cardRevisions(gameAddress).last().contentJson).getJSONArray("references").length())
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.world(world.id), MediaAssetSlot.WORLD_BACKGROUND, byteArrayOf(1, 2, 3), "image/png"))
            val withImage = workspace.cardRevisions(address).last()
            assertTrue(JSONObject(withImage.contentJson).getJSONObject("imageSignatures").has("WORLD_BACKGROUND"))
            workspace.apply(NovexCommand.DetachImage(ModuleOwner.world(world.id), MediaAssetSlot.WORLD_BACKGROUND))
            assertFalse(JSONObject(workspace.cardRevisions(address).last().contentJson).getJSONObject("imageSignatures").has("WORLD_BACKGROUND"))
            val worlds = workspace.cardRevisions(address); val games = workspace.cardRevisions(gameAddress)
            db.close(); db = open(); workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            assertEquals(worlds, workspace.cardRevisions(address)); assertEquals(games, workspace.cardRevisions(gameAddress))
            workspace.apply(NovexCommand.DeleteWorld(world.id))
            assertTrue(workspace.cardRevisions(address).isEmpty())
            assertEquals(games, workspace.cardRevisions(gameAddress))
            assertEquals("原规则", JSONObject(games.first().contentJson).getString("summary"))
            assertEquals("记言人", JSONObject(games.last().contentJson).getString("playerIdentity"))
        } finally { db.close() }
    }
    @Test fun `migration does not invent old revisions and failed edit rolls baseline back`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "migration.db").absolutePath).addMigrations(AppDatabase.MIGRATION_30_31, AppDatabase.MIGRATION_31_32).allowMainThreadQueries().build()
        var db = open()
        try {
            var workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("旧世界", "旧正文")).requireWorld()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "旧文游", "旧规则")).requireInteractiveFiction()
            db.openHelper.writableDatabase.execSQL("DROP TABLE novex_world_revisions")
            db.openHelper.writableDatabase.execSQL("DROP TABLE novex_game_revisions")
            restoreVersion31LibraryTable(db.openHelper.writableDatabase)
            db.openHelper.writableDatabase.version = 30
            db.close(); db = open(); workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val address = NovexContentAddress.world(world.id)
            assertTrue(workspace.cardRevisions(address).isEmpty())
            try { workspace.apply(NovexCommand.SaveWorldPage(world.id, "")); fail("Blank name must fail") }
            catch (_: IllegalArgumentException) { }
            assertTrue(workspace.cardRevisions(address).isEmpty())
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "新正文")))
            assertEquals(2, workspace.cardRevisions(address).size)
            assertTrue(workspace.cardRevisions(address).first().contentJson.contains("旧正文"))
            val gameAddress = NovexContentAddress.interactiveFiction(game.id)
            assertTrue(workspace.cardRevisions(gameAddress).isEmpty())
            workspace.apply(NovexCommand.SaveInteractiveFictionPage(game.id, "旧文游", "新规则"))
            assertEquals(2, workspace.cardRevisions(gameAddress).size)
        } finally { db.close() }
    }
    @Test fun `difference distinguishes absent null arrays and escaped keys`() {
        val changes = NovexRevisionDifference.compare("""{"a/b":null,"items":["甲","乙"]}""", """{"items":["乙"],"new":null}""")
        assertTrue(changes.any { it.path == "/a~1b" && it.before == "null" && it.after == null })
        assertTrue(changes.any { it.path == "/new" && it.before == null && it.after == "null" })
        assertTrue(changes.any { it.path == "/items/1" && it.after == null })
    }
}
