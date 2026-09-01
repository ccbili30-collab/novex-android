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
class AppDatabaseBranchMigrationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL(
                            "CREATE TABLE messages (" +
                                "id TEXT NOT NULL PRIMARY KEY, " +
                                "session_id TEXT NOT NULL, " +
                                "sort_order INTEGER NOT NULL, " +
                                "created_at INTEGER NOT NULL" +
                                ")",
                        )
                    }

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
    fun migration15PreservesLegacyRowsAndBackfillsTheActiveLinearPath() {
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO sessions(id) VALUES ('s1')")
        db.execSQL("INSERT INTO messages VALUES ('u1', 's1', 0, 100)")
        db.execSQL("INSERT INTO messages VALUES ('a1', 's1', 1, 101)")
        db.execSQL("INSERT INTO messages VALUES ('u2', 's1', 2, 102)")
        db.execSQL("INSERT INTO messages VALUES ('a2', 's1', 3, 103)")

        AppDatabase.MIGRATION_14_15.migrate(db)

        db.query(
            "SELECT id, parent_message_id, active_child_id FROM messages " +
                "WHERE session_id = 's1' ORDER BY sort_order",
        ).use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) {
                    add(
                        Triple(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                        ),
                    )
                }
            }
            assertEquals(
                listOf(
                    Triple("u1", null, "a1"),
                    Triple("a1", "u1", "u2"),
                    Triple("u2", "a1", "a2"),
                    Triple("a2", "u2", null),
                ),
                rows,
            )
        }
        db.query(
            "SELECT active_root_message_id, active_leaf_message_id FROM sessions WHERE id = 's1'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("u1", cursor.getString(0))
            assertEquals("a2", cursor.getString(1))
        }
        db.query("SELECT COUNT(*) FROM messages WHERE session_id = 's1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(4, cursor.getInt(0))
        }
    }

    companion object {
        private const val DB_NAME = "branch-migration-test.db"
    }
}
