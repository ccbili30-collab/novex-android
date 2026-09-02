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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterLibraryServiceInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var catalog: CharacterCatalogRepository
    private lateinit var moduleRepository: ContentModuleRepository
    private lateinit var mediaRepository: MediaAssetRepository
    private lateinit var service: CharacterLibraryService
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        catalog = CharacterCatalogRepository(database.characterCatalogDao())
        moduleRepository = ContentModuleRepository(database.contentModuleDao())
        directory = File(context.cacheDir, "character-library-service-${System.nanoTime()}").apply { mkdirs() }
        mediaRepository = MediaAssetRepository(database.mediaAssetDao()) { File(it).delete() }
        service = CharacterLibraryService(catalog, moduleRepository, mediaRepository)
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun copyIsIndependentWhileSharedMediaSurvivesUntilLastRootIsDeleted() = runBlocking {
        val source = catalog.createCharacter("伊薇", now = 10)
        val quotes = moduleRepository.add(
            ModuleOwner.characterVersion(source.original.id),
            ContentModuleType.QUOTES,
            "多形态语录",
            "{\"text\":\"晚上好\"}",
            now = 11,
        )
        val skills = moduleRepository.add(
            ModuleOwner.characterVersion(source.original.id),
            ContentModuleType.TALENT_SKILL,
            "天赋技能",
            "{\"text\":\"观察\"}",
            now = 12,
        )
        moduleRepository.addReference(
            quotes.id,
            ModuleReferenceTarget.module(skills.id),
            position = 0,
        )
        val avatarFile = File(directory, "avatar.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val avatar = mediaRepository.register(avatarFile.absolutePath, "image/png", "avatar-hash", now = 13)
        mediaRepository.attach(
            ModuleOwner.characterVersion(source.original.id),
            MediaAssetSlot.CHARACTER_AVATAR,
            avatar.id,
        )
        val quoteImageFile = File(directory, "quote.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val quoteImage = mediaRepository.register(
            quoteImageFile.absolutePath,
            "image/png",
            "quote-image-hash",
            now = 14,
        )
        mediaRepository.attach(
            ModuleOwner.contentModule(quotes.id),
            MediaAssetSlot.MODULE_IMAGE,
            quoteImage.id,
        )

        val copy = service.duplicateCharacter(source.character.id, now = 20)
        val copiedOwner = ModuleOwner.characterVersion(copy.original.id)
        val copiedModules = moduleRepository.list(copiedOwner)
        val copiedQuotes = copiedModules.first { it.type == ContentModuleType.QUOTES }
        val copiedSkills = copiedModules.first { it.type == ContentModuleType.TALENT_SKILL }
        assertEquals("晚上好", org.json.JSONObject(copiedQuotes.contentJson).getString("text"))
        assertEquals(copiedSkills.id, moduleRepository.references(copiedQuotes.id).single().targetId)
        assertEquals(2, mediaRepository.referenceCount(avatar.id))
        assertEquals(
            quoteImage.id,
            mediaRepository.assetFor(ModuleOwner.contentModule(copiedQuotes.id), MediaAssetSlot.MODULE_IMAGE)?.id,
        )
        assertEquals(2, mediaRepository.referenceCount(quoteImage.id))

        moduleRepository.updateContent(
            copiedQuotes.id,
            "{\"text\":\"复制后修改\"}",
        )
        assertTrue(
            moduleRepository.list(ModuleOwner.characterVersion(source.original.id))
                .first { it.type == ContentModuleType.QUOTES }.contentJson
                .contains("晚上好"),
        )

        service.deleteCharacter(source.character.id)
        assertNull(catalog.character(source.character.id))
        assertNotNull(mediaRepository.asset(avatar.id))
        assertTrue(avatarFile.exists())
        assertNotNull(mediaRepository.asset(quoteImage.id))
        assertTrue(quoteImageFile.exists())

        val exported = service.exportDocument(copy.character.id)
        assertEquals(1, exported.versions.size)
        assertEquals(
            listOf(ContentModuleType.QUOTES, ContentModuleType.TALENT_SKILL),
            exported.versions.single().modules.map { it.type },
        )

        service.deleteCharacter(copy.character.id)
        assertNull(mediaRepository.asset(avatar.id))
        assertFalse(avatarFile.exists())
        assertNull(mediaRepository.asset(quoteImage.id))
        assertFalse(quoteImageFile.exists())
    }

    @Test
    fun saveAsWorldVariantReplacesOnlyCurrentWorldAndCopiesVersionContent() = runBlocking {
        val worldA = catalog.createWorld("雾港", now = 1)
        val worldB = catalog.createWorld("星城", now = 2)
        val character = catalog.createCharacter("伊薇", originalProfileJson = "{\"name\":\"伊薇\"}", now = 3)
        catalog.addVersionToWorld(worldA.id, character.original.id, 0, now = 4)
        catalog.addVersionToWorld(worldB.id, character.original.id, 0, now = 5)
        moduleRepository.add(
            ModuleOwner.characterVersion(character.original.id),
            ContentModuleType.EQUIPMENT,
            "随身装备",
            "{\"text\":\"银色怀表\"}",
            now = 6,
        )
        val backgroundFile = File(directory, "background.png").apply { writeBytes(byteArrayOf(9)) }
        val background = mediaRepository.register(
            backgroundFile.absolutePath,
            "image/png",
            "background-hash",
            now = 7,
        )
        mediaRepository.attach(
            ModuleOwner.characterVersion(character.original.id),
            MediaAssetSlot.CHARACTER_PAGE_BACKGROUND,
            background.id,
        )

        val variant = service.saveAsWorldVariant(character.original.id, worldA.id, now = 20)

        assertEquals(CharacterVersionKind.VARIANT, variant.kind)
        assertEquals(listOf(variant.id), catalog.versionsForWorld(worldA.id).map { it.id })
        assertEquals(listOf(character.original.id), catalog.versionsForWorld(worldB.id).map { it.id })
        assertEquals(listOf(worldA.id), catalog.worldsForVersion(variant.id).map { it.id })
        assertEquals(
            "银色怀表",
            org.json.JSONObject(
                moduleRepository.list(ModuleOwner.characterVersion(variant.id)).single().contentJson,
            ).getString("text"),
        )
        assertEquals(2, mediaRepository.referenceCount(background.id))
    }
}
