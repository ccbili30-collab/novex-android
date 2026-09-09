package com.openminis.app.novex.domain

import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.adapter.*
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
class NovexSettingUsePersistenceTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun `reopen preserves child switches and independent paths without changing identity or originals`() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "use.db").absolutePath).allowMainThreadQueries().build()
        var db = open()
        try {
            var workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("父世界", "父世界正文")).requireWorld()
            val child = workspace.apply(NovexCommand.CreateWorld("地理配套", "地理配套正文")).requireWorld()
            val parent = NovexContentAddress.world(world.id)
            val dependency = NovexContentAddress.world(child.id)
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM,
                "地理", """{"text":"地理模块独有文本"}""", id = "geography"))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("child", parent, NovexReferenceTarget(dependency),
                NovexReferencePurpose.BACKGROUND, sourceModuleId = "geography")))
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("cycle", dependency, NovexReferenceTarget(parent), NovexReferencePurpose.BACKGROUND)))
            val identity = AnswerIdentity.PersonaPreset("host", "主持人", "主持职责")
            var state = NovexConversationContextAdoption(workspace).adopt(NovexConversationConfigurationSnapshot("chat", answerIdentity = identity,
                backgroundSettings = listOf(BackgroundSetting(parent)), managedSubjects = listOf(ManagedSubject(dependency, ManagedAccess.EDIT))))
            fun toggle(target: NovexReferenceTarget, enabled: Boolean) {
                state = NovexConversationConfiguration.open(state).apply(NovexConversationCommand.SetSettingEnabled(target, enabled)).snapshot
            }
            toggle(NovexReferenceTarget(parent, "geography"), false)
            toggle(NovexReferenceTarget(parent), false)
            val row = ChatRepository(db.chatDao()).createSession("test-model", novexConfigurationJson = NovexConversationConfigurationCodec.encode(state))
            db.close(); db = open(); workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            state = NovexConversationConfigurationCodec.decode(db.chatDao().getSession(row.id)!!.novexConfigurationJson, row.id)
            toggle(NovexReferenceTarget(parent), true)
            val reads = NovexContextReadService(workspace)
            val text = WorkspaceNovexContextLoader(workspace).load(state).filter { it.kind != ContextSourceKind.TOOL_DEFINITION }.joinToString { it.content }
            assertTrue(text.contains("父世界正文"))
            assertFalse(text.contains("地理模块独有文本"))
            assertFalse(text.contains("地理配套正文"))
            assertTrue(runCatching { reads.read(state, "geography") }.isFailure)
            assertEquals(0, reads.search(state, "地理模块独有文本").getInt("total_sources_matched"))
            assertEquals(identity, state.answerIdentity)
            assertEquals(ManagedAccess.EDIT, state.managedSubjects.single().access)
            assertNotNull(workspace.module("geography"))
            state = NovexConversationContextAdoption(workspace).adopt(NovexConversationConfiguration.open(state)
                .apply(NovexConversationCommand.AddBackground(dependency)).snapshot)
            assertTrue(WorkspaceNovexContextLoader(workspace).load(state).any { it.content == "地理配套正文" })
            toggle(NovexReferenceTarget(dependency), false)
            assertFalse(WorkspaceNovexContextLoader(workspace).load(state).any { it.content == "地理配套正文" })
            assertFalse(dependency in NovexConversationSubjectProjection.used(state))
            toggle(NovexReferenceTarget(parent, "geography"), true)
            // Enabling a parent module cannot silently undo the user's separate child-card switch.
            assertFalse(WorkspaceNovexContextLoader(workspace).load(state).any { it.content == "地理配套正文" })
            assertTrue(NovexReferenceTarget(dependency) in state.disabledSettings)
        } finally { db.close() }
    }

    @Test fun `background off filters retained images while identity remains an independent purpose`() {
        val world = NovexContentAddress.world("world")
        val actor = NovexContentAddress.characterVersion("actor")
        fun source(address: NovexContentAddress, acting: Boolean = false) = NovexFrozenContext(NovexReferenceTarget(address),
            listOf(NovexContextCandidate("module", "模块", "正文", if (acting) ContextSourceKind.ANSWER_IDENTITY else ContextSourceKind.BACKGROUND_MODULE)),
            actorVersionId = if (acting) address.id else null, media = listOf(NovexSnapshotMedia(
                NovexRetainedMedia("image", "retained.png", "image/png", "hash"), com.openminis.app.data.character.MediaAssetSlot.MODULE_IMAGE,
                moduleId = "module", owner = address)), mediaCaptured = true)
        val state = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion(actor.id),
            backgroundSettings = listOf(BackgroundSetting(world), BackgroundSetting(actor)),
            adoptedContexts = listOf(NovexAdoptedContext(world, false, listOf(source(world)), emptyList()),
                NovexAdoptedContext(actor, true, listOf(source(actor, true)), emptyList())),
            disabledSettings = setOf(NovexReferenceTarget(world), NovexReferenceTarget(actor)))
        assertEquals(listOf(actor), NovexSnapshotMediaProjection.visible(state).map { it.owner })
        assertTrue(NovexEffectiveFrozenContext.sources(state).any { it.actorVersionId == actor.id })
        val legacy = state.copy(disabledSettings = emptySet(), adoptedContexts = listOf(NovexAdoptedContext(world, false,
            listOf(source(world), source(actor)))))
        assertTrue(runCatching { NovexConversationConfiguration.open(legacy).apply(NovexConversationCommand.SetSettingEnabled(NovexReferenceTarget(world, "module"), false)) }.isFailure)
        assertNotNull(NovexConversationConfiguration.open(legacy).apply(NovexConversationCommand.SetSettingEnabled(NovexReferenceTarget(world), false)))
    }
}
