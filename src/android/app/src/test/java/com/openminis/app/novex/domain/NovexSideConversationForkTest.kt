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
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexSideConversationForkTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun sideOpensBlankSharesSettingsStaysHiddenWithCapAndCascade() = runBlocking {
        val path = File(files.root, "side.db").absolutePath
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, path)
            .allowMainThreadQueries().build()
        try {
            val repository = ChatRepository(db.chatDao())
            val parent = repository.createSession(
                "model", title = "主线故事",
                conversationPrompt = "主线提示词",
                perTurnPrompt = "每轮给出选项",
            )
            repository.appendMessage(parent.id, "user", """[{"type":"text","value":"第一轮"}]""")
            repository.appendMessage(parent.id, "assistant", """[{"type":"text","value":"开场"}]""")

            val side = repository.createSideSession(parent.id)
            assertEquals(parent.id, side.sideOfSession)
            assertTrue(side.title.orEmpty().startsWith("主线故事·侧"))
            // Blank fork (decision 13): no main-line history in UI or model context.
            assertEquals(0, repository.loadActiveMessages(side.id).size)
            // Configuration snapshot is shared verbatim.
            assertEquals("主线提示词", repository.getSession(side.id)!!.conversationPrompt)
            assertEquals("每轮给出选项", repository.getSession(side.id)!!.perTurnPrompt)
            assertEquals(parent.worldId, repository.getSession(side.id)!!.worldId)
            // Hidden from the session list, visible as a side session.
            assertTrue(db.chatDao().listSessions().none { it.id == side.id })
            assertEquals(listOf(side.id), repository.listSideSessions(parent.id).map { it.id })
            // Histories never sync in either direction.
            repository.appendMessage(parent.id, "user", """[{"type":"text","value":"主线新轮"}]""")
            repository.appendMessage(side.id, "user", """[{"type":"text","value":"侧边讨论"}]""")
            assertEquals(3, repository.loadActiveMessages(parent.id).size)
            assertEquals(1, repository.loadActiveMessages(side.id).size)
            // Cap: 10 side conversations per parent.
            repeat(9) { repository.createSideSession(parent.id) }
            assertEquals(10, repository.listSideSessions(parent.id).size)
            val failure = runCatching { repository.createSideSession(parent.id) }.exceptionOrNull()
            assertNotNull(failure)
            // Deleting a side session removes it without touching the parent.
            repository.deleteSession(side.id)
            assertEquals(9, repository.listSideSessions(parent.id).size)
            assertNotNull(repository.getSession(parent.id))
            // Deleting the parent cascades its side conversations (no orphans:
            // side rows are gone, not just hidden from the list).
            val survivorId = repository.listSideSessions(parent.id).first().id
            repository.deleteSession(parent.id)
            assertNull(repository.getSession(parent.id))
            assertNull(repository.getSession(survivorId))
            assertEquals(0, db.chatDao().listSideSessions(parent.id).size)
        } finally { db.close() }
    }
}
