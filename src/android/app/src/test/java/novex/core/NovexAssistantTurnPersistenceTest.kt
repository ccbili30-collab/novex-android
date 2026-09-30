package novex.core

import android.app.Application
import androidx.room.Room
import novex.android.data.NovexMainDatabase
import novex.android.data.chat.SessionRow
import novex.android.data.chat.MessageRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexAssistantTurnPersistenceTest {
    @Test fun `checkpointing a running turn preserves its position and later user replies`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), NovexMainDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = database.chatDao()
            dao.upsertSession(SessionRow("chat", modelId = "test", createdAt = 1, updatedAt = 1))
            fun message(id: String, role: String, text: String) = MessageRow(id, "chat", role, text, 1, sortOrder = -1)
            val user = dao.appendOnActivePath(message("user", "user", "原话"), "原话", 1)
            val running = dao.checkpointRunningTurn(message("reply", "assistant", "读取中"), "读取中", 2)
            assertEquals(user.id, running.parentMessageId)
            val next = dao.appendOnActivePath(message("next", "user", "后续消息"), "后续消息", 3)
            val saved = dao.checkpointRunningTurn(message("reply", "assistant", "已保存"), "已保存", 4)
            assertEquals(running.sortOrder, saved.sortOrder)
            assertEquals(running.createdAt, saved.createdAt)
            assertEquals(next.id, saved.activeChildId)
            assertEquals(next.id, dao.branchAnchorsOf("chat")!!.activeLeafMessageId)
            assertEquals(listOf("user", "reply", "next"), dao.historyFor("chat").map { it.id })
            assertEquals("已保存", dao.messageById("reply")!!.partsJson)
            assertEquals("原话", dao.messageById("user")!!.partsJson)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                dao.checkpointRunningTurn(message("user", "assistant", "错误覆盖"), "", 5)
            } }
            assertEquals("原话", dao.messageById("user")!!.partsJson)
        } finally { database.close() }
    }
}
