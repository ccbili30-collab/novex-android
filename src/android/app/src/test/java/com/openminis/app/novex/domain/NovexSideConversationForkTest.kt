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

    @Test fun forkCopiesHistorySettingsAndStaysHiddenWithCap() = runBlocking {
        val path = File(files.root, "side.db").absolutePath
        val db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, path)
            .allowMainThreadQueries().build()
        try {
            val repository = ChatRepository(db.chatDao())
            val parent = repository.createSession("model", title = "主线故事", conversationPrompt = "主线提示词")
            repository.appendMessage(parent.id, "user", """[{"type":"text","value":"第一轮"}]""")
            repository.appendMessage(parent.id, "assistant", """[{"type":"text","value":"开场"}]""")

            val side = repository.createSideSession(parent.id)
            assertEquals(parent.id, side.sideOfSession)
            assertTrue(side.title.orEmpty().startsWith("主线故事·侧"))
            // Hidden from the session list, visible as a side session.
            assertTrue(db.chatDao().listSessions().none { it.id == side.id })
            assertEquals(listOf(side.id), repository.listSideSessions(parent.id).map { it.id })
            // Fork copies the active history and the prompt snapshot.
            val sideMessages = repository.loadActiveMessages(side.id)
            assertEquals(2, sideMessages.size)
            assertEquals(listOf("user", "assistant"), sideMessages.map { it.role })
            assertEquals("主线提示词", repository.getSession(side.id)!!.conversationPrompt)
            // After the fork the histories diverge: parent-only writes stay parent-only.
            repository.appendMessage(parent.id, "user", """[{"type":"text","value":"主线新轮"}]""")
            assertEquals(2, repository.loadActiveMessages(side.id).size)
            // Cap: 10 side conversations per parent.
            repeat(9) { repository.createSideSession(parent.id) }
            assertEquals(10, repository.listSideSessions(parent.id).size)
            val failure = runCatching { repository.createSideSession(parent.id) }.exceptionOrNull()
            assertNotNull(failure)
            // Deleting a side session removes it without touching the parent.
            repository.deleteSession(side.id)
            assertEquals(9, repository.listSideSessions(parent.id).size)
            assertNotNull(repository.getSession(parent.id))
        } finally { db.close() }
    }
}
