package com.openminis.app.novex.domain

import com.openminis.app.novex.adapter.NovexTestWorkspaceFactory as NovexWorkspaceFactory

import android.app.Application
import androidx.room.Room
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
class NovexWorldbookRuntimeTest {
    @get:Rule val files = TemporaryFolder()
    private fun candidate(id: String, condition: String, text: String = "正文") = NovexContextCandidate(id, "世界书$id", text, worldbookConditions = listOf(condition))

    @Test fun combinedBudgetAndStableOrderApplyAcrossAllBooks() {
        val always = """{"constant":true}"""
        val books = listOf(candidate("b", always, "第二本"), candidate("a", always, "第一本"), candidate("c", """{"keys":["钟楼"]}""", "关键词正文"))
        val result = NovexWorldbookRuntime.evaluate(books, null, listOf("来到钟楼"), 3) { it.length }
        assertEquals(listOf("a"), result.fragments.map { it.sourceId })
        assertEquals(2, result.omissions.size)
        assertTrue(result.omissions.all { it.reason.contains("预算") })
        val noHit = NovexWorldbookRuntime.evaluate(listOf(books.last()), null, listOf("去别处"), 100) { it.length }
        assertTrue(noHit.fragments.isEmpty()); assertTrue(noHit.omissions.single().reason.contains("未命中"))
    }

    @Test fun unsupportedAndDisabledConditionsNeverBecomeAlwaysOn() {
        listOf("""{"enabled":false,"constant":true}""", """{"constant":true,"recursive":true}""", """{"scanDepth":101}""", """{"enabled":"false"}""", "broken").forEach {
            assertTrue(NovexWorldbookRuntime.evaluate(listOf(candidate("a", it)), null, listOf("钟楼"), 100) { text -> text.length }.fragments.isEmpty())
        }
        assertNotNull(NovexWorldbookConditions.omission(listOf("""{"keys":["Alice"],"caseSensitive":true}"""), listOf("alice")))
        assertNull(NovexWorldbookConditions.omission(listOf("""{"keys":["Alice"]}"""), listOf("alice")))
        assertNotNull(NovexWorldbookConditions.omission(listOf("""{"keys":["钟楼"],"scanDepth":1}"""), listOf("钟楼", "离开")))
    }

