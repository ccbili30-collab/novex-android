package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.NovexCardPackageCodec
import com.openminis.app.data.character.NovexCardTransferParser
import com.openminis.app.data.character.toPlainText
import com.openminis.app.data.creative.CreativeArtifactFileStore
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import com.openminis.app.novex.adapter.WorkspaceNovexContextLoader
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real domain/storage/media adapters in an isolated host database; no Activity or model. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexManagedCardPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `confirmed world is complete in pages context export and a reopened database`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databasePath = File(files.root, "cards.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, databasePath)
            .allowMainThreadQueries().build()
        var database = openDatabase()
        val mediaRoot = File(files.root, "media")
        var workspace = NovexWorkspaceFactory.create(database, mediaRoot)
        try {
            val artifacts = CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts")))
            val service = NovexManagementService(workspace, artifacts, NovexManagementTransaction { work ->
                database.withTransaction { work() }
            })
            val originalText = "完整规则：原文与少数意见均须保留。\n".repeat(600)
            val modules = JSONArray()
                .put(JSONObject().put("module_type", "custom").put("name", "规则")
                    .put("content_json", JSONObject().put("kind", "article").put("text", originalText)))
                .put(JSONObject().put("module_type", "custom").put("name", "制度")
                    .put("content_json", JSONObject("""{"kind":"collection","items":[{"id":"council","name":"议事","summary":"听取意见","description":"完整正文：允许保留反对意见，不能只输出摘要。"}]}""")))
                .put(JSONObject().put("module_type", "timeline").put("name", "历史")
                    .put("content_json", JSONObject("""{"kind":"timeline","nodes":[{"time":"第一年","title":"初次议事","description":"留下未解决的问题。"}]}""")))
            val configuration = NovexConversationConfigurationSnapshot("creation-chat")
            val proposal = service.propose(configuration, JSONArray().put(JSONObject()
                .put("operation", "create_world").put("name", "完整验收世界")
                .put("overview", "长期创作验收").put("modules", modules)).toString(),
                "创建世界卡，完整保存这三个模块", "create-complete-world")
            assertTrue(workspace.worlds().isEmpty())
            val worldAddress = service.apply(configuration, proposal, proposal.confirmationPhrase).createdSubjects.single()
            val managed = configuration.copy(managedSubjects = listOf(ManagedSubject(worldAddress, ManagedAccess.EDIT)))
            val inspection = service.inspect(managed, worldAddress, null)
            assertEquals(listOf("规则", "制度", "历史"), inspection.modules.map { it.name })
            val sourceIds = inspection.modules.map { it.id }
            assertEquals(originalText, ContentModuleDocumentCodec.decode(inspection.modules.first().contentJson).toPlainText())
            val changes = JSONArray()
                .put(JSONObject().put("operation", "update_module").put("module_id", sourceIds[0]).put("name", "核心规则"))
                .put(JSONObject().put("operation", "add_reference").put("module_id", sourceIds[0])
                    .put("target_kind", "module").put("target_id", sourceIds[1]))
            val links = service.propose(managed, changes.toString(), "改名并关联制度", "link-complete-world")
            service.apply(managed, links, links.confirmationPhrase)

            val page = requireNotNull(workspace.world(worldAddress.id))
            assertEquals(listOf("核心规则", "制度", "历史"), page.modules.map { it.name })
            assertEquals(originalText, ContentModuleDocumentCodec.decode(page.modules.first().contentJson).toPlainText())
            val candidates = WorkspaceNovexContextLoader(workspace).load(managed.copy(
                backgroundSettings = listOf(BackgroundSetting(worldAddress)),
            ))
            assertEquals(originalText, candidates.single { it.sourceId == sourceIds[0] }.content)
            assertTrue(candidates.single { it.sourceId == sourceIds[1] }.content.contains("完整正文：允许保留反对意见，不能只输出摘要。"))
            assertTrue(candidates.single { it.sourceId == sourceIds[0] }.relatedSourceIds.contains(sourceIds[1]))

            val exported = workspace.apply(NovexCommand.ExportNativeWorld(worldAddress.id)).requireNativeCard()
            val parsed = NovexCardTransferParser.parse(NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(exported)))
            val copy = workspace.apply(NovexCommand.ImportNativeCard(parsed)).requireNativeImport()
            assertNotEquals(worldAddress.id, copy.localId)
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, mediaRoot)
            val restoredSource = requireNotNull(workspace.world(worldAddress.id))
            val restoredCopy = requireNotNull(workspace.world(copy.localId))
            assertEquals(2, workspace.worlds().size)
            assertEquals(sourceIds, restoredSource.modules.map { it.id })
            assertTrue(restoredCopy.modules.none { it.id in sourceIds })
            assertEquals(listOf("核心规则", "制度", "历史"), restoredCopy.modules.map { it.name })
            val expectedDocuments = page.modules.map { ContentModuleDocumentCodec.decode(it.type, it.contentJson) }
            assertEquals(expectedDocuments, restoredSource.modules.map { ContentModuleDocumentCodec.decode(it.type, it.contentJson) })
            assertEquals(expectedDocuments, restoredCopy.modules.map { ContentModuleDocumentCodec.decode(it.type, it.contentJson) })
            assertEquals(listOf(restoredCopy.modules[1].id),
                requireNotNull(workspace.module(restoredCopy.modules[0].id)).references.map { it.targetId })
            assertEquals(listOf(sourceIds[1]), requireNotNull(workspace.module(sourceIds[0])).references.map { it.targetId })
        } finally {
            database.close()
        }
    }
}
