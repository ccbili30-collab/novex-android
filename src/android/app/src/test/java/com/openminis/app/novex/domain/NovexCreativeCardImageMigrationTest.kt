package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.character.*
import com.openminis.app.data.creative.*
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory
import kotlinx.coroutines.runBlocking
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
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexCreativeCardImageMigrationTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun oldAttachmentsBecomeOwnedAndNeitherSourceDeletionNorRestartRecreatesRemovedPictures() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexTestWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("旧图", "正文")).requireWorld()
            val module = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "镇口", """{"text":"旧正文"}""")).requireModule()
            val store = CreativeArtifactFileStore(File(files.root, "artifacts"))
            val legacy = CreativeArtifactRepository(db, store)
            val origin = CreativeArtifactOrigin("chat", "branch")
            val image = legacy.capture("镇口图", CreativeArtifactKind.IMAGE, byteArrayOf(1,2,3), "image/png", origin)
            val attachment = CreativeArtifactAttachment(image.artifact.id, NovexContentAddress.world(world.id), module.id)
            legacy.attach(attachment)
            assertNull(workspace.module(module.id)!!.image)
            val migrated = CreativeArtifactRepository(db, store, workspace)
            assertEquals(0, migrated.migrateCardImages())
            val owned = workspace.module(module.id)!!.image!!
            assertArrayEquals(byteArrayOf(1,2,3), File(owned.managedPath).readBytes())
            assertEquals(0, migrated.migrateCardImages())
            assertEquals(owned.id, workspace.module(module.id)!!.image!!.id)
            migrated.detach(attachment)
            migrated.permanentlyDelete(image.artifact.id)
            assertTrue(File(owned.managedPath).isFile)
            val next = migrated.capture("另一图", CreativeArtifactKind.IMAGE, byteArrayOf(4,5,6), "image/png", origin)
            migrated.attach(attachment.copy(artifactId = next.artifact.id))
            assertArrayEquals(byteArrayOf(4,5,6), File(workspace.module(module.id)!!.image!!.managedPath).readBytes())
            workspace.apply(NovexCommand.DetachImage(ModuleOwner.contentModule(module.id), MediaAssetSlot.MODULE_IMAGE))
            assertTrue(migrated.attachedModuleImageFiles(NovexContentAddress.world(world.id)).isEmpty())
            assertEquals(0, migrated.migrateCardImages())
            assertNull(workspace.module(module.id)!!.image)
            assertTrue(workspace.module(module.id)!!.module.contentJson.contains("旧正文"))
            workspace.apply(NovexCommand.SaveModule(module.id, "镇口", "# 镇口\n更新后的正文"))
            assertTrue(migrated.attachedModuleImageFiles(NovexContentAddress.world(world.id)).isEmpty())
            assertEquals(0, migrated.migrateCardImages())
            assertNull(workspace.module(module.id)!!.image)
            assertTrue(ContentModuleDocumentCodec.decode(workspace.module(module.id)!!.module.contentJson).let {
                it is ContentModuleDocument.Article && it.text == "# 镇口\n更新后的正文"
            })
        } finally { db.close() }
    }
}
