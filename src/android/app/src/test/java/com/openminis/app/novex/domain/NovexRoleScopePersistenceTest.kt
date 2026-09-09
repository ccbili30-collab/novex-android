package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory
import com.openminis.app.novex.adapter.WorkspaceNovexContextLoader
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
class NovexRoleScopePersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `game duty persona is frozen on start and stays out of referenced background`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "共创开局")).requireInteractiveFiction()
            workspace.apply(NovexCommand.AddModule(ModuleOwner.interactiveFiction(game.id), ContentModuleType.GAME_ANSWER_IDENTITY,
                "历史推演主持人", """{"text":"以史料主持推演，先与玩家共创制度"}""", id = "host"))
            val prepared = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id)
            val persona = prepared.answerIdentity as? AnswerIdentity.PersonaPreset
            assertEquals("历史推演主持人", persona?.label)
            assertEquals("以史料主持推演，先与玩家共创制度", persona?.instructions)
            workspace.apply(NovexCommand.SaveModule("host", "新版主持人", """{"text":"后来的新指令"}"""))
            val active = NovexConversationConfiguration.open(NovexConversationConfigurationSnapshot("chat"))
                .apply(NovexConversationCommand.ActivateInteractiveFiction(prepared)).snapshot
            assertEquals(persona, active.answerIdentity)
            val content = WorkspaceNovexContextLoader(workspace).load(active.copy(answerIdentity = AnswerIdentity.Nova)).joinToString("\n") { it.content }
            assertFalse(content.contains("以史料主持推演"))
            assertFalse(content.contains("后来的新指令"))
            val borrower = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "借用规则")).requireInteractiveFiction()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("background", NovexContentAddress.interactiveFiction(borrower.id),
                NovexReferenceTarget(NovexContentAddress.interactiveFiction(game.id)), NovexReferencePurpose.RULES)))
            val borrowed = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(borrower.id)
            assertNull(borrowed.answerIdentity)
            assertFalse(borrowed.contentJson.contains("后来的新指令"))
        } finally { database.close() }
    }

    @Test
    fun `game with declared and companion players preserves separate choices and cannot start unresolved`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生", "{}")).requireCharacter()
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(role.original.id), ContentModuleType.ROLE_PLAYER_IDENTITY,
                "门下弟子", """{"text":"角色指定的记言人"}""", id = "companion"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生", playerIdentity = "文游指定的旅人")).requireInteractiveFiction()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("role", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(NovexContentAddress.characterVersion(role.original.id)), NovexReferencePurpose.ANSWER_IDENTITY)))
            val prepared = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(game.id)
            assertNull(prepared.playerIdentity)
            assertEquals(2, org.json.JSONObject(prepared.contentJson).getJSONArray("playerIdentityChoices").length())
            assertThrows(IllegalArgumentException::class.java) {
                NovexConversationConfiguration.open(NovexConversationConfigurationSnapshot("chat"))
                    .apply(NovexConversationCommand.ActivateInteractiveFiction(prepared))
            }
            val choices = NovexGamePlayerChoices.read(prepared)
            val selected = NovexGamePlayerChoices.select(prepared, choices.last().id)
            val current = ConversationPlayerIdentity("user", "自选身份", "用户原有身份")
            val original = NovexConversationConfigurationSnapshot("chat", playerIdentity = current,
                answerIdentity = NovexPersonaPresets.gameHost)
            val active = NovexConversationConfiguration.open(original).apply(
                NovexConversationCommand.ActivateInteractiveFiction(selected, replacePlayerIdentity = true)).snapshot
            val reopened = NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(active), "chat")
            assertEquals(choices.last(), reopened.playerIdentity)
            val content = WorkspaceNovexContextLoader(workspace).load(reopened).joinToString("\n") { it.content }
            assertTrue(content.contains("角色指定的记言人"))
            assertFalse(content.contains("文游指定的旅人"))
            val ended = NovexConversationConfiguration.open(reopened).apply(NovexConversationCommand.DeactivateInteractiveFiction).snapshot
            assertEquals(current, ended.playerIdentity)
            assertEquals(original.answerIdentity, ended.answerIdentity)
            assertEquals(current, NovexGamePlayerChoices.useCurrent(prepared, current).playerIdentity)
            Unit
        } finally { database.close() }
    }

    @Test
    fun `game adopts companion only from its answering role and freezes its contents`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生", "{}")).requireCharacter()
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(role.original.id), ContentModuleType.ROLE_PLAYER_IDENTITY,
                "门下弟子", """{"text":"门下弟子兼记言人"}""", id = "companion"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "生生")).requireInteractiveFiction()
            val reference = NovexCardReference("role", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(NovexContentAddress.characterVersion(role.original.id)), NovexReferencePurpose.BACKGROUND)
            workspace.apply(NovexCommand.PutCardReference(reference))
            val assembler = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace)
            assertNull(assembler.create(game.id).playerIdentity)
            workspace.apply(NovexCommand.PutCardReference(reference.copy(purpose = NovexReferencePurpose.ANSWER_IDENTITY)))
            val adopted = assembler.create(game.id)
            assertEquals("门下弟子兼记言人", adopted.playerIdentity?.description)
            workspace.apply(NovexCommand.SaveModule("companion", "门下弟子", """{"text":"修改后的身份"}"""))
            assertEquals("门下弟子兼记言人", adopted.playerIdentity?.description)
        } finally { database.close() }
    }

    @Test
    fun `explicit player reference adopts only its exact identity module without activating the referenced game`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val source = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "主文游")).requireInteractiveFiction()
            val other = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "借用身份的文游")).requireInteractiveFiction()
            workspace.apply(NovexCommand.AddModule(ModuleOwner.interactiveFiction(other.id), ContentModuleType.GAME_PLAYER_IDENTITY,
                "玩家", """{"text":"从另一卡片明确采用的旅人"}""", id = "player"))
            workspace.apply(NovexCommand.AddModule(ModuleOwner.interactiveFiction(other.id), ContentModuleType.GAME_OPENING,
                "开局", """{"text":"绝不启动的另一个开局"}""", id = "opening"))
            val reference = NovexCardReference("player-reference", NovexContentAddress.interactiveFiction(source.id),
                NovexReferenceTarget(NovexContentAddress.interactiveFiction(other.id), "player"), NovexReferencePurpose.PLAYER_IDENTITY)
            workspace.apply(NovexCommand.PutCardReference(reference))
            val adopted = com.openminis.app.novex.adapter.NovexGameSnapshotAssembler(workspace).create(source.id)
            assertEquals(source.id, adopted.projectId)
            assertEquals("从另一卡片明确采用的旅人", adopted.playerIdentity?.description)
            assertFalse(adopted.contentJson.contains("绝不启动的另一个开局"))
            assertNull(adopted.answerIdentity)
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                workspace.apply(NovexCommand.PutCardReference(reference.copy(target = reference.target.copy(moduleId = "opening"))))
            } }
            Unit
        } finally { database.close() }
    }

    @Test
    fun `management defaults to public role fields and requires an explicit private module or profile section`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"name":"伏生","summary":"公开生平","systemPrompt":"旧格式专属秘密","extensions":{"player":"扩展中的私有秘密"}}""")).requireCharacter()
            workspace.apply(NovexCommand.AddModule(ModuleOwner.characterVersion(role.original.id), ContentModuleType.ROLE_PLAYER_IDENTITY,
                "私有标题秘密", """{"text":"配套模块中的秘密"}""", id = "companion"))
            val source = NovexContentAddress.characterVersion(role.original.id)
            val configuration = NovexConversationConfigurationSnapshot("chat", managedSubjects = listOf(ManagedSubject(source, ManagedAccess.READ_ONLY)))
            val artifacts = object : NovexManagementArtifactPort {
                override suspend fun exists(artifactId: String) = false
                override suspend fun describe(artifactId: String): NovexManagedArtifactDescription? = null
                override suspend fun attach(attachment: CreativeArtifactAttachment) = error("本场景不操作文件")
                override suspend fun detach(attachment: CreativeArtifactAttachment) = error("本场景不操作文件")
            }
            val service = NovexManagementService(workspace, artifacts)
            val public = service.inspect(configuration, source, null).toToolJson().toString()
            assertTrue(public.contains("公开生平"))
            assertFalse(public.contains("秘密"))
            val companion = service.inspect(configuration, source, "companion").toToolJson().toString()
            assertTrue(companion.contains("配套模块中的秘密"))
            assertFalse(companion.contains("旧格式专属秘密"))
            val instructions = service.inspect(configuration, source, null, profileSection = "role_instructions").toToolJson().toString()
            assertTrue(instructions.contains("旧格式专属秘密"))
            assertFalse(instructions.contains("配套模块中的秘密"))
            assertFalse(instructions.contains("扩展中的私有秘密"))
        } finally { database.close() }
    }

    @Test
    fun `inspection previews search and direct reads share role privacy and do not accept a guessed companion identifier`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"name":"伏生","summary":"公开资料可读取"}""")).requireCharacter()
            val owner = ModuleOwner.characterVersion(role.original.id)
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.ROLE_INSTRUCTIONS, "扮演", """{"text":"扮演模块秘密"}""", id = "actor"))
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.ROLE_PLAYER_IDENTITY, "配套", """{"text":"配套玩家秘密"}""", id = "companion"))
            val configuration = NovexConversationConfigurationSnapshot("chat", backgroundSettings = listOf(
                BackgroundSetting(NovexContentAddress.characterVersion(role.original.id))))
            val reader = com.openminis.app.novex.adapter.NovexContextReadService(workspace)
            val directory = reader.inspect(configuration).toString()
            assertTrue(directory.contains("公开资料可读取"))
            assertFalse(directory.contains("秘密"))
            assertEquals(0, reader.search(configuration, "秘密").getJSONArray("matches").length())
            assertThrows(IllegalArgumentException::class.java) { runBlocking { reader.read(configuration, "companion") } }
            val acting = configuration.copy(answerIdentity = AnswerIdentity.CharacterVersion(role.original.id))
            assertTrue(reader.read(acting, "actor").getString("text").contains("扮演模块秘密"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { reader.read(acting, "companion") } }
            Unit
        } finally { database.close() }
    }

    @Test
    fun `background character excludes companion and actor modules while acting includes only its actor instructions`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val role = workspace.apply(NovexCommand.CreateCharacter("伏生", """{"name":"伏生","summary":"可公开的角色生平","systemPrompt":"被新模块取代的旧指令"}""")).requireCharacter()
            val owner = ModuleOwner.characterVersion(role.original.id)
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.ROLE_INSTRUCTIONS, "扮演指令", """{"text":"仅扮演时遵守的专属要求"}""", id = "actor"))
            workspace.apply(NovexCommand.AddModule(owner, ContentModuleType.ROLE_PLAYER_IDENTITY, "配套玩家", """{"text":"未采用时不能暴露的门下弟子身份"}""", id = "companion"))
            val configuration = NovexConversationConfigurationSnapshot("chat", backgroundSettings = listOf(
                BackgroundSetting(NovexContentAddress.characterVersion(role.original.id))))
            val loader = WorkspaceNovexContextLoader(workspace)
            val background = loader.load(configuration).joinToString("\n") { it.content }
            assertTrue(background.contains("可公开的角色生平"))
            assertFalse(background.contains("仅扮演时遵守的专属要求"))
            assertFalse(background.contains("未采用时不能暴露的门下弟子身份"))
            val actorCandidates = loader.load(configuration.copy(answerIdentity = AnswerIdentity.CharacterVersion(role.original.id)))
            val actor = actorCandidates.joinToString("\n") { it.content }
            assertTrue(actor.contains("仅扮演时遵守的专属要求"))
            assertFalse(actor.contains("被新模块取代的旧指令"))
            assertFalse(actor.contains("未采用时不能暴露的门下弟子身份"))
            assertTrue(NovexContextComposer.compose("你好", 2000, actorCandidates).fragments.any {
                it.text.contains("仅扮演时遵守的专属要求")
            })
        } finally { database.close() }
    }
}
