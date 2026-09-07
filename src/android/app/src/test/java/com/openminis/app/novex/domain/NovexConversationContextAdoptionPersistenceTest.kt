package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexConversationContextAdoption
import com.openminis.app.novex.adapter.NovexWorkspaceFactory
import com.openminis.app.novex.adapter.WorkspaceNovexContextLoader
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
class NovexConversationContextAdoptionPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `direct adoption and game adoption keep different revisions readable after closing and reopening`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "parallel-adoption.db").absolutePath).allowMainThreadQueries().build()
        var database = open()
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("双重来源", "旧规则：十取一")).requireWorld()
            val address = NovexContentAddress.world(world.id)
            val before = NovexConversationContextAdoption(workspace).adopt(NovexConversationConfigurationSnapshot("chat",
                backgroundSettings = listOf(BackgroundSetting(address))))
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "新规则：二十取一")))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "新局" )).requireInteractiveFiction()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("rules", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(address), NovexReferencePurpose.RULES)))
            val frozen = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id, before.backgroundSettings, before.adoptedContexts)
            val started = NovexConversationConfiguration.open(before).apply(NovexConversationCommand.ActivateInteractiveFiction(frozen)).snapshot
            val row = com.openminis.app.data.repository.ChatRepository(database.chatDao()).createSession("test-model",
                novexConfigurationJson = NovexConversationConfigurationCodec.encode(started))
            database.close(); database = open()
            workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val restored = NovexConversationConfigurationCodec.decode(database.chatDao().getSession(row.id)!!.novexConfigurationJson, row.id)
            val candidates = WorkspaceNovexContextLoader(workspace).load(restored).filter { it.content.contains("规则：") }
            assertEquals(setOf("旧规则：十取一", "新规则：二十取一"), candidates.map { it.content }.toSet())
            assertEquals(2, candidates.map { it.sourceId }.distinct().size)
            assertTrue(candidates.all { it.label.contains("并列修订") })
            val reads = com.openminis.app.novex.adapter.NovexContextReadService(workspace)
            candidates.forEach { candidate ->
                assertEquals(candidate.content, reads.read(restored, candidate.sourceId).getString("text"))
                assertEquals(candidate.content, reads.read(restored, "world:${world.id}:overview",
                    revision = NovexFrozenContextCodec.digest(candidate.content)).getString("text"))
            }
            assertTrue(runCatching { reads.read(restored, "world:${world.id}:overview") }.isFailure)
            assertEquals(2, NovexAdoptedSourceUsageProjection.read(restored).filter { it.source.target.subject == address }.size)
            val removed = NovexConversationConfiguration.open(restored).apply(NovexConversationCommand.RemoveBackground(address)).snapshot
            val remaining = WorkspaceNovexContextLoader(workspace).load(removed).map { it.content }
            assertFalse("旧规则：十取一" in remaining)
            assertTrue("新规则：二十取一" in remaining)
            assertEquals(restored.effectivePlaythroughId, removed.effectivePlaythroughId)
        } finally { database.close() }
    }

    @Test
    fun `legacy-compatible role snapshot keeps companion identity out of public knowledge and scopes instructions`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val world = workspace.apply(NovexCommand.CreateWorld("生生之学")).requireWorld()
            workspace.apply(NovexCommand.LinkCharacterVersion(world.id, role.original.id, 0))
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(role.original.id), ContentModuleType.ROLE_INSTRUCTIONS,
                "专属指令", """{"text":"仅扮演时启用的指令"}""", id = "role-instructions"))
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(role.original.id), ContentModuleType.ROLE_PLAYER_IDENTITY,
                "专属玩家", """{"text":"未采用的配套玩家身份"}""", id = "companion"))
            val snapshot = com.openminis.app.data.character.CharacterConversationSnapshotFactory(
                com.openminis.app.data.character.CharacterCatalogRepository(database.characterCatalogDao()),
                com.openminis.app.data.character.ContentModuleRepository(database.contentModuleDao()),
                com.openminis.app.data.character.MediaAssetRepository(database.mediaAssetDao()) { false },
            ).create(world.id, role.original.id, null)
            val card = requireNotNull(snapshot.profile.character)
            assertFalse(card.knowledge.contains("未采用的配套玩家身份"))
            assertFalse(card.knowledge.contains("仅扮演时启用的指令"))
            assertTrue(card.systemPrompt.contains("仅扮演时启用的指令"))
            workspace.apply(NovexCommand.DeleteCharacter(role.character.id))
            val background = WorkspaceNovexContextLoader(workspace,
                com.openminis.app.novex.adapter.NovexLegacyContext(role.original.id, card, snapshot.profile.world))
                .load(NovexConversationConfigurationSnapshot("chat", backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.characterVersion(role.original.id)))))
                .joinToString("\n") { it.content }
            assertFalse(background.contains("配套玩家"))
            assertFalse(background.contains("仅扮演时启用"))
        } finally { database.close() }
    }

    @Test
    fun `ending a game restores the pregame role text even after explicitly refreshing that role during play`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(role.original.id), ContentModuleType.ROLE_INSTRUCTIONS,
                "角色要求", """{"text":"开局前已采用的角色要求"}""", id = "role-instructions"))
            val adoption = NovexConversationContextAdoption(workspace)
            val original = adoption.adopt(NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion(role.original.id)))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            val started = NovexConversationConfiguration.open(original).apply(NovexConversationCommand.ActivateInteractiveFiction(
                com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id)))
                .apply(NovexConversationCommand.SetAnswerIdentity(original.answerIdentity)).snapshot
            workspace.apply(NovexCommand.SaveModule("role-instructions", "角色要求", """{"text":"本局中明确刷新的角色要求"}"""))
            val refreshed = adoption.refresh(started, NovexContentAddress.characterVersion(role.original.id), true)
            assertTrue(WorkspaceNovexContextLoader(workspace).load(refreshed).any { it.content.contains("本局中明确刷新") })
            val ended = NovexConversationConfiguration.open(NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(refreshed), "chat"))
                .apply(NovexConversationCommand.DeactivateInteractiveFiction).snapshot
            val text = WorkspaceNovexContextLoader(workspace).load(ended).joinToString("\n") { it.content }
            assertTrue(text.contains("开局前已采用"))
            assertFalse(text.contains("本局中明确刷新"))
        } finally { database.close() }
    }

    @Test
    fun `explicit refresh changes adopted source while preserving playthrough state identity and controls`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("生生之学", "旧的会话背景")).requireWorld()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生", summary = "旧的文游正文", playerIdentity = "记言人")).requireInteractiveFiction()
            val backgrounds = listOf(BackgroundSetting(NovexContentAddress.world(world.id)))
            val gameSnapshot = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id, backgrounds)
            val initial = NovexConversationConfiguration.open(NovexConversationConfigurationSnapshot("chat", backgroundSettings = backgrounds))
                .apply(NovexConversationCommand.ActivateInteractiveFiction(gameSnapshot))
                .apply(NovexConversationCommand.SetPlaythroughValue("branch", "hp", PlaythroughValue.Number(8.0)))
                .snapshot
            val adopter = NovexConversationContextAdoption(workspace)
            val adopted = adopter.adopt(initial)
            workspace.apply(NovexCommand.SaveWorld(world.copy(overview = "明确刷新的会话背景")))
            val sourceRefreshed = adopter.refresh(adopted, NovexContentAddress.world(world.id), false)
            var text = WorkspaceNovexContextLoader(workspace).load(sourceRefreshed).joinToString("\n") { it.content }
            assertTrue(text.contains("明确刷新的会话背景"))
            assertFalse(text.contains("旧的会话背景"))
            workspace.apply(NovexCommand.SaveInteractiveFictionPage(game.id, "生生新版", summary = "明确刷新的文游正文", playerIdentity = "新版另一个身份"))
            val refreshed = adopter.refreshGame(sourceRefreshed)
            text = WorkspaceNovexContextLoader(workspace).load(refreshed).joinToString("\n") { it.content }
            assertTrue(text.contains("明确刷新的文游正文"))
            assertFalse(text.contains("旧的文游正文"))
            assertEquals(adopted.effectivePlaythroughId, refreshed.effectivePlaythroughId)
            assertEquals(adopted.playthroughStates, refreshed.playthroughStates)
            assertEquals(adopted.controls, refreshed.controls)
            assertEquals(adopted.answerIdentity, refreshed.answerIdentity)
            assertEquals(adopted.playerIdentity, refreshed.playerIdentity)
            assertTrue(refreshed.completedPlaythroughs.isEmpty())
            assertFalse(text.contains("新版另一个身份"))
        } finally { database.close() }
    }

    @Test
    fun `removing conversation background drops only that use while a game reference remains frozen`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val exclusive = workspace.apply(NovexCommand.CreateWorld("仅对话", "应当停止使用的对话背景")).requireWorld()
            val shared = workspace.apply(NovexCommand.CreateWorld("双重用途", "文游仍需使用的初始规则")).requireWorld()
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("shared", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(NovexContentAddress.world(shared.id)), NovexReferencePurpose.RULES)))
            val backgrounds = listOf(exclusive, shared).map { BackgroundSetting(NovexContentAddress.world(it.id)) }
            val captured = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id, backgrounds)
            var configuration = NovexConversationConfiguration.open(NovexConversationConfigurationSnapshot("chat", backgroundSettings = backgrounds))
                .apply(NovexConversationCommand.ActivateInteractiveFiction(captured)).snapshot
            configuration = NovexConversationContextAdoption(workspace).adopt(configuration)
            configuration = NovexConversationConfiguration.open(configuration)
                .apply(NovexConversationCommand.RemoveBackground(NovexContentAddress.world(exclusive.id)))
                .apply(NovexConversationCommand.RemoveBackground(NovexContentAddress.world(shared.id))).snapshot
            workspace.apply(NovexCommand.SaveWorld(shared.copy(overview = "未采用的新规则")))
            val text = WorkspaceNovexContextLoader(workspace).load(configuration).joinToString("\n") { it.content }
            assertFalse(text.contains("应当停止使用"))
            assertTrue(text.contains("文游仍需使用的初始规则"))
            assertFalse(text.contains("未采用的新规则"))
            assertNotNull(configuration.activeInteractiveFiction)
        } finally { database.close() }
    }

    @Test
    fun `ordinary role and background keep adopted text after original edits and configuration reload`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "adoption.db").absolutePath).allowMainThreadQueries().build()
        var database = open()
        try {
            var workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生")).requireCharacter()
            val world = workspace.apply(NovexCommand.CreateWorld("生生之学", "初次采用的世界正文")).requireWorld()
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(role.original.id), ContentModuleType.ROLE_INSTRUCTIONS,
                "扮演要求", """{"text":"初次采用的专属扮演要求"}""", id = "instructions"))
            val configuration = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion(role.original.id),
                backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.world(world.id))))
            val adopted = NovexConversationContextAdoption(workspace).adopt(configuration)
            val legacyBlock = "<当前角色卡>原格式的旧扮演要求</当前角色卡>"
            val projected = NovexLegacyPromptProjection.project("对话自己的要求\n$legacyBlock", adopted, role.original.id, null, world.id,
                legacyGeneratedPrompt = legacyBlock)
            assertFalse(projected.contains("原格式的旧扮演要求"))
            assertTrue(projected.contains("对话自己的要求"))
            val customized = "<当前角色卡>用户为此对话修改的要求</当前角色卡>"
            assertTrue(NovexLegacyPromptProjection.project(customized, adopted, role.original.id, null, world.id,
                legacyGeneratedPrompt = legacyBlock).contains("用户为此对话修改"))
            val serialized = NovexConversationConfigurationCodec.encode(adopted)
            val repository = com.openminis.app.data.repository.ChatRepository(database.chatDao())
            val session = repository.createSession("model", novexConfigurationJson = serialized)
            workspace.apply(NovexCommand.SaveModule("instructions", "新要求", """{"text":"后来改写的扮演要求"}"""))
            workspace.apply(NovexCommand.SaveWorldPage(worldId = world.id, name = "新版世界", overview = "后来改写的世界正文"))
            database.close()
            database = open()
            workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val saved = com.openminis.app.data.repository.ChatRepository(database.chatDao()).getSession(session.id)!!.novexConfigurationJson
            val reopened = NovexConversationConfigurationCodec.decode(saved, "chat")
            val loader = WorkspaceNovexContextLoader(workspace)
            val text = loader.load(reopened).joinToString("\n") { it.content }
            assertTrue(text.contains("初次采用的世界正文"))
            assertTrue(text.contains("初次采用的专属扮演要求"))
            assertFalse(text.contains("后来改写"))
            val removed = NovexConversationConfiguration.open(reopened).apply(NovexConversationCommand.RemoveBackground(NovexContentAddress.world(world.id)))
                .apply(NovexConversationCommand.SetAnswerIdentity(AnswerIdentity.Nova)).snapshot
            assertFalse(loader.load(removed).joinToString("\n") { it.content }.contains("初次采用"))
            assertEquals(serialized, NovexConversationConfigurationCodec.encode(NovexConversationContextAdoption(workspace).adopt(reopened)))
        } finally { database.close() }
    }
}
