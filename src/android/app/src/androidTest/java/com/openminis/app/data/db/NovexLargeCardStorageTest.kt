package com.openminis.app.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.character.*
import com.openminis.app.data.interactivefiction.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Disk-backed, isolated Android storage: the JVM SQLite implementation has no CursorWindow. */
@RunWith(AndroidJUnit4::class)
class NovexLargeCardStorageTest {
    private fun withDatabase(block: suspend (AppDatabase, () -> AppDatabase) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "large-card-${UUID.randomUUID()}.db"
        val opened = mutableListOf<AppDatabase>()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build().also { opened += it }
        try { block(open(), ::open) } finally {
            opened.forEach { it.close() }
            context.deleteDatabase(name)
        }
    }

    // More than 4 MiB in UTF-8, including characters that cross byte chunk boundaries.
    private fun body() = "\u8bbe\u5b9a\ud83d\udd4a\n".repeat(440_000) + "\u0000END"

    @Test fun emptyNullAndExactChunkBoundariesRemainDistinct() = withDatabase { db, _ ->
        val dao = db.characterCatalogDao()
        listOf("", "x".repeat(128 * 1024), "x".repeat(256 * 1024)).forEachIndexed { i, text ->
            val row = WorldEntity("w$i", "World", text, legacySnapshotJson = if (i == 0) null else "",
                createdAt = 1, updatedAt = 1)
            dao.insertWorld(row)
            assertEquals(row, dao.world(row.id))
        }
    }

    @Test fun largeWorldAndCharacterSurviveReopenAndAllCatalogQueries() = withDatabase { db, reopen ->
        val text = body()
        val world = WorldEntity("w", "World", text, legacySnapshotJson = text, createdAt = 1, updatedAt = 1)
        val character = CharacterEntity("c", "Character", "v", 1, 1)
        val version = CharacterVersionEntity("v", "c", CharacterVersionKind.ORIGINAL, "Original", text, 0, 1, 1)
        db.characterCatalogDao().apply {
            insertWorld(world)
            insertCharacterWithOriginal(character, version)
            upsertWorldMembership(WorldCharacterVersionEntity("w", "v", 0, 1))
        }
        db.close()
        val dao = reopen().characterCatalogDao()
        assertEquals(world, dao.world("w"))
        assertEquals(listOf(world), dao.listWorlds())
        assertEquals(listOf(world), dao.worldsForVersion("v"))
        assertEquals(version, dao.version("v"))
        assertEquals(listOf(version), dao.listVersions())
        assertEquals(listOf(version), dao.versionsForCharacter("c"))
        assertEquals(listOf(version), dao.versionsForWorld("w"))
        assertNull(dao.world("missing"))
    }

    @Test fun modulesForAllThreeCardKindsKeepLargeEditsOrderAndRollback() = withDatabase { db, reopen ->
        val text = body()
        val rows = listOf(ModuleOwner.world("w"), ModuleOwner.characterVersion("c"), ModuleOwner.interactiveFiction("g"))
            .mapIndexed { index, owner -> ContentModuleEntity("m$index", owner.type, owner.id,
                ContentModuleType.CUSTOM, "Text", text, 0, createdAt = 1, updatedAt = 1) }
        rows.forEach { db.contentModuleDao().insert(it) }
        db.close()
        val reopened = reopen()
        val dao = reopened.contentModuleDao()
        rows.forEach { row ->
            assertEquals(row, dao.module(row.id))
            assertEquals(listOf(row), dao.list(row.ownerType, row.ownerId))
        }
        assertEquals(rows.toSet(), dao.all().toSet())
        val added = rows.first().copy(id = "extra", position = 1, contentJson = "small")
        dao.insert(added)
        dao.move(added.id, 0, 2)
        assertEquals(listOf("extra", "m0"), dao.list(ModuleOwnerType.WORLD, "w").map { it.id })
        try {
            reopened.withTransaction {
                dao.update(rows.first().copy(contentJson = "must roll back"))
                error("intentional rollback")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(text, dao.module("m0")!!.contentJson)
        dao.update(rows.first().copy(contentJson = text + "edited"))
        assertEquals(text + "edited", dao.module("m0")!!.contentJson)
        dao.deleteAndCompact("extra")
        assertEquals(0, dao.module("m0")!!.position)
    }

    @Test fun gameAndAllCardRevisionBodiesSurviveReopen() = withDatabase { db, reopen ->
        val text = body()
        db.characterCatalogDao().insertWorld(WorldEntity("w", "World", createdAt = 1, updatedAt = 1))
        db.characterCatalogDao().insertCharacterWithOriginal(CharacterEntity("c", "Character", "v", 1, 1),
            CharacterVersionEntity("v", "c", CharacterVersionKind.ORIGINAL, "Original", createdAt = 1, updatedAt = 1))
        val game = InteractiveFictionProjectEntity("g", "Game", text, InteractiveFictionLaunchMode.FREE_SANDBOX,
            text, 1, 1, sourceDocumentJson = text)
        db.interactiveFictionDao().insert(game)
        val worldRevision = NovexWorldRevisionEntity("w", 1, 1, text)
        val gameRevision = NovexGameRevisionEntity("g", 1, 1, text)
        val roleRevision = NovexCharacterRevisionEntity("v", 1, 1, text)
        db.novexCardRevisionDao().insertWorld(worldRevision)
        db.novexCardRevisionDao().insertGame(gameRevision)
        db.novexCharacterRevisionDao().insert(roleRevision)
        db.close()
        val reopened = reopen()
        assertEquals(game, reopened.interactiveFictionDao().project("g"))
        assertEquals(listOf(game), reopened.interactiveFictionDao().list())
        assertEquals(worldRevision, reopened.novexCardRevisionDao().latestWorld("w"))
        assertEquals(listOf(worldRevision), reopened.novexCardRevisionDao().worlds("w"))
        assertEquals(gameRevision, reopened.novexCardRevisionDao().latestGame("g"))
        assertEquals(listOf(gameRevision), reopened.novexCardRevisionDao().games("g"))
        assertEquals(roleRevision, reopened.novexCharacterRevisionDao().latest("v"))
        assertEquals(listOf(roleRevision), reopened.novexCharacterRevisionDao().list("v"))
    }
}
