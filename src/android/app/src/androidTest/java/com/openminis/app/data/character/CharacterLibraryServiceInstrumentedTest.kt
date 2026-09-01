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

        val copy = service.duplicateCharacter(source.character.id, now = 20)
        val copiedOwner = ModuleOwner.characterVersion(copy.original.id)
        val copiedModules = moduleRepository.list(copiedOwner)
        val copiedQuotes = copiedModules.first { it.type == ContentModuleType.QUOTES }
        val copiedSkills = copiedModules.first { it.type == ContentModuleType.TALENT_SKILL }
        assertEquals("晚上好", org.json.JSONObject(copiedQuotes.contentJson).getString("text"))
        assertEquals(copiedSkills.id, moduleRepository.references(copiedQuotes.id).single().targetId)
        assertEquals(2, mediaRepository.referenceCount(avatar.id))

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

        val exported = service.exportDocument(copy.character.id)
        assertEquals(1, exported.versions.size)
        assertEquals(
            listOf(ContentModuleType.QUOTES, ContentModuleType.TALENT_SKILL),
            exported.versions.single().modules.map { it.type },
        )

        service.deleteCharacter(copy.character.id)
        assertNull(mediaRepository.asset(avatar.id))
        assertFalse(avatarFile.exists())
    }
}
