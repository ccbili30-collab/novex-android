package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.creative.*
import com.openminis.app.data.db.*
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import java.io.File
import kotlinx.coroutines.*
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
class NovexConversationDeletionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `deletion waits for task shutdown and retains saved cards sibling files and original provenance`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val chats = ChatRepository(db.chatDao())
            db.chatDao().insertSession(ChatSessionEntity("chat", modelId = "test", createdAt = 1, updatedAt = 1))
            db.chatDao().insertSession(ChatSessionEntity("other", modelId = "test", createdAt = 1, updatedAt = 1))
            db.chatDao().appendMessageOnActivePath(MessageEntity("user", "chat", "user", "原话", 1, sortOrder = -1), "原话", 1)
            val workspace = NovexWorkspaceFactory.create(db, File(folder.root, "media"))
            val drafts = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val world = drafts.subjects.single { it.kind == NovexContentKind.WORLD }
            val game = drafts.subjects.single { it.kind == NovexContentKind.INTERACTIVE_FICTION }
            workspace.apply(NovexCommand.SaveWorldPage(world.id, "保留作品", "已经保存"))
            val files = FileNovexConversationWorkspaceStore(File(folder.root, "workspace"))
            for (branch in listOf("left", "right")) files.writeText(
                NovexConversationWorkspaceScope("chat", listOf(branch), branch), NovexWorkspaceArea.OUTPUTS,
                "作品.md", "分支成果 $branch", "text/markdown", NovexWorkspaceProvenance("chat", branch))
            val artifacts = CreativeArtifactRepository(db, CreativeArtifactFileStore(File(folder.root, "artifacts")))
            val journal = NovexOperationJournal(File(folder.root, "operations"))
            val waiting = NovexToolOperation("chat", "reply", "call", "novex_write_card", "{}", "写卡")
            journal.save(NovexOperationRecord(waiting, NovexOperationStatus.WAITING))
            val other = waiting.copy(conversationId = "other")
            journal.save(NovexOperationRecord(other, NovexOperationStatus.WAITING))
            val stopped = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val deletion = NovexConversationDeletion(chats, workspace, files,
                WorkspaceCreativeArtifactBridge(files, artifacts), NovexToolExecution(journal),
                stopRuntime = { stopped.complete(Unit); finish.await() })
            val work = async { deletion.delete("chat") }
            stopped.await()
            assertNotNull(db.chatDao().getSession("chat"))
            assertEquals(NovexOperationStatus.WAITING, journal.read(waiting.id)!!.status)
            finish.complete(Unit)
            work.await()
            assertNull(db.chatDao().getSession("chat"))
            assertTrue(db.chatDao().loadMessages("chat").isEmpty())
            assertNotNull(db.chatDao().getSession("other"))
            assertEquals("已经保存", workspace.world(world.id)!!.world.overview)
            assertNull(workspace.interactiveFiction(game.id))
            assertEquals(listOf(world.id), workspace.worlds().map { it.world.id })
            val works = artifacts.list(CreativeArtifactQuery(conversationId = "chat"))
            assertEquals(2, works.size)
            assertEquals(setOf("left", "right"), works.map { NovexWorkspaceFileRef.parse(it.sourcePath!!).branchId }.toSet())
            assertEquals(2, files.inspectConversation("chat").size)
            assertEquals(NovexOperationStatus.DENIED, journal.read(waiting.id)!!.status)
            assertEquals(NovexOperationStatus.WAITING, journal.read(other.id)!!.status)
            deletion.delete("chat")
            assertEquals(2, artifacts.list(CreativeArtifactQuery(conversationId = "chat")).size)
        } finally { db.close() }
    }

    @Test fun `failed preservation leaves conversation and messages recoverable`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.chatDao().insertSession(ChatSessionEntity("chat", modelId = "test", createdAt = 1, updatedAt = 1))
            val workspace = NovexWorkspaceFactory.create(db, File(folder.root, "media"))
            val files = FileNovexConversationWorkspaceStore(File(folder.root, "workspace"))
            val artifacts = CreativeArtifactRepository(db, CreativeArtifactFileStore(File(folder.root, "artifacts")))
            val deletion = NovexConversationDeletion(ChatRepository(db.chatDao()), workspace, files,
                WorkspaceCreativeArtifactBridge(files, artifacts), NovexToolExecution(NovexOperationJournal(File(folder.root, "ops"))),
                stopRuntime = { error("任务停止失败") })
            assertThrows(IllegalStateException::class.java) { runBlocking { deletion.delete("chat") } }
            assertNotNull(db.chatDao().getSession("chat"))
        } finally { db.close() }
    }
}
