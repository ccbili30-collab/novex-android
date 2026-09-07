package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.adapter.NovexCheckpointSourceCapture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexCheckpointSourcePersistenceTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun `capture reads selected persisted message parts and retains them after original rows change`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "checkpoints.db").absolutePath).allowMainThreadQueries().build()
        var db = open()
        try {
            var repo = ChatRepository(db.chatDao())
            val session = repo.createSession("model")
            repo.appendMessage(session.id, "user", """[{"type":"text","value":"倒水，没有喝水记录。"}]""", messageId = "user")
            repo.appendMessage(session.id, "assistant", """[{"type":"text","value":"另一分支已喝水。"}]""", messageId = "old-reply")
            // Reply alternatives share the user parent; forking at a reply continues it.
            repo.forkReplyFrom(session.id, "user")
            repo.appendMessage(session.id, "assistant", """[{"type":"text","value":"只记录倒水，尚未饮用。"}]""", messageId = "selected-reply")
            db.close(); db = open(); repo = ChatRepository(db.chatDao())
            val path = repo.loadActiveMessages(session.id).map { it.id } + "pending-reply"
            val sources = NovexCheckpointSourceCapture.capture(session.id, path, repo.loadMessages(session.id))
            assertEquals(listOf("user", "selected-reply"), sources.map { it.messageId })
            val checkpoint = NovexPlaythroughCheckpointFactory.create("save", NovexConversationConfigurationSnapshot(session.id),
                path, "pending-reply", "原话", "未经核验的整理", "{}", 1, sources)
            val store = FileNovexConversationWorkspaceStore(File(files.root, "workspaces"))
            val scope = NovexConversationWorkspaceScope(session.id, path.dropLast(1), "pending-reply")
            NovexPlaythroughCheckpointWriter(store).save(scope, checkpoint, NovexWorkspaceProvenance(session.id, "pending-reply", "user", "call"))
            val original = repo.loadMessages(session.id).first { it.id == "user" }
            db.chatDao().insertMessage(original.copy(partsJson = "[]", updatedAt = 99))
            val saved = NovexCheckpointContinuation(FileNovexConversationWorkspaceStore(File(files.root, "workspaces"))).inspect(scope).single().checkpoint!!
            assertEquals(original.partsJson, saved.sourceEvents.first().partsJson)
            assertEquals(listOf("pending-reply"), saved.missingSourceMessageIds)
            assertFalse(saved.sourceEvents.any { it.partsJson.contains("另一分支") })
            assertTrue(runCatching { NovexCheckpointSourceCapture.capture("another-chat", path, repo.loadMessages(session.id)) }.isFailure)
        } finally { db.close() }
    }
}
