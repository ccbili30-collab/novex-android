package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
class NovexImmediateLibraryPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun `visible library receives committed changes without navigation`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val cards = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val game = cards.subjects.single { it.kind == NovexContentKind.INTERACTIVE_FICTION }
            withTimeout(10_000) {
                val listening = CompletableDeferred<Unit>()
                val visible = async(start = CoroutineStart.UNDISPATCHED) {
                    com.openminis.app.novex.adapter.observeNovexLibraryChanges(database).first {
                        val found = workspace.interactiveFictions().any { it.project.id == game.id }
                        listening.complete(Unit)
                        found
                    }
                }
                listening.await()
                workspace.apply(NovexCommand.SaveInteractiveFictionPage(game.id, "新作品", "正文"))
                visible.await()
            }
        } finally { database.close() }
    }

    @Test fun `first deferred access recovers old hidden content without cleaning empty targets`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val media = File(files.root, "media")
            val workspace = NovexWorkspaceFactory.create(database, media)
            val original = workspace.apply(NovexCommand.EnsureConversationDrafts("old-chat")).requireConversationDrafts()
            val role = original.subjects.single { it.kind == NovexContentKind.CHARACTER_VERSION }
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(role.id), ContentModuleType.CUSTOM,
                "人物设定", """{"text":"保留原文"}"""))
            // Reproduce an older install: content exists, ownership still marks all cards private.
            database.novexConversationDraftDao().save(com.openminis.app.data.db.NovexConversationDraftEntity(
                "old-chat", NovexConversationDraftCodec.encode(original)))
            val restored = NovexWorkspaceFactory.createDeferred(database, media)
            assertEquals(role.id, restored.characters().single().character.original.id)
            assertEquals(3, restored.conversationDrafts("old-chat")!!.cards.size)
            assertEquals(2, restored.conversationDrafts("old-chat")!!.cards.count { it.isPrivate })
            assertTrue(restored.worlds().isEmpty())
            assertTrue(restored.interactiveFictions().isEmpty())
            assertTrue(restored.modules(ModuleOwner.characterVersion(role.id)).modules.single().contentJson.contains("保留原文"))
        } finally { database.close() }
    }

    @Test fun `saving content makes its original card visible without leaving chat and survives reopen`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "library.db").absolutePath
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, path).allowMainThreadQueries().build()
        var database = open()
        val media = File(files.root, "media")
        try {
            var workspace = NovexWorkspaceFactory.create(database, media)
            val cards = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            assertTrue(workspace.worlds().isEmpty())
            assertTrue(workspace.characters().isEmpty())
            assertTrue(workspace.interactiveFictions().isEmpty())
            val game = cards.subjects.single { it.kind == NovexContentKind.INTERACTIVE_FICTION }
            workspace.apply(NovexCommand.SaveInteractiveFictionPage(projectId = game.id, name = "生生", summary = "规则正文"))
            assertEquals(listOf(game.id), workspace.interactiveFictions().map { it.project.id })
            assertFalse(workspace.conversationDrafts("chat")!!.cards.single { it.subject == game }.isPrivate)
            database.close()
            database = open()
            workspace = NovexWorkspaceFactory.create(database, media)
            assertEquals("规则正文", workspace.interactiveFictions().single().project.summary)
            assertEquals(game.id, workspace.interactiveFictions().single().project.id)
            assertEquals("chat", workspace.conversationDrafts("chat")!!.conversationId)
            assertTrue(workspace.worlds().isEmpty())
            assertTrue(workspace.characters().isEmpty())
        } finally { database.close() }
    }

    @Test fun `module only writing enters library and outer rollback restores hidden empty placeholder`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
                .subjects.single { it.kind == NovexContentKind.WORLD }
            try {
                database.withTransaction {
                    workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "规则", """{"text":"正文"}"""))
                    assertEquals(world.id, workspace.worlds().single().world.id)
                    error("rollback")
                }
            } catch (expected: IllegalStateException) { assertEquals("rollback", expected.message) }
            assertTrue(workspace.worlds().isEmpty())
            assertTrue(workspace.modules(ModuleOwner.world(world.id)).modules.isEmpty())
            assertTrue(workspace.conversationDrafts("chat")!!.cards.single { it.subject == world }.isPrivate)
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "规则", """{"text":"正文"}"""))
            assertEquals(world.id, workspace.worlds().single().world.id)
        } finally { database.close() }
    }
}
