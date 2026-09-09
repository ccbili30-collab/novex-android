package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.creative.CreativeArtifactFileStore
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexNaturalCardExecutionTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun `save as game edits its module in library and stable outputs distinguish identical copies from retry`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("chat"))
            val transaction = NovexManagementTransaction { block -> db.withTransaction { block() } }
            val service = NovexCardFileService(workspace,
                NovexManagementService(workspace, CreativeArtifactRepository(db, CreativeArtifactFileStore(File(files.root, "files"))), transaction),
                NovexCardFileOperations(NovexCardSourceModules(NovexDocumentSnapshotStore { null }) { true }), transaction)
            var config = NovexConversationConfigurationSnapshot("chat", executionMode = NovexExecutionMode.FREE)
            val args = JSONObject("""{"kind":"game","name":"生生","creation_key":"game-1","modules":[{"name":"规则","text":"原文规则"},{"name":"开场","text":"开场原文"}]}""")
            val first = service.execute(config, "novex_write_card", args, listOf("存为文游卡"), "call-1")
            config = first.configuration
            val id = first.applied!!.createdSubjects.single().id
            assertEquals(id, workspace.interactiveFictions().single().project.id)
            val retry = service.execute(config, "novex_write_card", args, listOf("存为文游卡"), "new-call-id")
            assertTrue(retry.applied!!.replayed)
            assertEquals(1, workspace.interactiveFictions().size)
            val copy = service.execute(config, "novex_write_card", JSONObject(args.toString()).put("creation_key", "game-2"), listOf("存为文游卡"), "call-2")
            assertNotEquals(id, copy.applied!!.createdSubjects.single().id)
            val modules = workspace.interactiveFiction(id)!!.modules
            service.execute(config, "novex_write_module", JSONObject().put("module_id", modules[0].id).put("text", "精简规则"), listOf("把第二段之前的规则改简短"), "edit")
            service.execute(config, "novex_move_module", JSONObject().put("module_id", modules[1].id).put("position", 0), listOf("开场移到最前面"), "move")
            val saved = workspace.interactiveFiction(id)!!.modules
            assertEquals(listOf("开场", "规则"), saved.map { it.name })
            assertTrue(saved[0].contentJson.contains("开场原文"))
            assertTrue(saved[1].contentJson.contains("精简规则"))
            assertEquals(2, workspace.interactiveFictions().size)
            assertEquals(AnswerIdentity.Nova, config.answerIdentity)
            assertNull(config.activeInteractiveFiction)
        } finally { db.close() }
    }
}
