package com.openminis.app.data.db

import novex.android.data.*
import novex.android.data.cards.*
import novex.android.data.chat.*
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.ConversationBranchGraph
import com.openminis.app.data.repository.ChatRepository
import novex.core.AnswerIdentity
import novex.core.ContextUsageRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationBranchRepositoryInstrumentedTest {
    private lateinit var database: NovexMainDatabase
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, NovexMainDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ChatRepository(database.chatDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun retrySwitchRestartAndDeleteKeepSiblingTimelines() = runBlocking {
        val session = repository.createSession(modelId = "test-model")
        val u1 = repository.appendMessage(session.id, "user", textParts("question"))
        val a1 = repository.appendMessage(session.id, "assistant", textParts("old answer"))
        val u2 = repository.appendMessage(session.id, "user", textParts("follow up"))
        val a2 = repository.appendMessage(session.id, "assistant", textParts("old tail"))

        val fork = repository.forkReplyFrom(session.id, u1.id)
        assertEquals(listOf(u1.id), fork.activeMessages.map { it.id })
        assertTrue(fork.allMessages.map { it.id }.containsAll(listOf(a1.id, u2.id, a2.id)))

        val a3 = repository.appendMessage(session.id, "assistant", textParts("new answer"))
        repository.recordNovexContextUsage(
            session.id,
            usageRecord("old-context", u1.id, a1.id),
        )
        repository.recordNovexContextUsage(
            session.id,
            usageRecord("new-context", u1.id, a3.id),
        )
        val newPath = repository.loadActiveConversation(session.id)
        assertEquals(listOf(u1.id, a3.id), newPath.activeMessages.map { it.id })
        assertEquals(
            ConversationBranchGraph.SiblingPosition(2, 2),
            newPath.graph.siblingPosition(a3.id),
        )
        database.chatDao().addMarker(marker("new-summary", session.id, a3.id, 200))

        repository.switchMessageSibling(session.id, a3.id, -1)
        val reopened = ChatRepository(database.chatDao()).loadActiveConversation(session.id)
        assertEquals(listOf(u1.id, a1.id, u2.id, a2.id), reopened.activeMessages.map { it.id })
        assertEquals(a2.id, database.chatDao().sessionById(session.id)?.activeLeafMessageId)
        assertEquals("old tail", database.chatDao().sessionById(session.id)?.lastMessage)
        database.chatDao().addMarker(marker("old-summary", session.id, a2.id, 300))
        assertEquals(
            "old-summary",
            repository.latestActiveCompactMarker(session.id, reopened.activeMessages)?.summary,
        )

        val newAgain = repository.switchMessageSibling(session.id, a1.id, 1)
        assertEquals(
            "new-summary",
            repository.latestActiveCompactMarker(session.id, newAgain.activeMessages)?.summary,
        )
        repository.switchMessageSibling(session.id, a3.id, -1)

        val deletion = repository.deleteMessageBranch(session.id, a1.id)
        val afterDelete = deletion.conversation
        assertEquals(listOf(u1.id, a3.id), afterDelete.activeMessages.map { it.id })
        assertEquals(setOf(u1.id, a3.id), afterDelete.allMessages.map { it.id }.toSet())
        assertEquals(setOf(a1.id, u2.id, a2.id), deletion.deletedMessages.map { it.id }.toSet())
        assertEquals(listOf("new-summary"), database.chatDao().markersFor(session.id).map { it.summary })
        assertEquals(
            listOf("new-context"),
            repository.novexContextUsage(session.id).map { it.id },
        )
    }

    @Test
    fun activeErrorStaysOnSelectedBranch() = runBlocking {
        val session = repository.createSession(modelId = "test-model", title = "Branched")
        val user = repository.appendMessage(session.id, "user", textParts("question"))
        val oldAnswer = repository.appendMessage(session.id, "assistant", textParts("old answer"))
        repository.forkReplyFrom(session.id, user.id)
        val newAnswer = repository.appendMessage(session.id, "assistant", textParts("new answer"))
        repository.switchMessageSibling(session.id, newAnswer.id, -1)
        database.chatDao().addMarker(marker("old-summary", session.id, oldAnswer.id, 300))

        repository.updateLastActiveAssistantError(session.id, "selected error")
        val sourceRows = repository.historyFor(session.id).associateBy { it.id }
        assertEquals("selected error", sourceRows.getValue(oldAnswer.id).errorInfo)
        assertNull(sourceRows.getValue(newAnswer.id).errorInfo)
    }

    private fun textParts(text: String): String =
        org.json.JSONArray()
            .put(org.json.JSONObject().put("type", "text").put("value", text))
            .toString()


    private fun marker(
        id: String,
        sessionId: String,
        anchorId: String,
        createdAt: Long,
    ) = CompactMarkerRow(
        id = id,
        sessionId = sessionId,
        summary = id,
        firstKeptSortOrder = Int.MAX_VALUE,
        compactedCount = 1,
        createdAt = createdAt,
        lastCompactedMessageId = anchorId,
        version = 2,
    )

    private fun usageRecord(
        id: String,
        requestMessageId: String,
        responseMessageId: String,
    ) = ContextUsageRecord(
        id = id,
        requestMessageId = requestMessageId,
        responseMessageId = responseMessageId,
        branchId = responseMessageId,
        answerIdentity = AnswerIdentity.Nova,
        includedSources = emptyList(),
        usedTokens = 0,
        effectiveWindowTokens = 200_000,
        createdAt = 1L,
    )
}
