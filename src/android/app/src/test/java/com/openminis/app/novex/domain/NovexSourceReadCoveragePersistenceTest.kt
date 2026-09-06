package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
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
@Config(application = Application::class, sdk = [28])
class NovexSourceReadCoveragePersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `read ranges survive restart without replacing initial sources or borrowing a sibling branch`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "coverage.db").absolutePath).allowMainThreadQueries().build()
        var database = open()
        try {
            var repository = ChatRepository(database.chatDao())
            val session = repository.createSession("model")
            val firstRead = NovexSourceRead("rules", "帝议", "one", 0, 50, 100)
            val record = ContextUsageRecord("usage", "user", "reply", "reply", AnswerIdentity.Nova,
                listOf(ContextSourceUsage(ContextSourceKind.ANSWER_IDENTITY, "nova", "诺瓦", 12)),
                usedTokens = 12, effectiveWindowTokens = 4096, sourceReads = listOf(firstRead))
            repository.recordNovexContextUsage(session.id, record)
            repository.recordNovexContextUsage(session.id, record.copy(id = "sibling-usage", responseMessageId = "sibling", branchId = "sibling",
                sourceReads = listOf(firstRead.copy(start = 50, end = 100))))
            database.close()
            database = open()
            repository = ChatRepository(database.chatDao())
            val records = repository.novexContextUsage(session.id)
            val restored = NovexContextUsageLedger.open(NovexContextUsageLedgerSnapshot(session.id, records))
                .latestByRequestForActivePath(setOf("user", "reply")).getValue("user")
            assertEquals(listOf(firstRead), restored.sourceReads)
            assertEquals(record.includedSources, restored.includedSources)
            assertFalse(NovexSourceReadCoverage.from(restored.sourceReads).single().complete)
            repository.recordNovexContextUsage(session.id, restored.copy(sourceReads = restored.sourceReads + firstRead.copy(start = 50, end = 100)))
            val completed = repository.novexContextUsage(session.id).single { it.id == record.id }
            assertTrue(NovexSourceReadCoverage.from(completed.sourceReads).single().complete)
            assertEquals(record.includedSources, completed.includedSources)
            assertEquals(2, repository.novexContextUsage(session.id).size)
        } finally { database.close() }
    }
}
