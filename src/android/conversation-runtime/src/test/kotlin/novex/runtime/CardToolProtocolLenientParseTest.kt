package novex.runtime

import novex.model.PendingTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-lenient-parse]/[T-saved-with-ids] 2026-09-16 工具协议批的解析守护：
 * 多余键忽略、常见错法自纠（伪 block_id / kind 大小写 / 字符串数字位置）、
 * 缺必需键报错点名；保存结果带新建编号与可直链版本。
 */
class CardToolProtocolLenientParseTest {
    private fun call(name: String, args: String) = PendingTool("call_1", name, args)

    @Test fun createCardIgnoresExtraKeysAndNormalizesKind() {
        val request = CardToolProtocol.parse("chat", call("create_card",
            """{"kind":"world","name":"测试世界","description":"模型多给的说明字段"}"""))
        val edit = request.edit as CardToolEdit.Create
        assertEquals(novex.content.CardKind.WORLD, edit.kind)
        assertEquals("测试世界", edit.name)
    }

    @Test fun writeModuleTextAutocorrectsPseudoBlockIds() {
        listOf("new", "create", "NEW", " auto ", "null").forEach { pseudo ->
            val request = CardToolProtocol.parse("chat", call("write_module_text",
                """{"root_id":"r","target_id":"t","draft_version":"v1","module_id":"m","block_id":"$pseudo","name":"段落","text":"正文"}"""))
            assertNull((request.edit as CardToolEdit.WriteText).blockId)
        }
        // 缺省 block_id 同样按新建处理。
        val omitted = CardToolProtocol.parse("chat", call("write_module_text",
            """{"root_id":"r","target_id":"t","draft_version":"v1","module_id":"m","name":"段落","text":"正文"}"""))
        assertNull((omitted.edit as CardToolEdit.WriteText).blockId)
    }

    @Test fun missingRequiredKeyNamesItInMessage() {
        try {
            CardToolProtocol.parse("chat", call("add_module",
                """{"root_id":"r","target_id":"t","name":"模块"}""")) // 缺 draft_version
            fail("应当因缺 draft_version 拒绝")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message!!.contains("draft_version"))
        }
    }

    @Test fun replaceTextRangeAcceptsStringPositions() {
        val request = CardToolProtocol.parse("chat", call("replace_text_range",
            """{"root_id":"r","target_id":"t","draft_version":"v1","module_id":"m","block_id":"b","content_ref":"ref","start":"12","end":20,"text":"替换"}"""))
        val edit = request.edit as CardToolEdit.ReplaceTextRange
        assertEquals(12L, edit.start)
        assertEquals(20L, edit.end)
    }

    @Test fun savedResultCarriesCreatedIdsAndChainableVersion() {
        val json = JSONObject(CardToolProtocol.result(
            CardToolResult.Saved("r", "t", "rev9", listOf("m1", "m2"), listOf("b1"))))
        assertEquals("saved", json.getString("status"))
        assertEquals("saved:rev9", json.getString("next_draft_version"))
        assertEquals(listOf("m1", "m2"), json.getJSONArray("created_module_ids").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(listOf("b1"), json.getJSONArray("created_block_ids").let { a -> (0 until a.length()).map { a.getString(it) } })
    }
}
