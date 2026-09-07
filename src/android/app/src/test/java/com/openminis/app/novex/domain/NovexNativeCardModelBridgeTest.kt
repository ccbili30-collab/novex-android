package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.creative.CreativeArtifactFileStore
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import com.openminis.app.tools.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Opt-in local bridge: private source files and credentials never enter CI or the repository. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexNativeCardModelBridgeTest {
    @Test fun originalSourceThroughNativeCardService() = runBlocking {
        val path = System.getenv("NOVEX_CARD_BRIDGE_DIR")
        assumeTrue("Only enabled for local private-fixture verification", !path.isNullOrBlank())
        val root = File(requireNotNull(path))
        val sourceStore = FileNovexDocumentSnapshotRepository(File(root, "documents"))
        val documentRef = NovexResourceRef(File(root, "document-ref.txt").readText().trim())
        val document = requireNotNull(sourceStore.find(documentRef))
        var db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, File(root, "native.db").absolutePath).allowMainThreadQueries().build()
        var workspace = NovexWorkspaceFactory.create(db, File(root, "media"))
        workspace.apply(NovexCommand.EnsureConversationDrafts("original-case"))
        var configuration = NovexConversationConfigurationSnapshot("original-case")
        fun management() = NovexManagementService(workspace, CreativeArtifactRepository(db, CreativeArtifactFileStore(File(root, "artifacts"))))
        fun service() = NovexCardFileService(workspace, management(), NovexCardFileOperations(NovexCardSourceModules(sourceStore) { it == documentRef }),
            NovexManagementTransaction { block -> db.withTransaction { block() } })
        val definitions = NovexManagementTools.modelDefinitions() + NovexCardFileTools.definitions() + NovexDocumentAgentTools.providerDefinitions() +
            AgentTools.makeAgentTools().filter { it.name == "present_choices" }
        File(root, "tools.json").writeText(JSONArray(definitions.map { it.toOpenAIJson() }).toString())
        File(root, "guide.txt").writeText(NovexProductToolGuide.build(definitions.map { it.name }.toSet()))
        val documentTools = NovexDocumentToolRouter(NovexDocumentTools(sourceStore))
        val proposals = mutableMapOf<String, NovexManagementPlan>()
        File(root, "ready.json").writeText(JSONObject().put("document", JSONObject(documentTools.execute("document_inspect", JSONObject().put("document_ref", documentRef.value).toString()).toJson()))
            .put("directory", management().inspect(configuration, null, null).toToolJson()).toString())
        try {
            val deadline = System.currentTimeMillis() + 15 * 60_000
            var index = 0
            while (System.currentTimeMillis() < deadline) {
                val requestFile = File(root, "request-$index.json")
                if (!requestFile.exists()) { Thread.sleep(100); continue }
                val request = JSONObject(requestFile.readText())
                val name = request.getString("name")
                if (name == "__verify_and_close") {
                    db.close()
                    db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, File(root, "native.db").absolutePath).allowMainThreadQueries().build()
                    workspace = NovexWorkspaceFactory.create(db, File(root, "media"))
                    val draft = requireNotNull(workspace.conversationDrafts("original-case"))
                    val games = draft.cards.filter { it.subject.kind == NovexContentKind.INTERACTIVE_FICTION }.filter { workspace.modules(ModuleOwner.interactiveFiction(it.subject.id)).modules.isNotEmpty() }
                    assertEquals("Exactly the requested game, with no unsolicited filled world or character", 1, games.size)
                    val modules = workspace.modules(ModuleOwner.interactiveFiction(games.single().subject.id)).modules
                    assertEquals(48, modules.size)
                    assertEquals(document.blocks.joinToString("\n") { it.text }, modules.joinToString("\n") { JSONObject(it.contentJson).getString("text") })
                    assertTrue(draft.cards.filter { it.subject.kind != NovexContentKind.INTERACTIVE_FICTION }.all { management().inspect(configuration, it.subject, null).modules.isEmpty() })
                    assertNull(configuration.activeInteractiveFiction)
                    assertEquals(AnswerIdentity.Nova, configuration.answerIdentity)
                    File(root, "verified.json").writeText(JSONObject().put("reopened", true).put("exact_source", true)
                        .put("module_count", modules.size).put("card", management().inspect(configuration, games.single().subject, null).toToolJson()).toString())
                    return@runBlocking
                }
                val result = runCatching {
                    val args = request.getJSONObject("arguments")
                    val users = request.getJSONArray("user_requests").let { a -> (0 until a.length()).map(a::getString) }
                    when {
                        name in NovexCardFileTools.names -> service().execute(configuration, name, args, users, request.getString("operation_id")).also { configuration = it.configuration }.payload
                        name in NovexDocumentToolRouter.TOOL_NAMES -> JSONObject(documentTools.execute(name, args.toString()).toJson())
                        name == NovexManagementTools.INSPECT -> {
                            val kind = args.optString("subject_kind")
                            val target = if (kind.isBlank()) null else NovexContentAddress(when (kind) {
                                "world" -> NovexContentKind.WORLD; "character_version" -> NovexContentKind.CHARACTER_VERSION; "game" -> NovexContentKind.INTERACTIVE_FICTION
                                else -> error("无效对象类型") }, args.getString("subject_id"))
                            management().inspect(configuration, target, args.optString("module_id").takeIf { it.isNotBlank() }).toToolJson().apply {
                                if (args.optBoolean("include_advanced")) put("advanced_change_guide", NovexManagementTools.advancedGuide())
                            }
                        }
                        name == NovexManagementTools.PROPOSE -> management().propose(configuration, args.getString("changes"), users.last(), request.getString("operation_id"), users.dropLast(1)).let { plan ->
                            proposals[plan.id] = plan
                            JSONObject().put("proposal_id", plan.id).put("requires_confirmation", plan.requiresConfirmation).put("summary", plan.summary)
                        }
                        name == NovexManagementTools.APPLY -> {
                            val plan = requireNotNull(proposals[args.getString("proposal_id")])
                            management().apply(configuration, plan, users.last())
                            JSONObject().put("saved", true).put("message", "已执行，请回读核对")
                        }
                        name == "present_choices" -> JSONObject().put("waiting_for_user", true).put("choices", args.get("choices"))
                        else -> error("本次原生测试未启用此工具")
                    }
                }.getOrElse { JSONObject().put("error", it.message ?: it.javaClass.simpleName) }
                val temporary = File(root, "response-$index.tmp")
                temporary.writeText(result.toString())
                check(temporary.renameTo(File(root, "response-$index.json")))
                File(root, "configuration.json").writeText(NovexConversationConfigurationCodec.encode(configuration))
                index++
            }
            error("Local bridge timed out")
        } finally { db.close() }
    }
}
