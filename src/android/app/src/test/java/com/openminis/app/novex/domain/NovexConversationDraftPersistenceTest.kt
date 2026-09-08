package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.creative.CreativeArtifactFileStore
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
@Config(application = Application::class, sdk = [28])
class NovexConversationDraftPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `two pending creations reserve different empty targets`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("chat"))
            val service = NovexManagementService(workspace,
                CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))))
            val config = NovexConversationConfigurationSnapshot("chat")
            val plans = listOf("生生", "山海").map { name ->
                service.propose(config, """[{"operation":"create_game","name":"$name"}]""", "创建文游卡$name", name)
            }
            val targets = plans.flatMap { it.draftTargets.values }.toSet()
            assertEquals(2, targets.size)
            val finalized = workspace.apply(NovexCommand.FinalizeConversationDrafts("chat")).requireDraftFinalization()
            assertEquals(targets, finalized.snapshot.subjects.toSet())
            assertEquals(2, finalized.snapshot.pendingWrites.size)
        } finally { database.close() }
    }

    @Test
    fun `another explicit creation gets another private card and preserves the first work`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("chat"))
            val service = NovexManagementService(workspace,
                CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))))
            val config = NovexConversationConfigurationSnapshot("chat")
            val created = mutableListOf<NovexContentAddress>()
            for (name in listOf("生生", "山海")) {
                val request = "创建文游卡$name"
                val plan = service.propose(config, """[{"operation":"create_game","name":"$name","summary":"规则"}]""", request, name)
                created += service.apply(config, plan, request).createdSubjects.single()
            }
            assertEquals(2, created.distinct().size)
            assertEquals(listOf("生生", "山海"), created.map { workspace.interactiveFiction(it.id)!!.project.name })
            assertEquals(created.map { it.id }.toSet(), workspace.interactiveFictions().map { it.project.id }.toSet())
            assertEquals(4, workspace.conversationDrafts("chat")!!.cards.size)
            workspace.apply(NovexCommand.FinalizeConversationDrafts("chat"))
            assertEquals(2, workspace.interactiveFictions().size)
        } finally { database.close() }
    }

    @Test
    fun `workspace output is recovered into the creative library with provenance after restart`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "workspace-artifact.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, path).allowMainThreadQueries().build()
        var database = openDatabase()
        val workspaceFiles = File(files.root, "workspace")
        val scope = NovexConversationWorkspaceScope("chat", emptyList(), "reply")
        try {
            val store = FileNovexConversationWorkspaceStore(workspaceFiles)
            val written = NovexConversationWorkspaceTools(scope, store, NovexWorkspaceProvenance("chat", "reply", "message", "tool"))
                .workspaceWrite(NovexWorkspaceWriteRequest(NovexWorkspaceArea.OUTPUTS, "帝议.md", "完整文档正文"))
            assertTrue(written.ok)
            database.close()
            database = openDatabase()
            val artifacts = CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts")))
            val bridge = com.openminis.app.data.creative.WorkspaceCreativeArtifactBridge(
                FileNovexConversationWorkspaceStore(workspaceFiles), artifacts)
            bridge.reconcile(scope)
            val saved = artifacts.list().single()
            assertEquals("帝议.md", saved.artifact.title)
            assertEquals(CreativeArtifactOrigin("chat", "reply", "message", "tool"), saved.artifact.origin)
            assertEquals("完整文档正文", artifacts.bytes(saved.artifact.id).toString(Charsets.UTF_8))
            bridge.reconcile(scope)
            assertEquals(1, artifacts.list().single().revisions.size)
            assertNull(bridge.capture("workspace_read", written.toJson(), scope))
        } finally { database.close() }
    }

    @Test
    fun `outer conversation rollback keeps an image restored by the database`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("原有世界")).requireWorld()
            val slot = com.openminis.app.data.character.MediaAssetSlot.WORLD_COVER
            val bytes = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a9sAAAAASUVORK5CYII=")
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.world(world.id), slot, bytes, "image/png"))
            val path = workspace.world(world.id)!!.media.getValue(slot).managedPath
            assertThrows(IllegalStateException::class.java) { runBlocking {
                database.withTransaction {
                    workspace.apply(NovexCommand.DeleteWorld(world.id))
                    error("模拟整个对话事务回滚")
                }
            } }
            assertEquals(path, workspace.world(world.id)!!.media.getValue(slot).managedPath)
            assertArrayEquals(bytes, File(path).readBytes())
        } finally { database.close() }
    }

    @Test
    fun `conversation transaction and simultaneous draft bootstrap complete without blocking each other`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            withTimeout(10_000) {
                coroutineScope {
                    val started = CompletableDeferred<Unit>()
                    val continueBatch = CompletableDeferred<Unit>()
                    val batch = async(Dispatchers.IO) {
                        database.withTransaction {
                            started.complete(Unit)
                            continueBatch.await()
                            workspace.apply(NovexCommand.EnsureConversationDrafts("batch"))
                        }
                    }
                    started.await()
                    val independent = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        workspace.apply(NovexCommand.EnsureConversationDrafts("independent"))
                    }
                    continueBatch.complete(Unit)
                    batch.await()
                    independent.await()
                }
            }
            assertEquals(3, workspace.conversationDrafts("batch")!!.cards.size)
            assertEquals(3, workspace.conversationDrafts("independent")!!.cards.size)
        } finally { database.close() }
    }

    @Test
    fun `reopening replenishes cleaned slots without replacing a retained work`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val original = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val world = original.subjects.single { it.kind == NovexContentKind.WORLD }
            workspace.apply(NovexCommand.SaveWorldPage(world.id, "保留作品", "正文"))
            workspace.apply(NovexCommand.FinalizeConversationDrafts("chat"))
            val reopened = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            assertEquals(3, reopened.cards.size)
            assertEquals(world, reopened.subjects.single { it.kind == NovexContentKind.WORLD })
            assertFalse(reopened.cards.single { it.subject == world }.isPrivate)
            assertTrue(reopened.subjects.filterNot { it == world }.none { it in original.subjects })
            assertEquals("正文", workspace.world(world.id)!!.world.overview)
        } finally { database.close() }
    }

    @Test
    fun `explicitly mounted private role remains readable without entering the global library`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val drafts = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val role = drafts.subjects.single { it.kind == NovexContentKind.CHARACTER_VERSION }
            val config = NovexConversationConfigurationSnapshot("chat", managedSubjects = listOf(ManagedSubject(role, ManagedAccess.EDIT)))
            val service = NovexManagementService(workspace,
                CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))))
            val read = service.inspect(config, role, null)
            assertTrue(read.selectedSubjectJson!!.contains("本体"))
            assertEquals(3, read.subjects.size)
            assertTrue(read.subjects.single { it.subject == role }.label.contains("未命名角色"))
            assertTrue(workspace.characters().isEmpty())
        } finally { database.close() }
    }

    @Test
    fun `pending native write survives restart and protects its exact empty target until applied`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "pending.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, path).allowMainThreadQueries().build()
        var database = openDatabase()
        val media = File(files.root, "media")
        fun service(workspace: NovexWorkspace) = NovexManagementService(workspace,
            CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))),
            NovexManagementTransaction { work -> database.withTransaction { work() } })
        try {
            var workspace = NovexWorkspaceFactory.create(database, media)
            val drafts = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val game = drafts.subjects.single { it.kind == NovexContentKind.INTERACTIVE_FICTION }
            val config = NovexConversationConfigurationSnapshot("chat")
            val request = "把这些做成文游卡"
            val proposed = service(workspace).propose(config,
                """[{"operation":"create_game","name":"生生","summary":"有规则的共创开局","modules":[{"module_type":"custom","name":"规则","content_json":{"kind":"article","text":"完整规则"}}]}]""", request, "durable-plan")
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, media)
            val finalized = workspace.apply(NovexCommand.FinalizeConversationDrafts("chat")).requireDraftFinalization()
            assertEquals(listOf(game), finalized.snapshot.subjects)
            val restored = service(workspace).pendingPlan(config, proposed.id)!!
            assertEquals(proposed, restored)
            assertEquals(listOf(game), service(workspace).apply(config, restored, request).createdSubjects)
            assertNull(service(workspace).pendingPlan(config, proposed.id))
        } finally { database.close() }
    }

    @Test
    fun `explicit native creation fills the private game with modules without another confirmation`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val drafts = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val game = drafts.subjects.single { it.kind == NovexContentKind.INTERACTIVE_FICTION }
            val service = NovexManagementService(workspace,
                CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))),
                NovexManagementTransaction { work -> database.withTransaction { work() } })
            val config = NovexConversationConfigurationSnapshot("chat")
            val plan = service.propose(config,
                """[{"operation":"create_game","name":"生生","modules":[{"module_type":"custom","name":"帝议规则","content_json":{"kind":"article","text":"完整规则正文"}}]}]""",
                "把这些做成文游卡", "create-game")
            val result = service.apply(config, plan, "把这些做成文游卡")
            assertEquals(listOf(game), result.createdSubjects)
            val saved = workspace.interactiveFiction(game.id)!!
            assertEquals("生生", saved.project.name)
            assertEquals("帝议规则", saved.modules.single().name)
            assertTrue(saved.modules.single().contentJson.contains("完整规则正文"))
            assertEquals(listOf(game.id), workspace.interactiveFictions().map { it.project.id })
            val replay = service.apply(config, plan, "把这些做成文游卡")
            assertTrue(replay.replayed)
            assertEquals(listOf(game), replay.createdSubjects)
            assertTrue(replay.changes.isEmpty())
            assertEquals("生生", workspace.interactiveFiction(game.id)!!.project.name)
        } finally { database.close() }
    }

    @Test
    fun `migration preserves shared cards and failed trio creation rolls back every new row`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "migration.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, path)
            .addMigrations(AppDatabase.MIGRATION_25_26, AppDatabase.MIGRATION_26_27, AppDatabase.MIGRATION_27_28, AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31, AppDatabase.MIGRATION_31_32).allowMainThreadQueries().build()
        var database = openDatabase()
        val media = File(files.root, "media")
        try {
            var workspace = NovexWorkspaceFactory.create(database, media)
            val shared = workspace.apply(NovexCommand.CreateWorld("迁移前世界", "原文保留")).requireWorld()
            database.openHelper.writableDatabase.execSQL("DROP TABLE novex_conversation_drafts")
            restoreVersion31LibraryTable(database.openHelper.writableDatabase)
            database.openHelper.writableDatabase.version = 25
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, media)
            assertEquals("原文保留", workspace.world(shared.id)!!.world.overview)
            assertThrows(IllegalStateException::class.java) { runBlocking {
                database.withTransaction {
                    workspace.apply(NovexCommand.EnsureConversationDrafts("interrupted"))
                    error("模拟提交前中断")
                }
            } }
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, media)
            assertNull(workspace.conversationDrafts("interrupted"))
            assertEquals(listOf(shared.id), workspace.worlds().map { it.world.id })
            assertTrue(workspace.characters().isEmpty())
            assertTrue(workspace.interactiveFictions().isEmpty())
            assertEquals(3, workspace.apply(NovexCommand.EnsureConversationDrafts("after-migration"))
                .requireConversationDrafts().cards.size)
        } finally { database.close() }
    }

    @Test
    fun `incoming module character and saved conversation references protect otherwise empty drafts`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val drafts = workspace.apply(NovexCommand.EnsureConversationDrafts("owner")).requireConversationDrafts()
            val world = drafts.subjects.single { it.kind == NovexContentKind.WORLD }
            val character = drafts.subjects.single { it.kind == NovexContentKind.CHARACTER_VERSION }
            val game = drafts.subjects.single { it.kind == NovexContentKind.INTERACTIVE_FICTION }
            val shared = workspace.apply(NovexCommand.CreateWorld("共享世界")).requireWorld()
            val module = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(shared.id), ContentModuleType.CUSTOM, "资料引用")).requireModule()
            workspace.apply(NovexCommand.AddModuleReference(module.id, com.openminis.app.data.character.ModuleReferenceTarget.world(world.id), 0))
            workspace.apply(NovexCommand.LinkCharacterVersion(shared.id, character.id, 0))
            val config = NovexConversationConfigurationSnapshot("other", managedSubjects = listOf(ManagedSubject(game, ManagedAccess.READ_ONLY)))
            database.chatDao().insertSession(com.openminis.app.data.db.ChatSessionEntity(
                "other", modelId = "test", createdAt = 1, updatedAt = 1,
                novexConfigurationJson = NovexConversationConfigurationCodec.encode(config),
            ))
            val result = workspace.apply(NovexCommand.FinalizeConversationDrafts("owner")).requireDraftFinalization()
            assertTrue(result.removedSubjects.isEmpty())
            assertEquals(drafts, result.snapshot)
            assertNotNull(workspace.world(world.id))
            assertNotNull(workspace.interactiveFiction(game.id))
        } finally { database.close() }
    }

    @Test
    fun `writes survive restart before exit and exit only deletes unused unprotected private cards`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "publication.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, path).allowMainThreadQueries().build()
        var database = openDatabase()
        val media = File(files.root, "media")
        try {
            var workspace = NovexWorkspaceFactory.create(database, media)
            val shared = workspace.apply(NovexCommand.CreateWorld("未命名世界")).requireWorld()
            val drafts = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val world = drafts.subjects.single { it.kind == NovexContentKind.WORLD }
            val character = drafts.cards.single { it.subject.kind == NovexContentKind.CHARACTER_VERSION }
            val game = drafts.subjects.single { it.kind == NovexContentKind.INTERACTIVE_FICTION }
            workspace.apply(NovexCommand.SaveWorldPage(world.id, "生生之学", "帝议原文已经写入"))
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, media)
            assertEquals("帝议原文已经写入", workspace.world(world.id)!!.world.overview)
            assertEquals(setOf(shared.id, world.id), workspace.worlds().map { it.world.id }.toSet())

            val result = workspace.apply(NovexCommand.FinalizeConversationDrafts("chat", setOf(game))).requireDraftFinalization()
            assertTrue(result.promotedSubjects.isEmpty()) // The save already entered the library.
            assertEquals(setOf(character.subject), result.removedSubjects)
            assertEquals(setOf(shared.id, world.id), workspace.worlds().map { it.world.id }.toSet())
            assertNull(workspace.character(character.rootId))
            assertNotNull(workspace.interactiveFiction(game.id))
            assertTrue(workspace.interactiveFictions().isEmpty())
            assertEquals("chat", workspace.conversationDrafts("chat")!!.conversationId)
        } finally {
            database.close()
        }
    }

    @Test
    fun `relationship pickers include saved works while empty placeholders remain hidden`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val drafts = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val privateWorld = drafts.subjects.single { it.kind == NovexContentKind.WORLD }
            val privateModule = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(privateWorld.id), ContentModuleType.CUSTOM, "私有章节")).requireModule()
            val shared = workspace.apply(NovexCommand.CreateWorld("共享世界")).requireWorld()
            val sharedModule = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(shared.id), ContentModuleType.CUSTOM, "公开章节")).requireModule()
            assertTrue(workspace.world(shared.id)!!.availableVersions.isEmpty())
            val hiddenIds = drafts.subjects.filterNot { it == privateWorld }.map { it.id }.toSet()
            val options = workspace.module(sharedModule.id)!!.referenceOptions
            assertTrue(options.none { it.target.id in hiddenIds })
            assertTrue(options.any { it.target.id == privateWorld.id })
            assertTrue(options.any { it.target.id == privateModule.id })
        } finally {
            database.close()
        }
    }

    @Test
    fun `new conversation privately owns three durable cards without polluting the shared library`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val path = File(files.root, "drafts.db").absolutePath
        fun openDatabase() = Room.databaseBuilder(context, AppDatabase::class.java, path)
            .allowMainThreadQueries().build()
        var database = openDatabase()
        val media = File(files.root, "media")
        try {
            var workspace = NovexWorkspaceFactory.create(database, media)
            val first = workspace.apply(NovexCommand.EnsureConversationDrafts("chat-1")).requireConversationDrafts()
            assertEquals(setOf(NovexContentKind.WORLD, NovexContentKind.CHARACTER_VERSION,
                NovexContentKind.INTERACTIVE_FICTION), first.subjects.map { it.kind }.toSet())
            assertEquals(3, first.subjects.size)
            assertTrue(workspace.worlds().isEmpty())
            assertTrue(workspace.characters().isEmpty())
            assertTrue(workspace.interactiveFictions().isEmpty())
            database.close()
            database = openDatabase()
            workspace = NovexWorkspaceFactory.create(database, media)
            val reopened = workspace.apply(NovexCommand.EnsureConversationDrafts("chat-1")).requireConversationDrafts()
            assertEquals(first, reopened)
            assertTrue(workspace.worlds().isEmpty())
            assertTrue(workspace.characters().isEmpty())
            assertTrue(workspace.interactiveFictions().isEmpty())
        } finally {
            database.close()
        }
    }
}
