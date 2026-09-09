package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.repository.ChatRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexHistoryScopeMigrationTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun upgradeKeepsOldSummaryWithoutInventingItsAccessScope() = runBlocking {
        val path = File(files.root, "scope.db").absolutePath
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, path)
            .addMigrations(AppDatabase.MIGRATION_33_34, AppDatabase.MIGRATION_34_35).allowMainThreadQueries().build()
        var db = open()
        val session = ChatRepository(db.chatDao()).createSession("fixture")
        db.chatDao().insertCompactMarker(CompactMarkerEntity("old", session.id, "原始摘要", 10, 2, 1, version = 2))
        // Rebuild the exact v33 table (including existing rows and indexes) before the real reopen.
        val sql = db.openHelper.writableDatabase
        val oldCreate = sql.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='compact_markers'").use { cursor ->
            assertTrue(cursor.moveToFirst()); cursor.getString(0)
        }.replace(", `history_scope_key` TEXT", "").replace(", history_scope_key TEXT", "")
        assertFalse("夹具应确实移除新增列", oldCreate.contains("history_scope_key"))
        val names = mutableListOf<String>()
        sql.query("PRAGMA table_info(compact_markers)").use { cursor ->
            while (cursor.moveToNext()) cursor.getString(cursor.getColumnIndexOrThrow("name")).takeIf { it != "history_scope_key" }?.let(names::add)
        }
        val columns = names.joinToString(",") { "`$it`" }
        val indexes = mutableListOf<String>()
        sql.query("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='compact_markers' AND sql IS NOT NULL").use { cursor ->
            while (cursor.moveToNext()) indexes += cursor.getString(0)
        }
        sql.execSQL("CREATE TEMP TABLE saved_markers AS SELECT $columns FROM compact_markers")
        sql.execSQL("DROP TABLE compact_markers")
        sql.execSQL(oldCreate)
        sql.execSQL("INSERT INTO compact_markers ($columns) SELECT $columns FROM saved_markers")
        indexes.forEach(sql::execSQL)
        sql.execSQL("DROP TABLE saved_markers")
        sql.version = 33
        db.close()
        db = open()
        try {
            val restored = db.openHelper.readableDatabase.query("SELECT summary,history_scope_key FROM compact_markers WHERE id='old'").use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getString(0) to cursor.isNull(1)
            }
            assertEquals("原始摘要", restored.first)
            assertTrue(restored.second)
            db.chatDao().insertCompactMarker(CompactMarkerEntity("new", session.id, "同范围摘要", 20, 3, 2, version = 2, historyScopeKey = "scope-key"))
        } finally { db.close() }
        db = open()
        try {
            db.openHelper.readableDatabase.query("SELECT history_scope_key FROM compact_markers WHERE id='new'").use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("scope-key", cursor.getString(0))
            }
        } finally { db.close() }
    }
}
