package com.openminis.app.data.character

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyCharacterCatalogMigratorInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun importsEachLegacyCardAsCharacterAndOriginalWithoutChangingSessionSnapshots() = runBlocking {
        val world = StoryWorld(
            id = "world-1",
            name = "蒸汽城",
            description = "齿轮与雾",
            backgroundPath = "/legacy/world-bg.png",
            createdAt = 10,
            updatedAt = 20,
        )
        val card = CharacterCard(
            id = "card-1",
            name = "伊薇",
            worldId = world.id,
            summary = "钟表师",
            avatarPath = "/legacy/avatar.png",
            defaultBackgroundPath = "/legacy/chat-bg.png",
            createdAt = 30,
            updatedAt = 40,
        )
        val persona = PlayerPersona(
            id = "persona-1",
            name = "旅人",
            worldId = world.id,
            description = "来自远方",
            createdAt = 50,
            updatedAt = 60,
        )
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("story_worlds", JSONArray().put(world.toJson()).toString())
            .putString("characters", JSONArray().put(card.toJson()).toString())
            .putString("personas", JSONArray().put(persona.toJson()).toString())
            .commit()

        val session = ChatSessionEntity(
            id = "session-1",
            modelId = "test-model",
            createdAt = 100,
            updatedAt = 100,
            characterId = card.id,
            characterSnapshotJson = card.toJson().toString(),
            worldSnapshotJson = world.toJson().toString(),
            personaId = persona.id,
            personaSnapshotJson = persona.toJson().toString(),
            chatBackgroundPath = "/legacy/session-bg.png",
            conversationPrompt = "保持角色",
            imageStylePrompt = "水彩",
        )
        database.chatDao().insertSession(session)

        val first = LegacyCharacterCatalogMigrator.migrate(context, database, now = 1_000)
        val catalog = CharacterCatalogRepository(database.characterCatalogDao())
        val imported = catalog.character(card.id)
        assertNotNull(imported)
        assertEquals(card.id, imported!!.character.id)
        assertEquals(card.id, imported.original.id)
        assertEquals(card.toJson().toString(), imported.original.profileJson)
        assertEquals(listOf(card.id), catalog.versionsForWorld(world.id).map { it.id })
        assertEquals(world.toJson().toString(), catalog.world(world.id)?.legacySnapshotJson)
        assertEquals(1, first.worldCount)
        assertEquals(1, first.characterCount)
        assertEquals(1, first.membershipCount)

        val migratedSession = database.chatDao().getSession(session.id)!!
        assertEquals(world.id, migratedSession.worldId)
        assertEquals(card.id, migratedSession.characterVersionId)
        assertEquals(session.characterSnapshotJson, migratedSession.characterSnapshotJson)
        assertEquals(session.worldSnapshotJson, migratedSession.worldSnapshotJson)
        assertEquals(session.personaId, migratedSession.personaId)
        assertEquals(session.personaSnapshotJson, migratedSession.personaSnapshotJson)
        assertEquals(session.chatBackgroundPath, migratedSession.chatBackgroundPath)
        assertEquals(session.conversationPrompt, migratedSession.conversationPrompt)
        assertEquals(session.imageStylePrompt, migratedSession.imageStylePrompt)
        assertTrue(prefs.contains("characters"))

        val second = LegacyCharacterCatalogMigrator.migrate(context, database, now = 2_000)
        assertEquals(true, second.alreadyCompleted)
        assertEquals(1, catalog.versionsForWorld(world.id).size)
    }

    companion object {
        private const val PREFS = "novex_character_cards"
    }
}
