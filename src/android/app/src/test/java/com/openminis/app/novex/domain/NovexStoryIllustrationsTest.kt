package com.openminis.app.novex.domain

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.character.*
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.adapter.*
import com.openminis.app.ui.chat.*
import com.openminis.app.data.model.AgentContentPart
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class NovexStoryIllustrationsTest {
    @get:Rule val files = TemporaryFolder()
    private fun image(name: String, hash: String, keys: List<String> = listOf("雾")) = NovexSnapshotMedia(
        NovexRetainedMedia(name, "/retained/$name", "image/png", hash), MediaAssetSlot.MODULE_IMAGE,
        moduleId = name, owner = NovexContentAddress.world("world"), label = name,
        illustrationRule = NovexStoryIllustrations.encode(NovexStoryIllustrations.Rule(keys = keys)))

    @Test fun automaticSelectionIsStableAndBranchCooldownDoesNotPreventExplicitChoice() {
        val a = image("镇口", "a"); val b = image("钟楼", "b")
        assertEquals(a, NovexStoryIllustrations.choose(listOf(a, b), "雾中镇口", emptyList()))
        assertEquals(b, NovexStoryIllustrations.choose(listOf(a, b), "雾中镇口", listOf(setOf("a"))))
        assertEquals(a, NovexStoryIllustrations.choose(listOf(a, b), "雾中镇口", listOf(setOf("a"), emptySet())))
        assertEquals(a, NovexStoryIllustrations.choose(listOf(a, b), "再看一遍", listOf(setOf("a")), NovexStoryIllustrations.id(a)))
        assertNull(NovexStoryIllustrations.choose(listOf(a, b), "", emptyList()))
        assertNull(NovexStoryIllustrations.choose(listOf(a), "没有命中", emptyList()))
        assertNull(NovexStoryIllustrations.choose(listOf(a), "雾", emptyList(), "foreign"))
        assertNull(NovexStoryIllustrations.decode("""{"version":99,"keys":["雾"]}"""))
        assertNull(NovexStoryIllustrations.decode("""{"keys":["/雾/i"]}"""))
    }

    @Test fun actualCardsFreezeRulesAndPicturesAndDisabledBooksNeverSupplyImages() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexTestWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("雾镇", "背景")).requireWorld()
            val module = workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "镇口",
                NovexStoryIllustrations.set("""{"text":"镇口说明"}""", ContentModuleType.CUSTOM, "main", NovexStoryIllustrations.Rule(keys = listOf("雾"))))).requireModule()
            workspace.apply(NovexCommand.AttachImage(ModuleOwner.contentModule(module.id), MediaAssetSlot.MODULE_IMAGE, byteArrayOf(1,2,3), "image/png"))
            val game = workspace.apply(NovexCommand.SaveInteractiveFictionPage(null, "送信")).requireInteractiveFiction()
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference("book", NovexContentAddress.interactiveFiction(game.id),
                NovexReferenceTarget(NovexContentAddress.world(world.id)), NovexReferencePurpose.RULES)))
            val snapshot = NovexGameSnapshotAssembler(workspace, NovexSnapshotMediaStore(File(files.root, "adopted"))).create(game.id)
            val configuration = NovexConversationConfigurationSnapshot("chat", activeInteractiveFiction = snapshot)
            val available = NovexStoryIllustrations.available(configuration, emptyList())
            assertEquals(listOf("镇口"), available.map { it.label })
            assertEquals(available.single(), NovexStoryIllustrations.choose(available, "雾", emptyList()))
            val disabled = NovexWorldbookUse.setReference(configuration, "book", false)
            assertTrue(NovexStoryIllustrations.available(disabled, listOf("雾")).isEmpty())
            val exported = workspace.apply(NovexCommand.ExportNativeWorld(world.id)).requireNativeCard()
            val copy = workspace.apply(NovexCommand.ImportNativeCard(NovexCardTransferParser.parse(exported))).requireNativeImport()
            assertTrue(workspace.world(copy.localId)!!.modules.single().contentJson.contains("illustrations"))
            workspace.apply(NovexCommand.DeleteWorld(world.id))
            assertTrue(File(available.single().asset.path).isFile)
            val restored = NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(configuration), "chat")
            assertEquals(available, NovexStoryIllustrations.available(restored, emptyList()))
            val executedOnly = listOf(AssistantBlock("work", "text", "雾", executionText = true), AssistantBlock("answer", "text", "白天没有事情发生"))
            assertNull(NovexStoryImageCoordinator.completed(restored, emptyList(), executedOnly, 0, emptyList(), "reply"))
            val candidates = NovexStoryImageCoordinator.tool(restored, emptyList(), com.openminis.app.tools.NovexIllustrationTools.INSPECT, "{}")
            assertEquals(1, candidates.getJSONArray("images").length())
            assertFalse(candidates.toString().contains(available.single().asset.path))
            val chosen = NovexStoryImageCoordinator.tool(restored, emptyList(), com.openminis.app.tools.NovexIllustrationTools.SELECT,
                JSONObject().put("image_id", NovexStoryIllustrations.id(available.single())).toString())
            val explicitBlocks = listOf(AssistantBlock("selection", "tool_use", chosen.toString(), ToolBlockStatus.SUCCESS,
                toolName = com.openminis.app.tools.NovexIllustrationTools.SELECT), AssistantBlock("answer", "text", "再次展示这张图片。"))
            assertNotNull(NovexStoryImageCoordinator.completed(restored, emptyList(), explicitBlocks, 0, emptyList(), "reply"))
            val raw = encodeAssistantTurnParts(listOf(AgentContentPart.Text("雾中镇口")), mapOf("picture" to storyImageBlock(available.single(), "reply")))
            val parts = JSONArray(raw)
            assertEquals("雾中镇口", parts.getJSONObject(0).getString("value"))
            assertEquals(NOVEX_STORY_IMAGE, parts.getJSONObject(1).getString("type"))
            assertEquals(available.single(), NovexSnapshotMediaCodec.decode(parts.getJSONObject(1).getJSONObject("value")))
            assertTrue(buildTurnParts(listOf(storyImageBlock(available.single(), "reply")), 0, emptyMap()).isEmpty())
        } finally { db.close() }
    }
}
