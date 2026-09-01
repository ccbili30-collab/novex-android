package com.openminis.app.data.character

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorldPageRepositoryInstrumentedTest {
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
    fun worldPagePersistsBaseFieldsAndConcreteCharacterVersions() = runBlocking {
        val world = repository.createWorld("旧名称", now = 10)
        repository.saveWorld(
            world.copy(
                name = "雾港",
                overview = "被永夜笼罩的港城",
                tagsJson = "[\"蒸汽\",\"悬疑\"]",
            ),
            now = 20,
        )
        val character = repository.createCharacter("伊薇", now = 30)
        val variant = repository.createVariant(character.character.id, "调查员分身", now = 31)
        repository.addVersionToWorld(world.id, character.original.id, position = 0, now = 40)
        repository.addVersionToWorld(world.id, variant.id, position = 1, now = 41)

        val reopened = repository.world(world.id)!!
        assertEquals("雾港", reopened.name)
        assertEquals("被永夜笼罩的港城", reopened.overview)
        assertEquals("[\"蒸汽\",\"悬疑\"]", reopened.tagsJson)
        assertEquals(
            listOf(character.original.id, variant.id),
            repository.versionsForWorld(world.id).map { it.id },
        )
        assertEquals(1, repository.listWorlds().size)
        assertEquals(2, repository.listVersions().size)
    }
}
