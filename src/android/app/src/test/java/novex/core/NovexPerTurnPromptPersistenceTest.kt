package novex.core

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.ConversationSettingsSnapshot
import novex.android.data.NovexMainDatabase
import com.openminis.app.data.repository.ChatRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexPerTurnPromptPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun perTurnPromptSurvivesReopenAndBlankClearsIt() = runBlocking {
        val path = File(files.root, "per-turn.db").absolutePath
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), NovexMainDatabase::class.java, path)
            .allowMainThreadQueries().build()
        var db = open()
        var repository = ChatRepository(db.chatDao())
        val session = repository.createSession("unknown", title = "每轮注入")
        val original = ConversationSettingsSnapshot(conversationPrompt = "提示词", perTurnPrompt = "  每一轮都要向我提供 3~4 个选项  ")
        repository.writeConversationSettings(session.id, original)
        db.close()

        db = open(); repository = ChatRepository(db.chatDao())
        try {
            assertEquals("每一轮都要向我提供 3~4 个选项", repository.sessionById(session.id)!!.perTurnPrompt)
            repository.writeConversationSettings(
                session.id,
                ConversationSettingsSnapshot(conversationPrompt = "提示词", perTurnPrompt = "   "),
            )
            assertNull(repository.sessionById(session.id)!!.perTurnPrompt)
        } finally { db.close() }
    }
}
