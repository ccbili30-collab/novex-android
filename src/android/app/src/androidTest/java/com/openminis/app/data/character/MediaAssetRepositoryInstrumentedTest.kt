package com.openminis.app.data.character

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAssetRepositoryInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var catalog: CharacterCatalogRepository
    private lateinit var repository: MediaAssetRepository
    private lateinit var mediaDirectory: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = CharacterCatalogRepository(database.characterCatalogDao())
        mediaDirectory = File(context.cacheDir, "media-asset-test-${System.nanoTime()}").apply { mkdirs() }
        repository = MediaAssetRepository(database.mediaAssetDao()) { path -> File(path).delete() }
    }

    @After
    fun tearDown() {
        database.close()
        mediaDirectory.deleteRecursively()
    }

    @Test
    fun sharedAssetIsDeletedOnlyAfterItsLastWorldOrCharacterReference() = runBlocking {
        val worldA = catalog.createWorld("世界 A", now = 10)
        val worldB = catalog.createWorld("世界 B", now = 11)
        val character = catalog.createCharacter("伊薇", now = 12)
        val file = File(mediaDirectory, "shared.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val asset = repository.register(
            managedPath = file.absolutePath,
            mimeType = "image/png",
            contentHash = "hash-shared",
            now = 20,
        )

        repository.attach(ModuleOwner.world(worldA.id), MediaAssetSlot.WORLD_COVER, asset.id)
        repository.attach(ModuleOwner.world(worldB.id), MediaAssetSlot.WORLD_BACKGROUND, asset.id)
        repository.attach(
            ModuleOwner.characterVersion(character.original.id),
            MediaAssetSlot.CHARACTER_AVATAR,
            asset.id,
        )
        assertEquals(3, repository.referenceCount(asset.id))

        repository.detach(ModuleOwner.world(worldA.id), MediaAssetSlot.WORLD_COVER)
        assertNotNull(repository.asset(asset.id))
        assertTrue(file.exists())

        repository.removeAll(ModuleOwner.world(worldB.id))
        assertNotNull(repository.asset(asset.id))
        assertTrue(file.exists())

        repository.removeAll(ModuleOwner.characterVersion(character.original.id))
        assertNull(repository.asset(asset.id))
        assertFalse(file.exists())
    }

    @Test
    fun replacingSlotCollectsOnlyTheOrphanAndRejectsOwnerSlotMismatch() = runBlocking {
        val world = catalog.createWorld("世界", now = 10)
        val firstFile = File(mediaDirectory, "first.png").apply { writeBytes(byteArrayOf(1)) }
        val secondFile = File(mediaDirectory, "second.png").apply { writeBytes(byteArrayOf(2)) }
        val first = repository.register(firstFile.absolutePath, "image/png", "hash-1", now = 20)
        val second = repository.register(secondFile.absolutePath, "image/png", "hash-2", now = 21)
        val owner = ModuleOwner.world(world.id)

        repository.attach(owner, MediaAssetSlot.WORLD_LOGO, first.id)
        repository.attach(owner, MediaAssetSlot.WORLD_LOGO, second.id)

        assertNull(repository.asset(first.id))
        assertFalse(firstFile.exists())
        assertEquals(second.id, repository.assetFor(owner, MediaAssetSlot.WORLD_LOGO)?.id)
        assertTrue(secondFile.exists())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.attach(owner, MediaAssetSlot.CHARACTER_AVATAR, second.id)
            }
        }
        Unit
    }

    @Test
    fun contentModuleCanOwnAnOptionalProtectedImage() = runBlocking {
        val world = catalog.createWorld("群星纪", now = 10)
        val module = ContentModuleRepository(database.contentModuleDao()).add(
            owner = ModuleOwner.world(world.id),
            type = ContentModuleType.MAP,
            name = "苍穹大陆地图",
            now = 11,
        )
        val file = File(mediaDirectory, "map.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val asset = repository.register(file.absolutePath, "image/png", "hash-map", now = 20)
        val moduleOwner = ModuleOwner.contentModule(module.id)

        repository.attach(moduleOwner, MediaAssetSlot.MODULE_IMAGE, asset.id)

        assertEquals(asset.id, repository.assetFor(moduleOwner, MediaAssetSlot.MODULE_IMAGE)?.id)
        assertEquals(1, repository.referenceCount(asset.id))
        repository.removeAll(moduleOwner)
        assertNull(repository.asset(asset.id))
        assertFalse(file.exists())
    }
}