    @Test fun conditionsSurviveSnapshotsAndUnmatchedBodiesCannotLeakThroughReadOrSearch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "media"))
            val world = workspace.apply(NovexCommand.CreateWorld("怪谈", "普通世界简介")).requireWorld()
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.CUSTOM, "夜间规则",
                """{"text":"秘密是银铃能唤醒镇长","contextTrigger":{"version":1,"keys":["钟楼"]}}""", id = "night"))
            val original = NovexConversationContextAdoption(workspace).adopt(NovexConversationConfigurationSnapshot("chat", backgroundSettings = listOf(BackgroundSetting(NovexContentAddress.world(world.id)))))
            val restored = NovexConversationConfigurationCodec.decode(NovexConversationConfigurationCodec.encode(original), "chat")
            assertTrue(WorkspaceNovexContextLoader(workspace).load(restored).single { it.sourceId == "night" }.worldbookConditions.isNotEmpty())
            val closed = NovexContextReadService(workspace, visibleMessages = listOf("去邮局"))
            assertEquals(0, closed.search(restored, "银铃").getInt("total_sources_matched"))
            assertTrue(runCatching { closed.read(restored, "night") }.isFailure)
            assertFalse(closed.inspect(restored).toString().contains("银铃"))
            val open = NovexContextReadService(workspace, visibleMessages = listOf("来到钟楼"))
            assertTrue(open.read(restored, "night").getString("text").contains("银铃"))
            workspace.apply(NovexCommand.SaveModule("night", "夜间规则", """{"text":"新正文"}"""))
            assertTrue(open.read(restored, "night").getString("text").contains("银铃"))
        } finally { db.close() }
    }

    @Test fun entryConditionsAndModuleConditionsMustBothMatchWithoutLeakingSiblingEntries() {
        val raw = """{"kind":"collection","contextTrigger":{"keys":["镇口"]},"items":[{"id":"one","name":"银铃","description":"第一条秘密","contextTrigger":{"keys":["月亮"]}},{"id":"two","name":"邮局","description":"第二条秘密","contextTrigger":{"enabled":false,"constant":true}}]}"""
        val candidates = NovexWorldbookConditions.candidates(NovexContextCandidate("module", "怪谈", "all"), ContentModuleType.REGION, raw)
        assertEquals(2, candidates.size)
        assertTrue(NovexWorldbookRuntime.evaluate(candidates, null, listOf("镇口"), 100) { it.length }.fragments.isEmpty())
        val hits = NovexWorldbookRuntime.evaluate(candidates, null, listOf("镇口月亮"), 100) { it.length }.fragments
        assertEquals(1, hits.size); assertTrue(hits.single().text.contains("第一条秘密")); assertFalse(hits.single().text.contains("第二条秘密"))
        assertEquals("module:entry:two", NovexWorldbookConditions.candidates(NovexContextCandidate("module", "怪谈", "all"), ContentModuleType.REGION, raw, "two").single().sourceId)
    }

    @Test fun incompatibleAdoptedRevisionsDoNotSilentlyMergeConditions() {
        val a = candidate("same", """{"constant":true}""")
        val b = candidate("same", """{"enabled":false}""")
        assertEquals(1, NovexContextSourceVersions.merge(listOf(a, a)).size)
        assertTrue(runCatching { NovexContextSourceVersions.merge(listOf(a, b)) }.isFailure)
    }
    @Test fun nativePackagePreservesFormalConditionsAndExplicitRemoval() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val workspace = NovexWorkspaceFactory.create(db, File(files.root, "exchange-media"))
            val world = workspace.apply(NovexCommand.CreateWorld("条件交换", "")).requireWorld()
            val raw = """{"kind":"collection","contextTrigger":{"keys":["镇口"]},"items":[{"id":"one","name":"银铃","description":"秘密","contextTrigger":{"enabled":false,"constant":true},"extension":"保留"}]}"""
            workspace.apply(NovexCommand.AddModule(ModuleOwner.world(world.id), ContentModuleType.REGION, "条目", raw, id = "exchange-module"))
            suspend fun copy(id: String): String {
                val exported = workspace.apply(NovexCommand.ExportNativeWorld(id)).requireNativeCard()
                val decoded = com.openminis.app.data.character.NovexCardPackageCodec.decode(com.openminis.app.data.character.NovexCardPackageCodec.encode(exported))
                return workspace.apply(NovexCommand.ImportNativeCard(com.openminis.app.data.character.NovexCardTransferParser.parse(decoded))).requireNativeImport().localId
            }
            val copied = copy(world.id)
            val exported = workspace.apply(NovexCommand.ExportNativeWorld(copied)).requireNativeCard()
            val module = JSONObject(exported.documentJson).getJSONArray("modules").getJSONObject(0).getJSONObject("content")
            assertEquals("镇口", module.getJSONObject("contextTrigger").getJSONArray("keys").getString(0))
            assertFalse(module.getJSONArray("items").getJSONObject(0).getJSONObject("contextTrigger").getBoolean("enabled"))
            assertEquals("保留", module.getJSONArray("items").getJSONObject(0).getString("extension"))
            val document = com.openminis.app.data.character.ContentModuleDocumentCodec.decode(ContentModuleType.REGION, raw) as com.openminis.app.data.character.ContentModuleDocument.Collection
            assertFalse(document.items.single().preservedJson.contains("contextTrigger"))
            val removed = JSONObject(com.openminis.app.data.character.ContentModuleDocumentCodec.edit(raw, document.copy(items = document.items.map { it.copy(contextTriggerJson = null) }))).apply { remove("contextTrigger") }.toString()
            workspace.apply(NovexCommand.SaveModule("exchange-module", "条目", removed))
            val removedCopy = copy(world.id)
            val cleaned = JSONObject(workspace.apply(NovexCommand.ExportNativeWorld(removedCopy)).requireNativeCard().documentJson).getJSONArray("modules").getJSONObject(0).getJSONObject("content")
            assertFalse(cleaned.has("contextTrigger"))
            assertFalse(cleaned.getJSONArray("items").getJSONObject(0).has("contextTrigger"))
        } finally { db.close() }
    }

    @Test fun stringContainingJsonRemainsUnsupportedInsteadOfExecutingAsAnObject() {
        val raw = JSONObject().put("text", "秘密").put("contextTrigger", """{"constant":true}""").toString()
        val condition = requireNotNull(NovexWorldbookConditions.read(raw))
        assertNotNull(NovexWorldbookConditions.omission(listOf(condition), emptyList()))
    }

}
