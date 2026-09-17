package novex.runtime

import novex.model.PendingTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── [T-schema-optional-fields] 2026-09-17 会话 b314941f 事故回归 ──
    // schema required 必须与解析层 defaulted 集合同契约：把"空字符串=合法
    // 语义"的字段标 required，会让外层发送前预检把模型按文档传的 ""/缺省
    // 当"参数缺失"拒发（write_module_text 新建块三连拒、模型弃用换路）。

    private fun requiredOf(name: String): Set<String> {
        val tool = CardToolProtocol.definitions(CardToolPolicy(setOf(ManagementTarget("card")))).first { it.name == name }
        return JSONObject(tool.parameters).getJSONArray("required").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    }

    @Test fun documentedEmptyStringFieldsAreNotRequiredInSchema() {
        // 事故主角：write_module_text.block_id（"新建传空字符串"）。
        assertFalse(requiredOf("write_module_text").contains("block_id"))
        // 同族文档承诺空串语义的定位字段（解析层 defaulted 全集在 tool() 族）。
        assertFalse(requiredOf("save_conversation_image").contains("module_id"))
        assertFalse(requiredOf("save_conversation_image").contains("after_id"))
        assertFalse(requiredOf("insert_owned_image").contains("after_id"))
        assertFalse(requiredOf("move_module").contains("before_id"))
        assertFalse(requiredOf("move_content_block").contains("before_id"))
        // 语义上真的必填的不许被顺带放开：删块/局部替换仍需 block_id。
        assertTrue(requiredOf("remove_content_block").contains("block_id"))
        assertTrue(requiredOf("replace_text_range").contains("block_id"))
    }

    @Test fun writeModuleTextEmptyStringBlockIdIsANewBlock() {
        // 模型按文档传 ""（会话 b314941f #55 原样形状）——解析层按新建处理，
        // schema 侧已不再 required，外层预检也不会拦。
        val request = CardToolProtocol.parse("chat", call("write_module_text",
            """{"root_id":"r","target_id":"t","draft_version":"v1","module_id":"m","block_id":"","name":"段落","text":"正文"}"""))
        assertNull((request.edit as CardToolEdit.WriteText).blockId)
    }

    // ── [T-bulk-string-tolerance] 字符串编码数组/对象兼容 + 教学报错 ──

    @Test fun createCardBulkAcceptsStringEncodedModules() {
        // 会话 b314941f #59 形状：modules 被整体编码成字符串 → 旧版判"缺失"。
        val request = CardToolProtocol.parse("chat", call("create_card_bulk",
            """{"kind":"WORLD","name":"测试世界","modules":"[{\"name\":\"00·启动说明\",\"text\":\"正文\"}]"}"""))
        val edit = request.edit as CardToolEdit.CreateBulk
        assertEquals(1, edit.tree.size)
        assertEquals("00·启动说明", edit.tree[0].name)
    }

    @Test fun addModuleBulkAcceptsStringEncodedModule() {
        val request = CardToolProtocol.parse("chat", call("add_module_bulk",
            """{"root_id":"r","target_id":"t","draft_version":"v1","module":"{\"name\":\"12·事件池\",\"text\":\"一行式事件\"}"}"""))
        val edit = request.edit as CardToolEdit.AddModuleBulk
        assertEquals(1, edit.tree.size)
        assertEquals("12·事件池", edit.tree[0].name)
    }

    @Test fun bulkMissingArgsErrorTeachesTheCorrectShape() {
        try {
            CardToolProtocol.parse("chat", call("create_card_bulk",
                """{"kind":"WORLD","name":"测试世界"}"""))
            fail("应当因 modules 缺失拒绝")
        } catch (failure: IllegalArgumentException) {
            assertTrue("报错应带正确形状示例: ${failure.message}", failure.message!!.contains("\"modules\":["))
        }
        try {
            CardToolProtocol.parse("chat", call("add_module_bulk",
                """{"root_id":"r","target_id":"t","draft_version":"v1"}"""))
            fail("应当因 module 缺失拒绝")
        } catch (failure: IllegalArgumentException) {
            assertTrue("报错应带正确形状示例: ${failure.message}", failure.message!!.contains("\"module\":{"))
        }
    }
}
