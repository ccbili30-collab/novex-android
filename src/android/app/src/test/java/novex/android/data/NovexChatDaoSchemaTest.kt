package novex.android.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import novex.android.data.chat.ChatDao
import novex.android.data.chat.CompactMarkerRow
import novex.android.data.chat.MessageRow
import novex.android.data.chat.SessionRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/*
 * Pins the chat half of the main database: the SQL-level table shapes this
 * app has shipped for years (evidence that the schema rewrite kept every
 * table, column and column order) and the behavioural contract of message
 * insertion, branch switching and compact markers.
 */

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexChatDaoSchemaTest {
    private lateinit var database: NovexMainDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NovexMainDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.chatDao()
    }

    @After
    fun tearDown() = database.close()

    private fun session(id: String) = SessionRow(id, modelId = "m", createdAt = 1, updatedAt = 1)
    private fun turn(id: String, sessionId: String, role: String, body: String) =
        MessageRow(id, sessionId, role, body, 1, sortOrder = -1)

    private fun columnNames(table: String): List<String> =
        database.openHelper.readableDatabase
            .query("PRAGMA table_info($table)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                }
            }

    @Test
    fun databaseVersionAndFileRemainPinned() {
        assertEquals(39, database.openHelper.readableDatabase.version)
        assertEquals("minis.db", NovexMainDatabase.DB_NAME)
    }

    @Test
    fun sessionsTableColumnsAreFrozen() {
        assertEquals(
            listOf(
                "id", "title", "model_id", "created_at", "updated_at", "category", "last_message",
                "model_binding", "source", "memory_enabled", "pinned_at", "edit_count",
                "thinking_override", "folder_id", "character_id", "character_snapshot_json",
                "world_snapshot_json", "persona_id", "persona_snapshot_json", "world_id",
                "character_version_id", "chat_background_path", "conversation_prompt",
                "image_style_prompt", "per_turn_prompt", "text_style_prompt", "runtime_dice_enabled",
                "runtime_ledger_enabled", "role_presentation_enabled", "assistant_display_name",
                "assistant_avatar_path", "player_display_name", "player_avatar_path",
                "novex_configuration_json", "active_root_message_id", "active_leaf_message_id",
                "composer_draft", "side_of_session",
            ),
            columnNames("sessions"),
        )
    }

    @Test
    fun messagesTableColumnsAndIndexesAreFrozen() {
        assertEquals(
            listOf(
                "id", "session_id", "role", "parts_json", "created_at", "token_usage", "sort_order",
                "reasoning_content", "stream_interrupt_count", "updated_at", "error_info",
                "parent_message_id", "active_child_id",
            ),
            columnNames("messages"),
        )
        val indexSql = database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='messages' AND name LIKE 'index%'")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertEquals(
            setOf("index_messages_session_id_sort_order", "index_messages_session_id_parent_message_id"),
            indexSql.toSet(),
        )
    }

    @Test
    fun appendingGrowsTheActiveChainAndAssignsSortOrders() = runBlocking {
        dao.upsertSession(session("s"))
        val first = dao.appendOnActivePath(turn("t1", "s", "user", "one"), "one", 10)
        val second = dao.appendOnActivePath(turn("t2", "s", "assistant", "two"), "two", 20)
        val third = dao.appendOnActivePath(turn("t3", "s", "user", "three"), "three", 30)

        assertEquals(listOf(0, 1, 2), listOf(first.sortOrder, second.sortOrder, third.sortOrder))
        assertEquals(null, first.parentMessageId)
        assertEquals("t1", second.parentMessageId)
        // The stored parent pointer advances even though the returned in-memory copy of the
        // persisted row still shows its own (null) child selection at insert time.
        assertEquals("t3", dao.messageById("t2")!!.activeChildId)
        val anchors = dao.branchAnchorsOf("s")!!
        assertEquals("t1", anchors.activeRootMessageId)
        assertEquals("t3", anchors.activeLeafMessageId)
        assertEquals(listOf("t1", "t2", "t3"), dao.historyFor("s").map { it.id })
        assertEquals("three", dao.activeLeafBody("s"))
    }

    @Test
    fun branchMutationSwitchesTheActivePathAndDeletesDoomedRows() = runBlocking {
        dao.upsertSession(session("s"))
        val root = dao.appendOnActivePath(turn("root", "s", "user", "seed"), "seed", 1)
        val kept = dao.appendOnActivePath(turn("kept", "s", "assistant", "kept body"), "kept body", 2)
        val dropped = dao.appendOnActivePath(turn("dropped", "s", "user", "old branch"), "old branch", 3)

        dao.applyBranchMutation(
            sessionId = "s",
            rootId = root.id,
            leafId = kept.id,
            childUpdates = mapOf(root.id to kept.id),
            deletedMessageIds = setOf(dropped.id),
            preview = "kept body",
            updatedAt = 99,
        )

        assertEquals(kept.id, dao.branchAnchorsOf("s")!!.activeLeafMessageId)
        assertEquals(listOf("root", "kept"), dao.historyFor("s").map { it.id })
        assertEquals(null, dao.messageById(dropped.id))
        assertEquals("kept body", dao.sessionById("s")!!.lastMessage)
    }

    @Test
    fun compactMarkersRoundTripNewestFirstWithFrozenColumns() = runBlocking {
        dao.upsertSession(session("s"))
        dao.addMarker(
            CompactMarkerRow("old", "s", "早期摘要", firstKeptSortOrder = 0, compactedCount = 2, createdAt = 5),
        )
        dao.addMarker(
            CompactMarkerRow(
                "new", "s", "最新摘要", firstKeptSortOrder = 4, compactedCount = 3, createdAt = 9,
                firstKeptMessageId = "m4", lastCompactedMessageId = "m6", version = 2, historyScopeKey = "s/main",
            ),
        )

        assertEquals("最新摘要", dao.newestMarker("s")!!.summary)
        assertEquals(2, dao.newestMarker("s")!!.version)
        assertEquals(listOf("old", "new"), dao.markersFor("s").map { it.id })
        assertEquals(1, dao.dropMarker("old"))
        assertEquals(
            listOf(
                "id", "session_id", "summary", "first_kept_sort_order", "compacted_count", "created_at",
                "ui_boundary_sort_order", "boundary_message_id", "first_kept_message_id",
                "last_compacted_message_id", "version", "history_scope_key",
            ),
            columnNames("compact_markers"),
        )
    }
}
