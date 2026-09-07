package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleCollectionItem
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.NovexCardPackageCodec
import com.openminis.app.data.character.NovexCardTransferParser
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
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
@Config(application = Application::class, sdk = [28])
class NovexCardReferencePersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `native management can add a purposeful reference with source permission only and exposes its purpose`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("生生之学")).requireWorld()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            val source = NovexContentAddress.interactiveFiction(game.id)
            val configuration = NovexConversationConfigurationSnapshot("chat", managedSubjects = listOf(ManagedSubject(source, ManagedAccess.EDIT)))
            val artifacts = object : NovexManagementArtifactPort {
                override suspend fun exists(artifactId: String) = false
                override suspend fun describe(artifactId: String): NovexManagedArtifactDescription? = null
                override suspend fun attach(attachment: CreativeArtifactAttachment) = error("本场景不操作文件")
                override suspend fun detach(attachment: CreativeArtifactAttachment) = error("本场景不操作文件")
            }
            val service = NovexManagementService(workspace, artifacts)
            val raw = """[{"operation":"put_card_reference","subject_kind":"game","subject_id":"${game.id}","reference_id":"world-reference","target_kind":"world","target_id":"${world.id}","purpose":"background"}]"""
            val plan = service.propose(configuration, raw, "将生生之学设为文游背景", "plan")
            assertEquals(setOf(source), plan.targets)
            service.apply(configuration, plan, plan.confirmationPhrase)
            val inspection = service.inspect(configuration, source, null).toToolJson()
            assertEquals("background", inspection.getJSONArray("card_references").getJSONObject(0).getString("purpose"))
            assertEquals(world.id, workspace.referencesFrom(source).single().target.subject.id)
            assertFalse(NovexManagementPolicy.canRead(configuration, NovexContentAddress.world(world.id)))
            assertEquals(AnswerIdentity.Nova, configuration.answerIdentity)
            assertNull(configuration.activeInteractiveFiction)
            val otherGame = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "另一文游")).requireInteractiveFiction()
            val otherSource = NovexContentAddress.interactiveFiction(otherGame.id)
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("other-reference", otherSource,
                NovexReferenceTarget(NovexContentAddress.world(world.id)), NovexReferencePurpose.BACKGROUND)))
            val removal = """[{"operation":"remove_card_reference","subject_kind":"game","subject_id":"${game.id}","reference_id":"other-reference"}]"""
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.propose(configuration, removal, "删除引用", "remove") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                workspace.apply(NovexCommand.RemoveCardReference("other-reference", source))
            } }
            assertEquals("other-reference", workspace.referencesFrom(otherSource).single().id)
        } finally { database.close() }
    }

    @Test
    fun `migration from version 26 adds reference storage while preserving existing cards`() = runBlocking {
        val path = File(files.root, "migration.db").absolutePath
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, path)
            .addMigrations(AppDatabase.MIGRATION_26_27, AppDatabase.MIGRATION_27_28, AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30).allowMainThreadQueries().build()
        var database = open()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("已有世界", "迁移前正文")).requireWorld()
            database.openHelper.writableDatabase.execSQL("DROP TABLE novex_card_references")
            database.openHelper.writableDatabase.version = 26
            database.close()
            database = open()
            val restored = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            assertEquals("迁移前正文", restored.world(world.id)!!.world.overview)
            assertTrue(restored.referencesFrom(NovexContentAddress.world(world.id)).isEmpty())
        } finally { database.close() }
    }

    @Test
    fun `an invalid source module in a dependency package rolls back every imported card`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("世界")).requireWorld()
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "世界的模块", id = "world-module"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "文游")).requireInteractiveFiction()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("reference", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(NovexContentAddress.world(world.id)), NovexReferencePurpose.BACKGROUND)))
            val exported = workspace.apply(NovexCommand.ExportNativeInteractiveFiction(game.id)).requireNativeCard()
            val document = org.json.JSONObject(exported.documentJson)
            document.getJSONObject("referenceBundle").getJSONArray("references").getJSONObject(0)
                .put("sourceModuleId", "world-module")
            val validated = NovexCardTransferParser.parse(exported.copy(documentJson = document.toString()))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { workspace.apply(NovexCommand.ImportNativeCard(validated)) } }
            assertEquals(listOf(world.id), workspace.worlds().map { it.world.id })
            assertEquals(listOf(game.id), workspace.interactiveFictions().map { it.project.id })
        } finally { database.close() }
    }

    @Test
    fun `a package actor points to the imported concrete version and retains its instructions`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val variant = workspace.apply(NovexCommand.CreateVariant(role.character.id, "晚年", """{"name":"伏生","systemPrompt":"晚年版本的扮演要求"}""")).requireVersion()
            val world = workspace.apply(NovexCommand.CreateWorld("晚年所在世界")).requireWorld()
            workspace.apply(NovexCommand.LinkCharacterVersion(world.id, variant.id, 0))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("world", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(NovexContentAddress.world(world.id)), NovexReferencePurpose.BACKGROUND)))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("actor", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(NovexContentAddress.characterVersion(variant.id)), NovexReferencePurpose.ANSWER_IDENTITY)))
            val exported = workspace.apply(NovexCommand.ExportNativeInteractiveFiction(game.id)).requireNativeCard()
            val imported = workspace.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(exported))).requireNativeImport()
            val importedLinks = workspace.referencesFrom(NovexContentAddress.interactiveFiction(imported.localId))
            val actor = importedLinks.single { it.purpose == NovexReferencePurpose.ANSWER_IDENTITY }.target.subject
            assertNotEquals(variant.id, actor.id)
            val importedRole = workspace.characterForVersion(actor.id)!!.character
            assertNotEquals(role.character.id, importedRole.character.id)
            assertEquals("晚年", importedRole.allVersions.single { it.id == actor.id }.label)
            assertEquals(importedLinks.single { it.purpose == NovexReferencePurpose.BACKGROUND }.target.subject.id,
                workspace.characterForVersion(actor.id)!!.worldsByVersion[actor.id]!!.single().id)
            val frozen = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(imported.localId)
            assertEquals(AnswerIdentity.CharacterVersion(actor.id), frozen.answerIdentity)
            assertTrue(frozen.contentJson.contains("晚年版本的扮演要求"))
        } finally { database.close() }
    }

    @Test
    fun `missing package dependencies keep their foreign address without binding local identifiers`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("同名目标", "本地已有正文")).requireWorld()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "文游")).requireInteractiveFiction()
            val target = NovexReferenceTarget(NovexContentAddress.world(world.id))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("reference", NovexContentAddress.interactiveFiction(game.id), target,
                NovexReferencePurpose.BACKGROUND, targetLabel = "同名目标")))
            val exported = workspace.apply(NovexCommand.ExportNativeInteractiveFiction(game.id)).requireNativeCard()
            val document = org.json.JSONObject(exported.documentJson)
            val bundle = document.getJSONObject("referenceBundle")
            val root = bundle.getString("root")
            val records = bundle.getJSONArray("cards")
            bundle.put("cards", org.json.JSONArray().apply { repeat(records.length()) { index ->
                records.getJSONObject(index).takeIf { it.getString("key") == root }?.let { put(it) }
            } })
            val imported = workspace.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(exported.copy(documentJson = document.toString())))).requireNativeImport()
            val reference = workspace.referencesFrom(NovexContentAddress.interactiveFiction(imported.localId)).single()
            assertNotEquals(world.id, reference.target.subject.id)
            assertEquals(NovexReferenceTargetStatus.MISSING_CARD, workspace.referenceStatus(reference.target))
            assertEquals(target, reference.unresolvedTarget)
            assertEquals("同名目标", reference.targetLabel)
            val again = workspace.apply(NovexCommand.ExportNativeInteractiveFiction(imported.localId)).requireNativeCard()
            val reimported = workspace.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(again))).requireNativeImport()
            assertEquals(target, workspace.referencesFrom(NovexContentAddress.interactiveFiction(reimported.localId)).single().unresolvedTarget)
        } finally { database.close() }
    }

    @Test
    fun `native package restores dependencies and module references to new objects rather than originals or names`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("同名世界", "真正的引用目标")).requireWorld()
            workspace.apply(NovexCommand.CreateWorld("同名世界", "不能偷偷绑定的其他世界"))
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "制度",
                ContentModuleDocumentCodec.encode(ContentModuleDocument.Collection(listOf(ContentModuleCollectionItem("entry", "帝议", description = "帝议原文")))), id = "original-module"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "文游")).requireInteractiveFiction()
            val source = NovexContentAddress.interactiveFiction(game.id)
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("rules", source,
                NovexReferenceTarget(NovexContentAddress.world(world.id), "original-module", "entry"), NovexReferencePurpose.RULES)))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("cycle", NovexContentAddress.world(world.id),
                NovexReferenceTarget(source), NovexReferencePurpose.RULES)))
            val exported = workspace.apply(NovexCommand.ExportNativeInteractiveFiction(game.id)).requireNativeCard()
            val validated = NovexCardTransferParser.parse(NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(exported)))
            val imported = workspace.apply(NovexCommand.ImportNativeCard(validated)).requireNativeImport()
            val importedSource = NovexContentAddress.interactiveFiction(imported.localId)
            val reference = workspace.referencesFrom(importedSource).single()
            assertNotEquals(world.id, reference.target.subject.id)
            assertNotEquals("original-module", reference.target.moduleId)
            assertEquals("entry", reference.target.entryId)
            assertEquals("真正的引用目标", workspace.world(reference.target.subject.id)!!.world.overview)
            assertEquals(NovexReferenceTargetStatus.AVAILABLE, workspace.referenceStatus(reference.target))
            assertEquals(importedSource, workspace.referencesFrom(reference.target.subject).single().target.subject)
            assertEquals(world.id, workspace.referencesFrom(source).single().target.subject.id)
        } finally { database.close() }
    }

    @Test
    fun `ordinary conversation follows background references but never management or actor links`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("背景世界")).requireWorld()
            val linked = workspace.apply(NovexCommand.CreateWorld("关联资料", "沿引用读取的背景正文")).requireWorld()
            val managed = workspace.apply(NovexCommand.CreateWorld("管理目标", "未授权管理的正文")).requireWorld()
            val role = workspace.apply(NovexCommand.CreateCharacter("仅作身份引用的角色", """{"name":"秘密角色","systemPrompt":"不能进入背景的扮演要求"}""")).requireCharacter()
            val source = NovexContentAddress.world(world.id)
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("background", source, NovexReferenceTarget(NovexContentAddress.world(linked.id)), NovexReferencePurpose.BACKGROUND)))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("manage", source, NovexReferenceTarget(NovexContentAddress.world(managed.id)), NovexReferencePurpose.MANAGEMENT)))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("actor", source, NovexReferenceTarget(NovexContentAddress.characterVersion(role.original.id)), NovexReferencePurpose.ANSWER_IDENTITY)))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("cycle", NovexContentAddress.world(linked.id), NovexReferenceTarget(source), NovexReferencePurpose.BACKGROUND)))
            val configuration = NovexConversationConfigurationSnapshot("chat", backgroundSettings = listOf(BackgroundSetting(source)))
            val candidates = com.openminis.app.novex.adapter.WorkspaceNovexContextLoader(workspace).load(configuration)
            val text = candidates.joinToString("\n") { it.content }
            assertTrue(text.contains("沿引用读取的背景正文"))
            assertFalse(text.contains("未授权管理的正文"))
            assertFalse(text.contains("不能进入背景的扮演要求"))
            assertFalse(text.contains("秘密角色"))
            assertEquals(candidates.size, candidates.map { it.sourceId }.distinct().size)
        } finally { database.close() }
    }

    @Test
    fun `starting a game also freezes the conversation backgrounds without adopting managed only cards`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("对话背景", "启动前已采用的背景")).requireWorld()
            val managed = workspace.apply(NovexCommand.CreateWorld("管理目标", "管理目标的私有正文")).requireWorld()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            val backgrounds = listOf(BackgroundSetting(NovexContentAddress.world(world.id)))
            val frozen = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id, backgrounds)
            val configuration = NovexConversationConfiguration.open(NovexConversationConfigurationSnapshot("chat",
                backgroundSettings = backgrounds, managedSubjects = listOf(ManagedSubject(NovexContentAddress.world(managed.id), ManagedAccess.EDIT))))
                .apply(NovexConversationCommand.ActivateInteractiveFiction(frozen)).snapshot
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "随后改变的背景")))
            val text = com.openminis.app.novex.adapter.WorkspaceNovexContextLoader(workspace).load(configuration).joinToString("\n") { it.content }
            assertTrue(text.contains("启动前已采用的背景"))
            assertFalse(text.contains("随后改变的背景"))
            assertFalse(text.contains("管理目标的私有正文"))
        } finally { database.close() }
    }

    @Test
    fun `rules from another game never activate its opening player or controls and cycles terminate`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val other = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "参考文游", playerIdentity = "另一个文游的玩家",
                modules = listOf(
                    NovexModuleDraft("rules", ContentModuleType.GAME_NARRATIVE_RULES, "帝议", """{"text":"可借用的帝议规则"}"""),
                    NovexModuleDraft("opening", ContentModuleType.GAME_OPENING, "开局", """{"text":"另一个文游的开局"}"""),
                    NovexModuleDraft("controls", ContentModuleType.GAME_QUICK_ACTIONS, "操作", """{"items":[{"id":"act","name":"另一个文游的操作"}]}""")))).requireInteractiveFiction()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "活动文游")).requireInteractiveFiction()
            val source = NovexContentAddress.interactiveFiction(game.id)
            val target = NovexContentAddress.interactiveFiction(other.id)
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("rules", source, NovexReferenceTarget(target), NovexReferencePurpose.RULES)))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("cycle", target, NovexReferenceTarget(source), NovexReferencePurpose.RULES)))
            val frozen = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id)
            val configuration = NovexConversationConfiguration.open(NovexConversationConfigurationSnapshot("chat"))
                .apply(NovexConversationCommand.ActivateInteractiveFiction(frozen)).snapshot
            val text = com.openminis.app.novex.adapter.WorkspaceNovexContextLoader(workspace).load(configuration).joinToString("\n") { it.content }
            assertTrue(text.contains("可借用的帝议规则"))
            assertFalse(text.contains("另一个文游的"))
            assertTrue(configuration.controls.isEmpty())
            assertNull(configuration.playerIdentity)
            assertEquals(game.id, configuration.activeInteractiveFiction!!.projectId)
        } finally { database.close() }
    }

    @Test
    fun `a module entry reference freezes only that stable entry and reports its later removal`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("世界", "整卡未被采用的概述")).requireWorld()
            val entries = ContentModuleDocument.Collection(listOf(
                ContentModuleCollectionItem("chosen", "帝议", description = "被采用的帝议规则"),
                ContentModuleCollectionItem("other", "帝议", description = "同名但未被采用的规则")))
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "制度",
                ContentModuleDocumentCodec.encode(entries), id = "rules"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            val target = NovexReferenceTarget(NovexContentAddress.world(world.id), "rules", "chosen")
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("rule", NovexContentAddress.interactiveFiction(game.id),
                target, NovexReferencePurpose.RULES)))
            val frozen = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id)
            workspace.apply(NovexCommand.SaveModule("rules", "制度", ContentModuleDocumentCodec.encode(entries.copy(items = entries.items.drop(1)))))
            assertEquals(NovexReferenceTargetStatus.MISSING_ENTRY, workspace.referenceStatus(target))
            val text = com.openminis.app.novex.adapter.WorkspaceNovexContextLoader(workspace).load(
                NovexConversationConfigurationSnapshot("chat", activeInteractiveFiction = frozen)).joinToString("\n") { it.content }
            assertTrue(text.contains("被采用的帝议规则"))
            assertFalse(text.contains("同名但未被采用的规则"))
            assertFalse(text.contains("整卡未被采用的概述"))
        } finally { database.close() }
    }

    @Test
    fun `explicit game actor is frozen and stops contributing instructions after changing identity`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"name":"伏生","systemPrompt":"启动时的扮演要求"}""")).requireCharacter()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("actor", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(NovexContentAddress.characterVersion(role.original.id)), NovexReferencePurpose.ANSWER_IDENTITY)))
            val frozen = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id)
            val configuration = NovexConversationConfiguration.open(NovexConversationConfigurationSnapshot("chat"))
                .apply(NovexConversationCommand.ActivateInteractiveFiction(frozen)).snapshot
            assertEquals(AnswerIdentity.CharacterVersion(role.original.id), configuration.answerIdentity)
            workspace.apply(NovexCommand.SaveCharacterVersion(role.character.id, role.original.id, "伏生", "本体",
                """{"name":"伏生","systemPrompt":"后来改写的扮演要求"}"""))
            val loader = com.openminis.app.novex.adapter.WorkspaceNovexContextLoader(workspace)
            val text = loader.load(configuration).joinToString("\n") { it.content }
            assertTrue(text.contains("启动时的扮演要求"))
            assertFalse(text.contains("后来改写的扮演要求"))
            val switched = loader.load(configuration.copy(answerIdentity = NovexPersonaPresets.gameHost)).joinToString("\n") { it.content }
            assertFalse(switched.contains("启动时的扮演要求"))
            assertFalse(switched.contains("后来改写的扮演要求"))
        } finally { database.close() }
    }

    @Test
    fun `a started game freezes linked background without becoming its background character`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("生生之学", "最初的世界规则")).requireWorld()
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"name":"伏生","summary":"古书传人","systemPrompt":"仅伏生扮演时的秘密要求"}""")).requireCharacter()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            val source = NovexContentAddress.interactiveFiction(game.id)
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("world", source,
                NovexReferenceTarget(NovexContentAddress.world(world.id)), NovexReferencePurpose.BACKGROUND)))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("role", source,
                NovexReferenceTarget(NovexContentAddress.characterVersion(role.original.id)), NovexReferencePurpose.BACKGROUND)))
            val frozen = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id)
            val configuration = NovexConversationConfiguration.open(NovexConversationConfigurationSnapshot("chat"))
                .apply(NovexConversationCommand.ActivateInteractiveFiction(frozen)).snapshot
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "后来改写的世界规则")))
            val candidates = com.openminis.app.novex.adapter.WorkspaceNovexContextLoader(workspace).load(configuration)
            val text = candidates.joinToString("\n") { it.content }
            assertTrue(text.contains("最初的世界规则"))
            assertTrue(text.contains("古书传人"))
            assertFalse(text.contains("后来改写的世界规则"))
            assertFalse(text.contains("仅伏生扮演时的秘密要求"))
            assertEquals(NovexPersonaPresets.gameHost, configuration.answerIdentity)
            assertTrue(configuration.managedSubjects.isEmpty())
        } finally { database.close() }
    }

    @Test
    fun `one explicit role identity is distinct from background people and must be replaced deliberately`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("世界")).requireWorld()
            val first = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val second = workspace.apply(NovexCommand.CreateCharacter("史官")).requireCharacter()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            val source = NovexContentAddress.interactiveFiction(game.id)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                workspace.apply(NovexCommand.PutCardReference(NovexCardReference("invalid", source,
                    NovexReferenceTarget(NovexContentAddress.world(world.id)), NovexReferencePurpose.ANSWER_IDENTITY)))
            } }
            val background = NovexCardReference("background", source,
                NovexReferenceTarget(NovexContentAddress.characterVersion(first.original.id)), NovexReferencePurpose.BACKGROUND)
            val answer = NovexCardReference("answer", source,
                NovexReferenceTarget(NovexContentAddress.characterVersion(second.original.id)), NovexReferencePurpose.ANSWER_IDENTITY)
            workspace.apply(NovexCommand.PutCardReference(background))
            workspace.apply(NovexCommand.PutCardReference(answer))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                workspace.apply(NovexCommand.PutCardReference(background.copy(id = "another-answer", purpose = NovexReferencePurpose.ANSWER_IDENTITY)))
            } }
            assertEquals(answer, workspace.referencesFrom(source).single { it.purpose == NovexReferencePurpose.ANSWER_IDENTITY })
            workspace.apply(NovexCommand.PutCardReference(answer.copy(target = background.target)))
            assertEquals(background.target, workspace.referencesFrom(source).single { it.purpose == NovexReferencePurpose.ANSWER_IDENTITY }.target)
        } finally { database.close() }
    }

    @Test
    fun `deleting a target preserves independent users and marks their reference missing`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val deleted = workspace.apply(NovexCommand.CreateWorld("将删除的世界")).requireWorld()
            val retained = workspace.apply(NovexCommand.CreateWorld("独立世界", "保留正文")).requireWorld()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "使用此世界的文游")).requireInteractiveFiction()
            val target = NovexReferenceTarget(NovexContentAddress.world(deleted.id))
            val incoming = NovexCardReference("incoming", NovexContentAddress.interactiveFiction(game.id), target, NovexReferencePurpose.BACKGROUND)
            workspace.apply(NovexCommand.PutCardReference(incoming))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("outgoing", target.subject,
                NovexReferenceTarget(NovexContentAddress.world(retained.id)), NovexReferencePurpose.RULES)))
            assertEquals(listOf(incoming), workspace.referencesTo(target.subject))
            workspace.apply(NovexCommand.DeleteWorld(deleted.id))
            assertNotNull(workspace.interactiveFiction(game.id))
            assertEquals("保留正文", workspace.world(retained.id)!!.world.overview)
            assertEquals(listOf(incoming), workspace.referencesFrom(incoming.source))
            assertTrue(workspace.referencesFrom(target.subject).isEmpty())
            assertEquals(NovexReferenceTargetStatus.MISSING_CARD, workspace.referenceStatus(target))
        } finally { database.close() }
    }

    @Test
    fun `a purposeful incoming reference protects a private empty target during exit cleanup`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val drafts = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val world = drafts.subjects.single { it.kind == NovexContentKind.WORLD }
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "使用此世界的文游")).requireInteractiveFiction()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("protected", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(world), NovexReferencePurpose.BACKGROUND)))
            val result = workspace.apply(NovexCommand.FinalizeConversationDrafts("chat")).requireDraftFinalization()
            assertEquals(listOf(world), result.snapshot.subjects)
            assertNotNull(workspace.world(world.id))
            assertTrue(workspace.worlds().isEmpty())
        } finally { database.close() }
    }

    @Test
    fun `purposeful references and backlinks survive restart using exact identifiers`() = runBlocking {
        val path = File(files.root, "references.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, path)
            .allowMainThreadQueries().build()
        var database = openDatabase()
        val media = File(files.root, "media")
        try {
            var workspace = NovexWorkspaceFactory.create(database, media)
            val world = workspace.apply(NovexCommand.CreateWorld("生生之学")).requireWorld()
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            val source = NovexContentAddress.interactiveFiction(game.id)
            val background = NovexCardReference("world-use", source,
                NovexReferenceTarget(NovexContentAddress.world(world.id)), NovexReferencePurpose.BACKGROUND)
            val person = NovexCardReference("person-use", source,
                NovexReferenceTarget(NovexContentAddress.characterVersion(role.original.id)), NovexReferencePurpose.BACKGROUND)
            workspace.apply(NovexCommand.PutCardReference(background))
            workspace.apply(NovexCommand.PutCardReference(person))
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, media)
            assertEquals(setOf(background, person), workspace.referencesFrom(source).toSet())
            assertEquals(listOf(person), workspace.referencesTo(person.target.subject))
            assertEquals(NovexReferencePurpose.BACKGROUND, workspace.referencesTo(person.target.subject).single().purpose)
            assertTrue(workspace.referencesFrom(NovexContentAddress.interactiveFiction("same-name-but-other-id")).isEmpty())
        } finally { database.close() }
    }
}
