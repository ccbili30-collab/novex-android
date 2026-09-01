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

    companion object {
        private const val DB_NAME = "character-catalog-migration-test.db"
    }
}
