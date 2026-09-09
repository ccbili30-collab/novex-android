package com.openminis.app.novex.domain

import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.novex.adapter.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexGameWorldbooksTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun reusedWorldbookDefaultsAndPerRunChoicesStayIndependentAfterReopen() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "books.db").absolutePath).allowMainThreadQueries().build()
        var db = open()
        try {
            var workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("小镇规则", "钟楼夜晚停摆")).requireWorld()
            val owner = NovexContentAddress.world(world.id)
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "怪谈", """{"text":"白手套只能由收信人取下"}""", id = "ghost"))
            val first = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "送信", "送信冒险")).requireInteractiveFiction()
            val second = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "探访", "探访冒险")).requireInteractiveFiction()
            var service = NovexGameWorldbooks(workspace) { block -> db.withTransaction { block() } }
            val link = NovexCardReference("first-book", NovexContentAddress.interactiveFiction(first.id), NovexReferenceTarget(owner), NovexReferencePurpose.RULES, targetLabel = "小镇规则")
            service.save(first.id, emptyList(), listOf(link.copy(enabled = false)))
            service.save(second.id, emptyList(), listOf(link.copy(id = "second-book", source = NovexContentAddress.interactiveFiction(second.id))))
            val actions = NovexConversationActions(workspace)
            val run = actions.startGame(NovexConversationConfigurationSnapshot("chat-one"), JSONObject().put("project_id", first.id))
            val other = actions.startGame(NovexConversationConfigurationSnapshot("chat-two"), JSONObject().put("project_id", second.id))
            assertFalse(WorkspaceNovexContextLoader(workspace).load(run).any { it.content.contains("钟楼夜晚停摆") })
            assertTrue(WorkspaceNovexContextLoader(workspace).load(other).any { it.content.contains("钟楼夜晚停摆") })
            assertEquals(0, NovexContextReadService(workspace).search(run, "白手套").getInt("total_sources_matched"))
            assertTrue(runCatching { NovexContextReadService(workspace).read(run, "ghost") }.isFailure)
            val enabled = NovexConversationConfiguration.open(run).apply(NovexConversationCommand.SetReferenceEnabled(link.id, true)).snapshot
            assertEquals(run.answerIdentity, enabled.answerIdentity)
            assertEquals(run.managedSubjects, enabled.managedSubjects)
            assertEquals(run.effectivePlaythroughId, enabled.effectivePlaythroughId)
            assertTrue(WorkspaceNovexContextLoader(workspace).load(enabled).any { it.content.contains("钟楼夜晚停摆") })
            assertFalse(service.load(first.id).single().enabled)
            assertTrue(service.load(second.id).single().enabled)
            workspace.apply(NovexCommand.SaveModule("ghost", "怪谈", """{"text":"新修订不应偷偷进入旧局"}"""))
            val raw = NovexConversationConfigurationCodec.encode(enabled)
            db.close(); db = open(); workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            service = NovexGameWorldbooks(workspace) { block -> db.withTransaction { block() } }
            val reopened = NovexConversationConfigurationCodec.decode(raw, "chat-one")
            val text = WorkspaceNovexContextLoader(workspace).load(reopened).joinToString { it.content }
            assertTrue(text.contains("白手套只能由收信人取下"))
            assertFalse(text.contains("新修订不应偷偷进入旧局"))
            assertFalse(service.load(first.id).single().enabled)
            assertTrue(runCatching { service.save(first.id, emptyList(), listOf(link)) }.isFailure)
            assertFalse(service.load(first.id).single().enabled)
        } finally { db.close() }
    }

    @Test fun agentOperationsUseSavedChoiceRevisionsAndExistingManagementPermission() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "agent-media"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "夜邮", "送信冒险")).requireInteractiveFiction()
            val world = workspace.apply(NovexCommand.CreateWorld("夜钟", "夜里响七声")).requireWorld()
            val defaults = NovexGameWorldbooks(workspace) { block -> db.withTransaction { block() } }
            val actions = NovexWorldbookActions(workspace, defaults)
            val configuration = NovexConversationConfigurationSnapshot("chat")
            val inspect = actions.execute(configuration, "inspect_worldbook_choices", JSONObject().put("project_id", game.id)).payload
            val selection = JSONObject().put("project_id", game.id).put("expected_revision", inspect.getString("revision"))
                .put("worldbooks", org.json.JSONArray().put(JSONObject().put("world_id", world.id).put("enabled", true)))
            assertTrue(runCatching { actions.execute(configuration, "set_game_worldbooks", selection) }.isFailure)
            assertTrue(defaults.load(game.id).isEmpty())
            val managed = configuration.copy(managedSubjects = listOf(ManagedSubject(NovexContentAddress.interactiveFiction(game.id), ManagedAccess.EDIT)))
            val result = actions.execute(managed, "set_game_worldbooks", selection)
            assertTrue(result.payload.getBoolean("saved")); assertEquals(managed, result.configuration)
            assertEquals(world.id, defaults.load(game.id).single().target.subject.id)
            assertTrue(runCatching { actions.execute(managed, "set_game_worldbooks", selection) }.isFailure)
            val active = NovexConversationActions(workspace).startGame(managed, JSONObject().put("project_id", game.id))
            val current = actions.execute(active, "inspect_worldbook_choices", JSONObject()).payload
            val off = actions.execute(active, "set_current_worldbooks", JSONObject().put("expected_revision", current.getString("revision"))
                .put("changes", org.json.JSONArray().put(JSONObject().put("reference_id", current.getJSONArray("choices").getJSONObject(0).getString("reference_id")).put("enabled", false))))
            assertFalse(NovexWorldbookUse.references(off.configuration).single().enabled)
            assertTrue(defaults.load(game.id).single().enabled)
            assertEquals(active.effectivePlaythroughId, off.configuration.effectivePlaythroughId)
            assertEquals(active.managedSubjects, off.configuration.managedSubjects)
        } finally { db.close() }
    }

    @Test fun codecDefaultsOldLinksToEnabledAndRejectsInvalidFlags() {
        val reference = NovexCardReference("r", NovexContentAddress.interactiveFiction("g"), NovexReferenceTarget(NovexContentAddress.world("w")), NovexReferencePurpose.BACKGROUND)
        val old = JSONObject(NovexCardReferenceCodec.encode(reference)).apply { remove("enabled") }
        assertTrue(NovexCardReferenceCodec.decode(old.toString()).enabled)
        assertFalse(NovexCardReferenceCodec.decode(NovexCardReferenceCodec.encode(reference.copy(enabled = false))).enabled)
        assertTrue(runCatching { NovexCardReferenceCodec.decode(old.put("enabled", "false").toString()) }.isFailure)
    }

    @Test fun disablingOnePathDoesNotDisableAnotherOrResetItsModulePreferences() {
        val first = NovexContentAddress.world("a"); val second = NovexContentAddress.world("b"); val child = NovexContentAddress.world("child")
        val source = NovexFrozenContext(NovexReferenceTarget(child), listOf(NovexContextCandidate("m", "模块", "正文")))
        fun link(id: String, from: NovexContentAddress) = NovexCardReference(id, from, source.target, NovexReferencePurpose.BACKGROUND)
        val configuration = NovexConversationConfigurationSnapshot("chat", backgroundSettings = listOf(BackgroundSetting(first), BackgroundSetting(second)),
            adoptedContexts = listOf(NovexAdoptedContext(first, false, listOf(source), listOf(link("a-c", first))), NovexAdoptedContext(second, false, listOf(source), listOf(link("b-c", second)))))
        val off = NovexWorldbookUse.setReference(configuration, "a-c", false)
        assertTrue(NovexEffectiveFrozenContext.sources(off).any { it.target == source.target })
        val bothOff = NovexWorldbookUse.setReference(off, "b-c", false)
        assertTrue(NovexEffectiveFrozenContext.sources(bothOff).isEmpty())
        val globalOn = NovexConversationConfiguration.open(bothOff).apply(NovexConversationCommand.SetSettingEnabled(source.target, true)).snapshot
        assertTrue(NovexEffectiveFrozenContext.sources(globalOn).isEmpty())
    }
}
