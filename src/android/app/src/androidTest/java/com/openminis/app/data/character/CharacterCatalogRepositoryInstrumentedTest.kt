package com.openminis.app.data.character

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterCatalogRepositoryInstrumentedTest {
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
    fun tearDown() {
        database.close()
    }

    @Test
    fun characterOwnsOneOriginalAndIndependentVariants() = runBlocking {
        val character = repository.createCharacter(
            name = "伊薇",
            originalLabel = "本体",
            originalProfileJson = "{\"name\":\"伊薇\"}",
            now = 100,
        )
        val cyber = repository.createVariant(
            characterId = character.character.id,
            label = "赛博分身",
            profileJson = "{\"name\":\"赛博伊薇\"}",
            now = 200,
        )
        val youth = repository.createVariant(
            characterId = character.character.id,
            label = "少女时期",
            profileJson = "{\"name\":\"少女伊薇\"}",
            now = 300,
        )

        val reopened = repository.character(character.character.id)
        assertNotNull(reopened)
        assertEquals(character.original.id, reopened!!.original.id)
        assertEquals(CharacterVersionKind.ORIGINAL, reopened.original.kind)
        assertEquals(
            listOf(youth.id, cyber.id),
            reopened.variants.map { it.id },
        )
        assertEquals(
            setOf(character.character.id),
            reopened.allVersions.map { it.characterId }.toSet(),
        )
    }

    @Test
    fun worldMembershipIsManyToManyAndDoesNotOwnVersions() = runBlocking {
        val worldA = repository.createWorld("蒸汽城", now = 100)
        val worldB = repository.createWorld("赛博城", now = 200)
        val character = repository.createCharacter("伊薇", now = 300)
        val variant = repository.createVariant(character.character.id, "赛博分身", now = 400)

        repository.addVersionToWorld(worldA.id, character.original.id, position = 0, now = 500)
        repository.addVersionToWorld(worldA.id, variant.id, position = 1, now = 501)
        repository.addVersionToWorld(worldB.id, variant.id, position = 0, now = 502)

        assertEquals(
            listOf(character.original.id, variant.id),
            repository.versionsForWorld(worldA.id).map { it.id },
        )
        assertEquals(
            listOf(worldA.id, worldB.id),
            repository.worldsForVersion(variant.id).map { it.id },
        )

        repository.removeVersionFromWorld(worldA.id, variant.id)
        assertEquals(listOf(worldB.id), repository.worldsForVersion(variant.id).map { it.id })
        assertNotNull(repository.version(variant.id))

        repository.deleteWorld(worldB.id)
        assertNull(repository.world(worldB.id))
        assertNotNull(repository.version(variant.id))
        assertEquals(emptyList<WorldEntity>(), repository.worldsForVersion(variant.id))
    }
}
