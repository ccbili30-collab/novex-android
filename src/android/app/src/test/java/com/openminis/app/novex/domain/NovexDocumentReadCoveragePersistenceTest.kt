package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.adapter.NovexContextReadJournal
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexDocumentReadCoveragePersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `workspace file coverage follows real content revisions and rejects an old cursor`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = ChatRepository(database.chatDao())
            val session = repository.createSession("model")
            val scope = NovexConversationWorkspaceScope(session.id, listOf("user"), "reply")
            val store = FileNovexConversationWorkspaceStore(File(files.root, "workspace"))
            val provenance = NovexWorkspaceProvenance(session.id, "reply")
            val entry = store.writeText(scope, NovexWorkspaceArea.NOTES, "资料.md", "甲乙丙丁戊己", "text/markdown", provenance)
            val tools = NovexConversationWorkspaceTools(scope, store)
            val journal = NovexContextReadJournal(repository)
            suspend fun record(result: NovexToolResult) = journal.record(session.id, "user", "reply", setOf("user", "reply"),
                AnswerIdentity.Nova, 4096, "source_tool", JSONObject(result.toJson()))
                .result.getJSONArray("read_coverage").getJSONObject(0)
            val first = tools.workspaceRead(NovexWorkspaceReadRequest(entry.workspaceRef, maxChars = 2))
            assertEquals(2, record(first).getInt("covered_characters"))
            val cursor = first.data.getValue("next_cursor") as String
            assertTrue(record(tools.workspaceRead(NovexWorkspaceReadRequest(entry.workspaceRef, cursor))).getBoolean("complete"))
            store.writeText(scope, NovexWorkspaceArea.NOTES, "资料.md", "后来编辑的正文", "text/markdown", provenance)
            assertFalse(tools.workspaceRead(NovexWorkspaceReadRequest(entry.workspaceRef, cursor)).ok)
            val changed = record(tools.workspaceRead(NovexWorkspaceReadRequest(entry.workspaceRef, maxChars = 2)))
            assertEquals(2, changed.getInt("covered_characters"))
            assertFalse(changed.getBoolean("complete"))
            assertNotEquals(entry.sha256, changed.getString("revision"))
            assertEquals("后来编辑的正文", FileNovexConversationWorkspaceStore(File(files.root, "workspace"))
                .readBytes(scope, entry.workspaceRef).toString(Charsets.UTF_8))
        } finally { database.close() }
    }

    @Test
    fun `document directory and search never complete reading and compact passages retain durable exact ranges`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "reads.db").absolutePath).allowMainThreadQueries().build()
        var database = open()
        val document = NovexDocumentSnapshot(NovexResourceRef("novex://documents/source"), "a".repeat(64), "parser-1", "制度文档",
            NovexDocumentFormat.DOCX, NovexDocumentStatus.READY, listOf("甲乙", "丙丁戊己").mapIndexed { index, text ->
                NovexDocumentBlock("block_$index", NovexDocumentBlockKind.PARAGRAPH, index, text,
                    source = NovexDocumentSourceAnchor("body", index))
            })
        val tools = NovexDocumentTools(NovexDocumentSnapshotStore { document })
        try {
            val repository = ChatRepository(database.chatDao())
            val session = repository.createSession("model")
            val journal = NovexContextReadJournal(repository)
            suspend fun record(result: NovexToolResult) = journal.record(session.id, "user", "reply", setOf("user", "reply"),
                AnswerIdentity.Nova, 4096, "source_tool", JSONObject(result.toJson()))
                .result.getJSONArray("read_coverage").getJSONObject(0)
            assertEquals(0, record(tools.documentInspect(NovexDocumentInspectRequest(document.ref))).getInt("covered_characters"))
            assertEquals(0, record(tools.documentRead(NovexDocumentReadRequest(document.ref, query = "甲"))).getInt("covered_characters"))
            var cursor: String? = null
            var lastCoverage: JSONObject? = null
            do {
                val result = tools.documentRead(NovexDocumentReadRequest(document.ref, cursor = cursor, maxChars = 3, compact = true))
                assertTrue(result.ok)
                lastCoverage = record(result)
                cursor = result.data["next_cursor"] as? String
            } while (cursor != null)
            assertEquals(6, lastCoverage!!.getInt("covered_characters"))
            assertTrue(lastCoverage.getBoolean("complete"))
            val saved = repository.novexContextUsage(session.id)
            database.close(); database = open()
            assertEquals(saved, ChatRepository(database.chatDao()).novexContextUsage(session.id))
            val otherBranch = NovexContextReadJournal(ChatRepository(database.chatDao())).record(session.id, "other-user", "other-reply",
                setOf("other-user", "other-reply"), AnswerIdentity.Nova, 4096, "source_tool",
                JSONObject(tools.documentRead(NovexDocumentReadRequest(document.ref, query = "甲")).toJson()))
            assertEquals(0, otherBranch.result.getJSONArray("read_coverage").getJSONObject(0).getInt("covered_characters"))
            val reparsed = NovexDocumentTools(NovexDocumentSnapshotStore { document.copy(parserVersion = "parser-2") })
            val changed = NovexContextReadJournal(ChatRepository(database.chatDao())).record(session.id, "user", "reply",
                setOf("user", "reply"), AnswerIdentity.Nova, 4096, "source_tool",
                JSONObject(reparsed.documentRead(NovexDocumentReadRequest(document.ref, maxChars = 1)).toJson()))
                .result.getJSONArray("read_coverage").getJSONObject(0)
            assertEquals(1, changed.getInt("covered_characters"))
            assertFalse(changed.getBoolean("complete"))
            assertNotEquals(lastCoverage.getString("revision"), changed.getString("revision"))
        } finally { database.close() }
    }
}
