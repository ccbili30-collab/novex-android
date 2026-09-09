package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.character.*
import com.openminis.app.novex.adapter.*
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
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexCardDirectoryJourneyTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun version32DatabaseMigratesWithoutChangingCardAndRepeatedBackfillIsStable() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "migration.db").absolutePath
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, path)
            .addMigrations(AppDatabase.MIGRATION_32_33, AppDatabase.MIGRATION_33_34, AppDatabase.MIGRATION_34_35).allowMainThreadQueries().build()
        var db = open()
        try {
            val store = NovexCardDirectoryStore(File(files.root, "cards"), syncDirectory = {})
            var workspace = NovexWorkspaceFactory.createWithDirectoryStore(db, File(files.root, "media"), store)
            val world = workspace.apply(NovexCommand.CreateWorld("迁移旧卡", "保留旧正文")).requireWorld()
            val before = workspace.world(world.id)!!.world
            db.openHelper.writableDatabase.execSQL("DROP TABLE novex_card_directories")
            db.openHelper.writableDatabase.version = 32
            db.close(); db = open()
            assertTrue(db.novexCardDirectoryDao().list().isEmpty())
            workspace = NovexWorkspaceFactory.createWithDirectoryStore(db, File(files.root, "media"), store)
            assertEquals(before, workspace.world(world.id)!!.world)
            assertEquals(0, workspace.migrateCardDirectories())
            val directory = workspace.cardDirectory(NovexContentAddress.world(world.id))
            assertNotNull(directory)
            assertEquals(0, workspace.migrateCardDirectories())
            assertEquals(directory, workspace.cardDirectory(NovexContentAddress.world(world.id)))
            assertEquals(before, workspace.world(world.id)!!.world)
        } finally { db.close() }
    }

    @Test fun realCommandsCreateOwnedFoldersUpdateModulesAndKeepCopiesIndependent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = NovexCardDirectoryStore(File(files.root, "cards"), syncDirectory = {})
            val workspace = NovexWorkspaceFactory.createWithDirectoryStore(db, File(files.root, "media"), store)
            val world = workspace.apply(NovexCommand.CreateWorld("雾镇", "旧正文")).requireWorld()
            val address = NovexContentAddress.world(world.id)
            val initial = requireNotNull(workspace.cardDirectory(address))
            assertTrue(File(initial, "card.json").isFile)
            val module = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "邮局", """{"text":"送信规则"}""")).requireModule()
            val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jI9sAAAAASUVORK5CYII=")
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.contentModule(module.id), MediaAssetSlot.MODULE_IMAGE, png, "image/png", source = "创作库"))
            val imageDirectory = requireNotNull(workspace.cardDirectory(address))
            assertTrue(imageDirectory.walkTopDown().any { it.extension == "png" && it.readBytes().contentEquals(png) })
            assertTrue(File(imageDirectory, "local.json").readText().contains("创作库"))
            workspace.apply(NovexCommand.SaveModule(module.id, "邮局", """{"text":"新的送信规则"}"""))
            assertTrue(File(workspace.cardDirectory(address), "card.json").readText().contains("新的送信规则"))
            val exported = workspace.apply(NovexCommand.ExportNativeWorld(world.id)).requireNativeCard()
            val copiedId = workspace.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(exported))).requireNativeImport().localId
            val copyAddress = NovexContentAddress.world(copiedId)
            val copyDirectory = requireNotNull(workspace.cardDirectory(copyAddress))
            assertNotEquals(copyDirectory.parentFile, imageDirectory.parentFile)
            workspace.apply(NovexCommand.DeleteWorld(world.id))
            assertNull(workspace.cardDirectory(address))
            assertTrue(copyDirectory.walkTopDown().any { it.extension == "png" && it.readBytes().contentEquals(png) })
            assertTrue(File(requireNotNull(workspace.cardDirectory(copyAddress)), "card.json").readText().contains("新的送信规则"))
            val role = workspace.apply(NovexCommand.CreateCharacter("阿予")).requireCharacter()
            assertNotNull(workspace.cardDirectory(NovexContentAddress.characterVersion(role.original.id)))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "入镇", "冒险")).requireInteractiveFiction()
            assertNotNull(workspace.cardDirectory(NovexContentAddress.interactiveFiction(game.id)))
            val before = requireNotNull(workspace.cardDirectory(copyAddress))
            assertTrue(runCatching { db.withTransaction {
                workspace.apply(NovexCommand.SaveWorld(workspace.world(copiedId)!!.world.copy(overview = "不应发布")))
                error("模拟对话事务失败")
            } }.isFailure)
            assertEquals(before, workspace.cardDirectory(copyAddress))
        } finally { db.close() }
    }
}
