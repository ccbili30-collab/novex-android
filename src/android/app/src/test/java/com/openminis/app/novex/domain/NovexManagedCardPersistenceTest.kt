package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleReferenceTarget
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
    fun `native character copies remap version and module links on repeated exchange`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val original = workspace.apply(NovexCommand.CreateCharacter("记言人", """{"name":"记言人"}""")).requireCharacter()
            val variant = workspace.apply(NovexCommand.CreateVariant(original.character.id, "晚年", """{"name":"晚年记言人"}""")).requireVersion()
            val first = workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(original.original.id),
                ContentModuleType.CUSTOM, "早年经历")).requireModule()
            val second = workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(variant.id),
                ContentModuleType.CUSTOM, "晚年经历")).requireModule()
            val externalWorld = workspace.apply(NovexCommand.CreateWorld("包外世界")).requireWorld()
            workspace.apply(NovexCommand.AddModuleReference(first.id, ModuleReferenceTarget.module(second.id), 0))
            workspace.apply(NovexCommand.AddModuleReference(first.id, ModuleReferenceTarget.characterVersion(variant.id), 1))
            workspace.apply(NovexCommand.AddModuleReference(first.id, ModuleReferenceTarget.world(externalWorld.id), 2))
            var sourceId = original.character.id
            repeat(2) {
                val exported = workspace.apply(NovexCommand.ExportNativeCharacter(sourceId)).requireNativeCard()
                val parsed = NovexCardTransferParser.parse(NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(exported)))
                val copiedId = workspace.apply(NovexCommand.ImportNativeCard(parsed)).requireNativeImport().localId
                val copied = requireNotNull(workspace.character(copiedId))
                val copiedVariant = copied.character.variants.single()
                val copiedFirst = copied.modulesByVersion.getValue(copied.character.original.id).single()
                val copiedSecond = copied.modulesByVersion.getValue(copiedVariant.id).single()
                assertEquals(listOf(ModuleReferenceTarget.module(copiedSecond.id),
                    ModuleReferenceTarget.characterVersion(copiedVariant.id)),
                    workspace.module(copiedFirst.id)!!.references.map { it.target })
                val reexported = workspace.apply(NovexCommand.ExportNativeCharacter(copiedId)).requireNativeCard()
                val refs = JSONObject(reexported.documentJson).getJSONArray("versions").getJSONObject(0)
                    .getJSONArray("modules").getJSONObject(0).getJSONArray("references")
                assertEquals(3, refs.length())
                assertEquals("world", refs.getJSONObject(2).getString("targetKind"))
                assertEquals(externalWorld.id, refs.getJSONObject(2).getString("targetId"))
                sourceId = copiedId
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `interrupted confirmed creation leaves no partial cards after database reopen`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "rollback.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, path)
            .allowMainThreadQueries().build()
        var database = openDatabase()
        val media = File(files.root, "media")
        var workspace = NovexWorkspaceFactory.create(database, media)
        try {
            val existing = workspace.apply(NovexCommand.CreateWorld("原有世界", "不得改动旧资料")).requireWorld()
            val service = NovexManagementService(workspace,
                CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))),
                NovexManagementTransaction { work -> database.withTransaction {
                    work()
                    throw IllegalStateException("模拟写入后提交前中断")
                } })
            val operations = JSONArray()
            for ((operation, name) in listOf("create_world" to "新世界", "create_character" to "新角色", "create_game" to "新文游")) {
                operations.put(JSONObject().put("operation", operation).put("name", name)
                    .put("profile_json", JSONObject().put("name", name))
                    .put("modules", JSONArray().put(JSONObject().put("module_type", "custom")
                        .put("name", "完整正文").put("content_json", JSONObject().put("kind", "article").put("text", "不能留下半成品")))))
            }
            val configuration = NovexConversationConfigurationSnapshot("interrupted-creation")
            val proposal = service.propose(configuration, operations.toString(), "创建世界卡、角色卡和文游卡", "interrupted-cards")
            val error = assertThrows(IllegalStateException::class.java) { runBlocking {
                service.apply(configuration, proposal, "")
            } }
            assertEquals("模拟写入后提交前中断", error.message)
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, media)
            assertEquals(listOf(existing.id), workspace.worlds().map { it.world.id })
            assertEquals("不得改动旧资料", workspace.world(existing.id)!!.world.overview)
            assertTrue(workspace.characters().isEmpty())
            assertTrue(workspace.interactiveFictions().isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `native copy retains map item images and unknown content after original deletion`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val opaque = ContentModuleDocument.Unsupported("institution-v2", "customGrid",
                """{"council":{"minorityOpinions":["保留未决问题"],"futureField":true}}""")
            val source = workspace.apply(NovexCommand.SaveWorldPage(
                worldId = null, name = "图片与未知模块", overview = "", tagsJson = "[]",
                modules = listOf(
                    NovexModuleDraft("map", ContentModuleType.MAP, "地图",
                        ContentModuleDocumentCodec.encode(ContentModuleDocument.SingleImage("山河旧图"))),
                    NovexModuleDraft("regions", ContentModuleType.REGION, "地区",
                        """{"kind":"collection","items":[{"id":"mountain","name":"山地","description":"完整地区说明"}]}"""),
                    NovexModuleDraft("unknown", ContentModuleType.CUSTOM, "制度扩展",
                        ContentModuleDocumentCodec.encode(opaque)),
                ),
            )).requireWorld()
            val bytes = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=")
            for ((owner, slot) in listOf(
                ModuleOwner.world(source.id) to MediaAssetSlot.WORLD_COVER,
                ModuleOwner.contentModule("map") to MediaAssetSlot.MODULE_IMAGE,
                ModuleOwner.contentModuleItem("regions", "mountain") to MediaAssetSlot.MODULE_IMAGE,
            )) workspace.apply(NovexCommand.AttachImage(owner, slot, bytes, "image/png"))
            val exported = workspace.apply(NovexCommand.ExportNativeWorld(source.id)).requireNativeCard()
            val parsed = NovexCardTransferParser.parse(NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(exported)))
            val copyId = workspace.apply(NovexCommand.ImportNativeCard(parsed)).requireNativeImport().localId
            workspace.apply(NovexCommand.DeleteWorld(source.id))
            val copy = requireNotNull(workspace.world(copyId))
            val map = copy.modules.single { it.name == "地图" }
            val region = copy.modules.single { it.name == "地区" }
            val images = listOf(copy.media.getValue(MediaAssetSlot.WORLD_COVER),
                copy.moduleImages.getValue(map.id), copy.moduleItemImages.getValue(region.id).getValue("mountain"))
            assertEquals(1, images.map { it.id }.distinct().size)
            images.forEach { assertArrayEquals(bytes, File(it.managedPath).readBytes()) }
            val restoredOpaque = ContentModuleDocumentCodec.decode(copy.modules.single { it.name == "制度扩展" }.contentJson)
                as ContentModuleDocument.Unsupported
            assertEquals(opaque.originalType, restoredOpaque.originalType)
            assertEquals(opaque.presentation, restoredOpaque.presentation)
            val council = JSONObject(restoredOpaque.contentJson).getJSONObject("council")
            assertTrue(council.getBoolean("futureField"))
            assertEquals("保留未决问题", council.getJSONArray("minorityOpinions").getString(0))
            val reexported = workspace.apply(NovexCommand.ExportNativeWorld(copyId)).requireNativeCard()
            assertEquals(3, reexported.media.size)
            reexported.media.forEach { assertArrayEquals(bytes, it.bytes) }
        } finally {
            database.close()
        }
    }

    @Test
    fun `created game restores long rules identity and usable preset controls after native exchange`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "game.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, path)
            .allowMainThreadQueries().build()
        var database = openDatabase()
        val media = File(files.root, "media")
        var workspace = NovexWorkspaceFactory.create(database, media)
        try {
            val service = NovexManagementService(workspace,
                CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))),
                NovexManagementTransaction { work -> database.withTransaction { work() } })
            val rules = "每次重大选择保留主流与少数意见，不能保证改革必然成功。\n".repeat(400)
            val operations = JSONArray().put(JSONObject().put("operation", "create_game")
                .put("name", "记言之旅").put("launch_mode", "fixed_identity").put("player_identity", "年轻记言人")
                .put("modules", JSONArray()
                    .put(JSONObject().put("module_type", "game_narrative_rules").put("name", "推演规则")
                        .put("content_json", JSONObject().put("kind", "article").put("text", rules)))
                    .put(JSONObject().put("module_type", "game_quick_actions").put("name", "状态操作")
                        .put("content_json", JSONObject("""{"kind":"collection","items":[{"id":"health","name":"查看体力","behavior":"view","stateKeys":["health"],"actionKey":"show.health"}]}""")))))
            val configuration = NovexConversationConfigurationSnapshot("game-creation")
            val proposal = service.propose(configuration, operations.toString(), "创建文游卡记言之旅", "game-roundtrip")
            assertTrue(workspace.interactiveFictions().isEmpty())
            val address = service.apply(configuration, proposal, "").createdSubjects.single()
            val before = requireNotNull(workspace.interactiveFiction(address.id))
            val exported = workspace.apply(NovexCommand.ExportNativeInteractiveFiction(address.id)).requireNativeCard()
            val transfer = NovexCardTransferParser.parse(NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(exported)))
            val copyId = workspace.apply(NovexCommand.ImportNativeCard(transfer)).requireNativeImport().localId
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, media)
            val copy = requireNotNull(workspace.interactiveFiction(copyId))
            assertNotEquals(address.id, copyId)
            assertEquals(2, workspace.interactiveFictions().size)
            assertEquals(before.project.launchMode, copy.project.launchMode)
            assertEquals("年轻记言人", copy.project.playerIdentity)
            assertEquals(listOf("推演规则", "状态操作"), copy.modules.map { it.name })
            assertEquals(rules, ContentModuleDocumentCodec.decode(copy.modules.first().contentJson).toPlainText())
            assertTrue(workspace.apply(NovexCommand.ExportInteractiveFictionText(copyId)).requireText().contains(rules))
            val runtime = InteractiveFictionRuntimeSnapshotFactory.create(copy)
            val candidates = WorkspaceNovexContextLoader(workspace).load(configuration.copy(activeInteractiveFiction = runtime))
            assertTrue(candidates.any { it.content == rules })
            val control = runtime.presetControls.single()
            assertEquals("show.health", control.actionKey)
            val result = InteractiveFictionRuntime.invoke(control, PlaythroughState("branch", mapOf(
                "health" to PlaythroughValue.Number(80.0), "secret" to PlaythroughValue.Text("不属于体力面板"))))
            assertEquals(ConversationControlOutcome.View("查看体力", mapOf("health" to PlaythroughValue.Number(80.0))), result)
        } finally {
            database.close()
        }
    }

    @Test
    fun `created character preserves full profile and modules through native exchange and reopen`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "character.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, path)
            .allowMainThreadQueries().build()
        var database = openDatabase()
        val media = File(files.root, "media")
        var workspace = NovexWorkspaceFactory.create(database, media)
        try {
            val service = NovexManagementService(workspace,
                CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))),
                NovexManagementTransaction { work -> database.withTransaction { work() } })
            val profile = JSONObject("""{"name":"记言人","summary":"保留异议与完整记载。","systemPrompt":"以古代人的经验回答，不预言后世。","customExtension":{"school":"地方私学","rank":3},"customAttributes":[{"name":"记","value":"善于保存不同版本"}],"relationships":[{"characterName":"先生","relationship":"师徒","description":"允许质疑老师"}]}""")
            val originalText = "记下少数意见，不把临时判断当成永恒答案。\n".repeat(400)
            val operations = JSONArray().put(JSONObject().put("operation", "create_character")
                .put("name", "记言人").put("profile_json", profile)
                .put("modules", JSONArray().put(JSONObject().put("module_type", "custom")
                    .put("name", "行为与记录").put("content_json", JSONObject()
                        .put("kind", "article").put("text", originalText)))))
            val configuration = NovexConversationConfigurationSnapshot("character-creation")
            val proposal = service.propose(configuration, operations.toString(), "创建角色卡记言人", "character-roundtrip")
            assertTrue(workspace.characters().isEmpty())
            val address = service.apply(configuration, proposal, "").createdSubjects.single()
            val created = workspace.characters().single().character
            assertEquals(created.original.id, address.id)
            val page = requireNotNull(workspace.character(created.character.id))
            val beforeProfile = JSONObject(page.character.original.profileJson)
            assertEquals(profile.getString("systemPrompt"), beforeProfile.getString("systemPrompt"))
            val modules = page.modulesByVersion.getValue(address.id)
            assertEquals(originalText, ContentModuleDocumentCodec.decode(modules.single().contentJson).toPlainText())
            val contextEntries = WorkspaceNovexContextLoader(workspace).load(configuration.copy(
                backgroundSettings = listOf(BackgroundSetting(address))))
            assertEquals(originalText, contextEntries.single { it.sourceId == modules.single().id }.content)
            val exported = workspace.apply(NovexCommand.ExportNativeCharacter(created.character.id)).requireNativeCard()
            val transfer = NovexCardTransferParser.parse(NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(exported)))
            val copyId = workspace.apply(NovexCommand.ImportNativeCard(transfer)).requireNativeImport().localId
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, media)
            val copy = requireNotNull(workspace.character(copyId))
            assertNotEquals(created.character.id, copy.character.character.id)
            assertEquals(2, workspace.characters().size)
            val afterProfile = JSONObject(copy.character.original.profileJson)
            assertEquals(profile.getString("systemPrompt"), afterProfile.optString("systemPrompt"))
            assertEquals("地方私学", afterProfile.getJSONObject("customExtension").getString("school"))
            assertEquals("善于保存不同版本", afterProfile.getJSONArray("customAttributes").getJSONObject(0).getString("value"))
            assertEquals("允许质疑老师", afterProfile.getJSONArray("relationships").getJSONObject(0).getString("description"))
            assertEquals(originalText, ContentModuleDocumentCodec.decode(
                copy.modulesByVersion.getValue(copy.character.original.id).single().contentJson).toPlainText())
        } finally {
            database.close()
        }
    }

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
            val worldAddress = service.apply(configuration, proposal, "").createdSubjects.single()
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
            service.apply(managed, links, "")

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
