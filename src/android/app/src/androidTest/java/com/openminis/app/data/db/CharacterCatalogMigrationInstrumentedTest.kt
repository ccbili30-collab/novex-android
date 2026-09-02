package com.openminis.app.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterCatalogMigrationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migration16CreatesCharacterVersionAndWorldMembershipTablesWithoutLegacyRows() {
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_15_16.migrate(db)

        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('worlds', 'characters', 'character_versions', 'world_character_versions') " +
                "ORDER BY name",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(
            listOf("character_versions", "characters", "world_character_versions", "worlds"),
            tables,
        )
        db.query("SELECT COUNT(*) FROM worlds").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM characters").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration17AddsIdempotentImportStateAndSessionReferences() {
        val db = helper.writableDatabase
        db.execSQL(
            """
            CREATE TABLE sessions (
                id TEXT NOT NULL PRIMARY KEY,
                character_id TEXT,
                world_snapshot_json TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("INSERT INTO sessions VALUES ('s1', 'legacy-card', '{\"id\":\"world-1\"}')")
        AppDatabase.MIGRATION_15_16.migrate(db)
        AppDatabase.MIGRATION_16_17.migrate(db)

        val columns = db.query("PRAGMA table_info(sessions)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertEquals(true, "world_id" in columns)
        assertEquals(true, "character_version_id" in columns)
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'catalog_migration_state'",
        ).use { cursor -> assertEquals(true, cursor.moveToFirst()) }
        val legacyColumn = db.query("PRAGMA table_info(worlds)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
        assertEquals(true, "legacy_snapshot_json" in legacyColumn)
    }

    @Test
    fun migration18CreatesOneSharedModuleAndReferenceSchema() {
        val db = helper.writableDatabase
        db.execSQL(
            "CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, character_id TEXT, world_snapshot_json TEXT)",
        )
        AppDatabase.MIGRATION_15_16.migrate(db)
        AppDatabase.MIGRATION_16_17.migrate(db)
        AppDatabase.MIGRATION_17_18.migrate(db)

        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('content_modules', 'content_module_references') ORDER BY name",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(listOf("content_module_references", "content_modules"), tables)
    }

    @Test
    fun migration19CreatesManagedAssetsAndProtectedReferences() {
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_18_19.migrate(db)
        val tables = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name IN ('media_assets', 'media_asset_references') ORDER BY name",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertEquals(listOf("media_asset_references", "media_assets"), tables)
        val foreignKeys = db.query("PRAGMA foreign_key_list(media_asset_references)").use { cursor ->
            buildList {
                val tableIndex = cursor.getColumnIndexOrThrow("table")
                val onDeleteIndex = cursor.getColumnIndexOrThrow("on_delete")
                while (cursor.moveToNext()) add(cursor.getString(tableIndex) to cursor.getString(onDeleteIndex))
            }
        }
        assertEquals(listOf("media_assets" to "RESTRICT"), foreignKeys)
    }

    @Test
    fun migration20AddsWorldTagsWithoutInventingModules() {
        val db = helper.writableDatabase
        db.execSQL(
            "CREATE TABLE worlds (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, overview TEXT NOT NULL, " +
                "legacy_snapshot_json TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        db.execSQL("INSERT INTO worlds VALUES ('w1', '雾港', '概述', NULL, 1, 2)")
        AppDatabase.MIGRATION_19_20.migrate(db)
        db.query("SELECT tags_json FROM worlds WHERE id = 'w1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("[]", cursor.getString(0))
        }
    }

    @Test
    fun migration21BackfillsStableCharacterVersionOrder() {
        val db = helper.writableDatabase
        db.execSQL(
            "CREATE TABLE character_versions (" +
                "id TEXT NOT NULL PRIMARY KEY, character_id TEXT NOT NULL, kind TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL)",
        )
        db.execSQL("INSERT INTO character_versions VALUES ('origin', 'c1', 'ORIGINAL', 50)")
        db.execSQL("INSERT INTO character_versions VALUES ('later', 'c1', 'VARIANT', 30)")
        db.execSQL("INSERT INTO character_versions VALUES ('earlier', 'c1', 'VARIANT', 20)")

        AppDatabase.MIGRATION_20_21.migrate(db)

        db.query("SELECT id, position FROM character_versions ORDER BY position").use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1))
            }
            assertEquals(listOf("origin" to 0, "earlier" to 1, "later" to 2), rows)
        }
    }

    companion object {
        private const val DB_NAME = "character-catalog-migration-test.db"
    }
}
