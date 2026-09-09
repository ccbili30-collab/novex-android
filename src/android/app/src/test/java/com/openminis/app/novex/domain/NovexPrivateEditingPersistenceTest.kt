package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.creative.CreativeArtifactFileStore
import com.openminis.app.data.creative.CreativeArtifactRepository
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory
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
class NovexPrivateEditingPersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `owned works remain editable after publication and references while readonly mounts stay protected`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(database, File(files.root, "media"))
            val drafts = workspace.apply(NovexCommand.EnsureConversationDrafts("chat")).requireConversationDrafts()
            val game = drafts.subjects.single { it.kind == NovexContentKind.INTERACTIVE_FICTION }
            val module = workspace.apply(NovexCommand.AddModule(ModuleOwner.interactiveFiction(game.id), ContentModuleType.CUSTOM,
                "帝议", """{"text":"初稿"}""")).requireModule()
            val config = NovexConversationConfigurationSnapshot("chat", managedSubjects = listOf(ManagedSubject(game, ManagedAccess.EDIT)))
            val service = NovexManagementService(workspace,
                CreativeArtifactRepository(database, CreativeArtifactFileStore(File(files.root, "artifacts"))),
                NovexManagementTransaction { block -> database.withTransaction { block() } })
            val changes = """[{"operation":"update_module","module_id":"${module.id}","content_json":{"text":"完整议事规则"}}]"""
            val request = "完善帝议模块，补齐议事规则"
            val plan = service.propose(config, changes, request, "private-edit")
            assertEquals(plan, service.pendingPlan(config, plan.id))
            service.apply(config, plan, request)
            assertTrue(workspace.module(module.id)!!.module.contentJson.contains("完整议事规则"))
            val stale = service.propose(config, changes, request, "stale-edit")
            workspace.apply(NovexCommand.SaveModule(module.id, module.name, """{"text":"用户后来手动修改"}"""))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { service.apply(config, stale, request) } }
            assertTrue(workspace.module(module.id)!!.module.contentJson.contains("用户后来手动修改"))
            workspace.apply(NovexCommand.ReleaseConversationDraftWrite("chat", stale.id))
            val world = workspace.apply(NovexCommand.CreateWorld("生生之学")).requireWorld()
            val referencePlan = service.propose(config,
                """[{"operation":"put_card_reference","subject_kind":"game","subject_id":"${game.id}","reference_id":"game-world","target_kind":"world","target_id":"${world.id}","purpose":"background"}]""",
                "关联生生之学作为背景", "private-reference")
            service.apply(config, referencePlan, "关联生生之学作为背景")
            assertEquals(NovexContentAddress.world(world.id), workspace.referencesFrom(game).single().target.subject)
            assertEquals(AnswerIdentity.Nova, config.answerIdentity)
            assertNull(config.activeInteractiveFiction)
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("shared-use", NovexContentAddress.world(world.id),
                NovexReferenceTarget(game), NovexReferencePurpose.RULES)))
            val usedElsewhere = service.propose(config, changes, request, "shared-use-edit")
            workspace.apply(NovexCommand.ReleaseConversationDraftWrite("chat", usedElsewhere.id))
            workspace.apply(NovexCommand.RemoveCardReference("shared-use", NovexContentAddress.world(world.id)))
            val readOnly = config.copy(managedSubjects = listOf(ManagedSubject(game, ManagedAccess.READ_ONLY)))
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                service.propose(readOnly, changes, request, "readonly-edit")
            } }
            val again = service.propose(config, changes, request, "later-edit")
            workspace.apply(NovexCommand.FinalizeConversationDrafts("chat"))
            service.apply(config, again, request)
            assertTrue(workspace.module(module.id)!!.module.contentJson.contains("完整议事规则"))
            val shared = service.propose(config, changes, request, "shared-edit")
        } finally { database.close() }
    }
}
