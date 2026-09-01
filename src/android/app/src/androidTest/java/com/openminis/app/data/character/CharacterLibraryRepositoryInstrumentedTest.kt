package com.openminis.app.data.character

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterLibraryRepositoryInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: CharacterCatalogRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CharacterCatalogRepository(database.characterCatalogDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun characterLibraryEditsCopiesAndDeletesRootsAndVariants() = runBlocking {
        val source = repository.createCharacter(
            name = "伊薇",
            originalProfileJson = "{\"name\":\"伊薇\",\"occupation\":\"调查员\"}",
            now = 10,
            characterId = "eve",
            originalVersionId = "eve-original",
        )
        val variant = repository.createVariant(
            characterId = source.character.id,
            label = "赛博分身",
            profileJson = "{\"name\":\"伊薇\",\"occupation\":\"黑客\"}",
            now = 11,
            id = "eve-cyber",
        )

        repository.saveCharacter(source.character.copy(name = "伊薇·根角色"), now = 20)
        repository.saveVersion(variant.copy(label = "雾港分身"), now = 21)
        val duplicate = repository.duplicateCharacter(
            characterId = source.character.id,
            now = 30,
            newCharacterId = "eve-copy",
            newOriginalVersionId = "eve-copy-original",
        )

        assertEquals(
            setOf("伊薇·根角色", "伊薇·根角色 副本"),
            repository.listCharacters().map { it.name }.toSet(),
        )
        assertEquals(2, duplicate.allVersions.size)
        assertEquals(source.original.profileJson, duplicate.original.profileJson)
        assertEquals("雾港分身", duplicate.variants.single().label)

        repository.deleteVersion(duplicate.variants.single().id)
        assertEquals(1, repository.character(duplicate.character.id)!!.allVersions.size)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.deleteVersion(duplicate.original.id) }
        }

        repository.deleteCharacter(duplicate.character.id)
        assertNull(repository.character(duplicate.character.id))
        assertEquals(listOf("伊薇·根角色"), repository.listCharacters().map { it.name })
    }
}
