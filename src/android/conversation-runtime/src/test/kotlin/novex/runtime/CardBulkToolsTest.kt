package novex.runtime

import novex.content.*
import novex.model.PendingTool
import novex.storage.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-bulk-tools] 宏工具守护：整包校验（错误带 JSON 路径、不写半张卡）、
 * 原子落库（树完整、编号回传）、插入定位与草稿推进。
 */
class CardBulkToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun tree(vararg names: String): String =
        JSONArray(names.map { JSONObject().put("name", it).put("text", "正文-$it") }).toString()

    private fun coordinator(store: CardStore) =
        CardToolCoordinator(store, TurnJournal(temporary.newFolder().toPath()))

    private fun policy() = CardToolPolicy(setOf(ManagementTarget("card")))

    @Test fun createBulkValidatesBeforeWriting() {
        val store = CardStore(temporary.newFolder().toPath())
        val bad = PendingTool("c1", "create_card_bulk",
            JSONObject().put("kind", "world").put("name", "坏树")
                .put("modules", JSONArray(listOf(
                    JSONObject().put("name", "有名字的"),
                    JSONObject().put("text", "缺名字的模块"),
                ))).toString())
        try {
            CardToolProtocol.parse("chat", bad)
            fail("缺 name 应在解析期拒绝")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message!!.contains("modules[1].name"))
        }
        // 解析即拒 → 卡未落库。
        assertTrue(store.open("card") == null && CardDrafts(store).read("card") == null)
    }

    @Test fun createBulkSavesWholeTreeAndReturnsIds() {
        val store = CardStore(temporary.newFolder().toPath())
        val modules = JSONObject().put("kind", "CHARACTER").put("name", "林绾月")
            .put("modules", JSONArray(listOf(
                JSONObject().put("name", "基础档案").put("text", "身份资料")
                    .put("children", JSONArray(listOf(JSONObject().put("name", "外貌")))),
                JSONObject().put("name", "修仙设定").put("text", "筑基后期"),
            )))
        val request = CardToolProtocol.parse("chat", PendingTool("c2", "create_card_bulk", modules.toString()))
        val saved = coordinator(store).submit(request, policy()) as CardToolResult.Saved
        assertEquals("card", saved.rootId)
        val card = ContentTargets.find(store.open("card")!!.content, "card")
        assertEquals(listOf("基础档案", "修仙设定"), card.modules.map { it.name })
        assertEquals(listOf("外貌"), card.modules[0].children.map { it.name })
        // 编号树全量回传：两个根模块 + 一个子模块；两个文字块（外貌无正文）。
        assertEquals(3, saved.createdModules.size)
        assertEquals(2, saved.createdBlocks.size)
    }

    @Test fun addModuleBulkAppendsAndInsertsBefore() {
        val store = CardStore(temporary.newFolder().toPath())
        CardDrafts(store).create(ContentDocument("card", CardKind.CHARACTER, "角色",
            listOf(ContentModule("m0", "原有", emptyList()))))
        val version = CardDrafts(store).read("card")!!.version

        // 末尾追加
        val append = CardToolProtocol.parse("chat", PendingTool("c3", "add_module_bulk",
            JSONObject().put("root_id", "card").put("target_id", "card").put("draft_version", version)
                .put("module", JSONObject().put("name", "背景故事").put("text", "出身")).toString()))
        val first = coordinator(store).submit(append, policy()) as CardToolResult.Saved
        assertEquals(listOf("原有", "背景故事"),
            ContentTargets.find(store.open("card")!!.content, "card").modules.map { it.name })

        // 定位插入：插到「原有」之前（用上次保存返回的 saved: 直链版本）。
        val insert = CardToolProtocol.parse("chat", PendingTool("c4", "add_module_bulk",
            JSONObject().put("root_id", "card").put("target_id", "card")
                .put("draft_version", "saved:${first.revision}")
                .put("before_id", "m0")
                .put("module", JSONObject().put("name", "前置设定")).toString()))
        coordinator(store).submit(insert, policy())
        assertEquals(listOf("前置设定", "原有", "背景故事"),
            ContentTargets.find(store.open("card")!!.content, "card").modules.map { it.name })
    }

    @Test fun ruleLenientDecodesStringAndAliases() {
        assertEquals(ModuleUse.Always, ModuleOptionsProtocol.decodeLenient("""{"kind":"constant"}"""))
        assertEquals(null, ModuleOptionsProtocol.decodeLenient("""{"kind":"Default","extra":1}"""))
        val keywords = ModuleOptionsProtocol.decodeLenient("""{"kind":"keywords","words":["文游规则"],"case_sensitive":"true"}""") as ModuleUse.Keywords
        assertEquals(listOf("文游规则"), keywords.words)
        assertEquals(true, keywords.caseSensitive)
    }
}
