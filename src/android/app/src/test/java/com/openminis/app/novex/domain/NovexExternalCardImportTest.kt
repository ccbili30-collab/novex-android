package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.openminis.app.data.character.*
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.NovexConversationContextAdoption
import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory
import com.openminis.app.novex.adapter.WorkspaceNovexContextLoader
import java.io.File
import java.nio.charset.Charset
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexExternalCardImportTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun unknownObjectArrayBrokenJsonAndOtherTextRemainVerbatim() {
        val samples = listOf("{\"name\":\"不是标准字段\",\"kind\":\"image\",\"rules\":[\"简洁叙述\"]}",
            "{\"name\":\"别的平台\",\"description\":\"人物\",\"world_book\":[\"设定\"],\"pre_prompt\":\"文风\"}",
            "[\"第三人称\", {\"未知字段\":true}]", "{不是合法结构化数据，但设定仍能读", "写作风格:\n  视角: 第三人称\n  避免: 替玩家决定")
        for (text in samples) for (kind in NovexCardKind.entries) {
            val imported = NovexExternalCardImport.decode(kind, text.toByteArray(), "叙事设定.txt")
            val modules = when (val document = imported.document) {
                is NovexWorldImportDocument -> document.modules
                is NovexCharacterImportDocument -> document.versions.single().modules
                is NovexInteractiveFictionImportDocument -> document.modules
            }
            assertEquals(text, (modules.single().document as ContentModuleDocument.Article).text)
            assertEquals("叙事设定", imported.displayName)
            assertArrayEquals(text.toByteArray(), NovexExternalCardImport.original(JSONObject(imported.document.originalJson))!!.second)
        }
    }

    @Test fun chineseEncodingsAreDecodedWithoutChangingTheStoredSource() {
        val text = "文风：简洁，留白，不代替玩家决定。"
        val inputs = listOf(text.toByteArray(Charset.forName("GB18030")), byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE))
        inputs.forEach { bytes ->
            val imported = NovexExternalCardImport.decode(NovexCardKind.WORLD, bytes, "文风.yaml")
            assertEquals(text, ((imported.document as NovexWorldImportDocument).modules.single().document as ContentModuleDocument.Article).text)
            assertArrayEquals(bytes, NovexExternalCardImport.original(JSONObject(imported.document.originalJson))!!.second)
        }
    }

    @Test fun corruptNativeAndUnreadableBinaryCannotBecomeSuccessfulEmptyCards() {
        assertTrue(runCatching { NovexExternalCardImport.decode(NovexCardKind.WORLD, "broken".toByteArray(), "坏卡.novexworld") }.isFailure)
        assertTrue(runCatching { NovexExternalCardImport.decode(NovexCardKind.WORLD, byteArrayOf(0, 1, 2, 3), "archive.bin") }.isFailure)
        assertTrue(runCatching { NovexExternalCardImport.decode(NovexCardKind.GAME, "broken".toByteArray(), "game.novexgame") }.isFailure)
    }

    @Test fun rawWorldIsUsableAfterRestartAndSourceSurvivesEditAndExport() = runBlocking {
        fun open() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java,
            File(files.root, "external.db").absolutePath).allowMainThreadQueries().build()
        var db = open()
        try {
            var workspace = NovexTestWorkspaceFactory.create(db, File(files.root, "media"))
            val text = "文风：克制。\n" + "未标准化设定，保持原话。\n".repeat(400)
            val input = text.toByteArray()
            val imported = NovexExternalCardImport.decode(NovexCardKind.WORLD, input, "文风.txt")
            val id = workspace.apply(NovexCommand.ImportNativeCard(imported)).requireNativeImport().localId
            val address = NovexContentAddress.world(id)
            val directory = requireNotNull(workspace.cardDirectory(address))
            assertArrayEquals(input, File(directory, "source/original").readBytes())
            assertEquals(1, workspace.world(id)!!.modules.size)
            db.close(); db = open(); workspace = NovexTestWorkspaceFactory.create(db, File(files.root, "media"))
            val configured = NovexConversationContextAdoption(workspace).adopt(NovexConversationConfigurationSnapshot("conversation",
                backgroundSettings = listOf(BackgroundSetting(address))))
            val candidates = WorkspaceNovexContextLoader(workspace).load(configured)
            val composition = NovexContextComposer.compose("你好", 100_000, candidates)
            assertTrue("Raw settings must be sent without matching a special keyword", composition.fragments.any { it.text == text.trim() && !it.partial })
            val module = workspace.world(id)!!.modules.single()
            workspace.apply(NovexCommand.SaveModule(module.id, module.name, """{"kind":"article","text":"整理后的文风"}"""))
            val exported = workspace.apply(NovexCommand.ExportNativeWorld(id)).requireNativeCard()
            assertArrayEquals(input, NovexExternalCardImport.original(JSONObject(exported.documentJson))!!.second)
            val roundtrip = NovexExternalCardImport.decode(NovexCardKind.WORLD, NovexCardPackageCodec.encode(exported), "文风.novexworld")
            assertEquals("整理后的文风", ((roundtrip.document as NovexWorldImportDocument).modules.single().document as ContentModuleDocument.Article).text)
            assertTrue(WorkspaceNovexContextLoader(workspace).load(configured).any { it.content == text })
        } finally { db.close() }
    }

    @Test fun rawRoleWorksWhenSelectedWithoutLeakingItsInstructionsAsBackground() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexTestWorkspaceFactory.create(db, File(files.root, "media"))
            val text = "扮演邮差阿予，害怕鬼；说话简短。配套玩家为记言人。"
            val imported = NovexExternalCardImport.decode(NovexCardKind.CHARACTER, text.toByteArray(), "阿予.md")
            val id = workspace.apply(NovexCommand.ImportNativeCard(imported)).requireNativeImport().localId
            val role = workspace.character(id)!!.character.original
            val loader = WorkspaceNovexContextLoader(workspace)
            val adoption = NovexConversationContextAdoption(workspace)
            val acting = adoption.adopt(NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion(role.id)))
            assertTrue(NovexContextComposer.compose("你好", 10_000, loader.load(acting)).fragments.any { it.text == text })
            val background = adoption.adopt(NovexConversationConfigurationSnapshot("chat", backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.characterVersion(role.id)))))
            assertFalse(loader.load(background).any { it.content.contains("配套玩家") })
            assertNull(acting.playerIdentity)
        } finally { db.close() }
    }

    @Test fun optionalOrganizationEditsTheMountedOriginalAndDoesNotReAdoptBothCopies() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexTestWorkspaceFactory.create(db, File(files.root, "organized-media"))
            workspace.apply(NovexCommand.EnsureConversationDrafts("organize"))
            val source = "{\"文风\":\"简短，留白\",\"地点\":\"雾中邮局\"}"
            suspend fun importCard() = workspace.apply(NovexCommand.ImportNativeCard(
                NovexExternalCardImport.decode(NovexCardKind.WORLD, source.toByteArray(), "设定.json"))).requireNativeImport().localId
            val first = importCard()
            val sameName = importCard()
            assertNotEquals(first, sameName)
            val card = NovexContentAddress.world(first)
            val module = workspace.world(first)!!.modules.single()
            val originalOther = workspace.world(sameName)!!.modules.single().contentJson
            val adoption = NovexConversationContextAdoption(workspace)
            val oldPlay = adoption.adopt(NovexConversationConfigurationSnapshot("play",
                backgroundSettings = listOf(BackgroundSetting(card))))
            val config = NovexConversationConfiguration.open(NovexConversationConfigurationSnapshot("organize"))
                .apply(NovexConversationCommand.MountSubject(card, ManagedAccess.EDIT)).snapshot
            val transaction = NovexManagementTransaction { block -> db.withTransaction { block() } }
            val management = NovexManagementService(workspace,
                com.openminis.app.data.creative.CreativeArtifactRepository(db,
                    com.openminis.app.data.creative.CreativeArtifactFileStore(File(files.root, "organized-artifacts"))), transaction)
            val service = NovexCardFileService(workspace, management,
                NovexCardFileOperations(NovexCardSourceModules(NovexDocumentSnapshotStore { null }) { false }), transaction)
            val update = JSONObject().put("module_id", module.id).put("name", "叙事风格").put("text", "简短，留白")
            service.execute(config, "novex_write_module", update, listOf("整理这张卡"), "organize-existing")
            val add = JSONObject().put("kind", "world").put("card_id", first).put("name", "地点").put("text", "雾中邮局")
            service.execute(config, "novex_write_module", add, listOf("整理这张卡"), "organize-place")
            service.execute(config, "novex_write_module", add, listOf("整理这张卡"), "organize-place")
            assertEquals(2, workspace.world(first)!!.modules.size)
            assertEquals(module.id, workspace.world(first)!!.modules.first().id)
            assertEquals(originalOther, workspace.world(sameName)!!.modules.single().contentJson)
            assertTrue(workspace.world(first)!!.modules.none { JSONObject(it.contentJson).optString("text") == source })
            assertArrayEquals(source.toByteArray(), File(workspace.cardDirectory(card)!!, "source/original").readBytes())
            val refreshed = adoption.refresh(oldPlay, card, acting = false)
            val loader = WorkspaceNovexContextLoader(workspace)
            assertFalse(loader.load(refreshed).any { it.content.contains(source) })
            assertTrue(loader.load(oldPlay).any { it.content == source })
            assertEquals(2, workspace.worlds().size)
        } finally { db.close() }
    }
}
