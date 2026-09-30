package novex.core

import novex.android.data.MIGRATION_34_35
import novex.android.data.MIGRATION_35_36
import novex.android.data.MIGRATION_36_37
import novex.android.data.MIGRATION_37_38
import novex.android.data.MIGRATION_38_39

import android.app.Application
import androidx.room.Room
import novex.android.data.NovexMainDatabase
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
class NovexComposerDraftPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun upgradeAndReopenKeepUnsentTextSeparateFromMessagesAndOtherConversations() = runBlocking {
        val path = File(files.root, "draft.db").absolutePath
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), NovexMainDatabase::class.java, path)
            .addMigrations(MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39).allowMainThreadQueries().build()
        var db = open()
        var repository = ChatRepository(db.chatDao())
        val first = repository.createSession("unknown", title = "尚未连接模型")
        val second = repository.createSession("unknown", title = "另一份草稿")
        // Recreate the pre-upgrade sessions table without the draft column.
        val sql = db.openHelper.writableDatabase
        val oldCreate = sql.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='sessions'").use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }.replace(", `composer_draft` TEXT", "").replace(", composer_draft TEXT", "")
        assertFalse(oldCreate.contains("composer_draft"))
        val columns = mutableListOf<String>()
        sql.query("PRAGMA table_info(sessions)").use { c -> while (c.moveToNext()) {
            c.getString(c.getColumnIndexOrThrow("name")).takeIf { it != "composer_draft" }?.let(columns::add)
        } }
        val names = columns.joinToString(",") { "`$it`" }
        sql.execSQL("PRAGMA foreign_keys=OFF")
        sql.execSQL("CREATE TEMP TABLE saved_sessions AS SELECT $names FROM sessions")
        sql.execSQL("DROP TABLE sessions")
        sql.execSQL(oldCreate)
        sql.execSQL("CREATE INDEX index_sessions_folder_id ON sessions(folder_id)")
        sql.execSQL("INSERT INTO sessions ($names) SELECT $names FROM saved_sessions")
        sql.execSQL("DROP TABLE saved_sessions")
        sql.version = 34
        db.close()
        db = open(); repository = ChatRepository(db.chatDao())
        assertNull(repository.sessionById(first.id)!!.composerDraft)
        val original = "  给邮差写一封信。\n先保留草稿，不要发送。\n"
        repository.saveComposerDraft(first.id, original)
        repository.saveComposerDraft(second.id, "另一个对话的内容")
        db.close()
        db = open(); repository = ChatRepository(db.chatDao())
        try {
            assertEquals(original, repository.sessionById(first.id)!!.composerDraft)
            assertEquals(0, repository.messageCount(first.id))
            repository.saveComposerDraft(first.id, "")
            assertNull(repository.sessionById(first.id)!!.composerDraft)
            repository.dropSession(first.id)
            assertNull(repository.sessionById(first.id))
            assertEquals("另一个对话的内容", repository.sessionById(second.id)!!.composerDraft)
        } finally { db.close() }
    }
}
