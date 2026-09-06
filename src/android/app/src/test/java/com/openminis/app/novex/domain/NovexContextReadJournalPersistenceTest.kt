package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.adapter.*
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
@Config(application = Application::class, sdk = [28])
class NovexContextReadJournalPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `real scoped tool reads persist their exact coverage before returning a receipt`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = ChatRepository(database.chatDao())
            val session = repository.createSession("model")
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("长世界", "正文".repeat(100))).requireWorld()
            val configuration = NovexConversationConfigurationSnapshot(session.id,
                backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.world(world.id))))
            val reader = NovexContextReadService(workspace)
            val directory = reader.inspect(configuration).getJSONArray("sources")
            val source = (0 until directory.length()).map { directory.getJSONObject(it) }.single { it.getString("label").contains("长世界") }
            val first = reader.read(configuration, source.getString("source_id"), limit = 25)
            val journal = NovexContextReadJournal(repository)
            val receipt = journal.record(session.id, "user", "reply", setOf("user", "reply"), AnswerIdentity.Nova, 4096, "read", first)
            val saved = repository.novexContextUsage(session.id).single()
            assertEquals(25, saved.sourceReads.single().end)
            assertEquals(25, receipt.result.getJSONArray("read_coverage").getJSONObject(0).getInt("covered_characters"))
            assertFalse(receipt.result.getJSONArray("read_coverage").getJSONObject(0).getBoolean("complete"))
            val next = reader.read(configuration, source.getString("source_id"), offset = first.getInt("next_offset"), revision = first.getString("revision"))
            val completed = journal.record(session.id, "user", "reply", setOf("user", "reply"), AnswerIdentity.Nova, 4096, "read", next)
            assertTrue(completed.result.getJSONArray("read_coverage").getJSONObject(0).getBoolean("complete"))
            assertEquals(2, repository.novexContextUsage(session.id).single().sourceReads.size)
            val search = reader.search(configuration, "正文")
            val searched = journal.record(session.id, "other-user", "other-reply", setOf("other-user", "other-reply"), AnswerIdentity.Nova, 4096, "search", search)
            assertEquals(0, searched.result.getJSONArray("read_coverage").getJSONObject(0).getInt("covered_characters"))
            assertFalse(searched.result.getJSONArray("read_coverage").getJSONObject(0).getBoolean("complete"))
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "后来改写的新正文")))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                reader.read(configuration, source.getString("source_id"), offset = 25, revision = first.getString("revision"))
            } }
            assertEquals(2, repository.novexContextUsage(session.id).single { it.requestMessageId == "user" }.sourceReads.size)
        } finally { database.close() }
    }
}
