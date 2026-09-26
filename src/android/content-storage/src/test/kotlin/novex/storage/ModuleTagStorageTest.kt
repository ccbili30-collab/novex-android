package novex.storage

import novex.content.CardKind
import novex.content.ContentBlock
import novex.content.ContentDocument
import novex.content.ContentModule
import novex.content.ContentRef
import novex.content.ModuleRouting
import novex.content.ModuleTemporality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-stage1-tags] 标签编解码守护：带标签往返保真；不带标签编码省略
 * （旧格式不膨胀）；旧卡（无字段）读出 null（语义=default/constant，
 * 存量零迁移）；未知取值严格报错不静默丢弃（格式既定行为）。
 */
class ModuleTagStorageTest {

    private fun moduleOf(routing: ModuleRouting?, temporality: ModuleTemporality?) = ContentModule(
        id = "m1", name = "世界观",
        blocks = listOf(ContentBlock.Text("b1", ContentRef("ref://x"))),
        routing = routing, temporality = temporality,
    )

    private fun cardOf(module: ContentModule) = ContentDocument(
        id = "c1", kind = CardKind.WORLD, name = "卡",
        modules = listOf(module),
    )

    @Test fun `tags round-trip faithfully`() {
        val card = cardOf(moduleOf(ModuleRouting.PER_TURN, ModuleTemporality.SNAPSHOT))
        val decoded = CardStructureCodec.decode(CardStructureCodec.encode(card))
        val module = decoded.modules.single()
        assertEquals(ModuleRouting.PER_TURN, module.routing)
        assertEquals(ModuleTemporality.SNAPSHOT, module.temporality)
    }

    @Test fun `null tags are omitted from encoding`() {
        val json = CardStructureCodec.encode(cardOf(moduleOf(null, null)))
            .getJSONArray("modules").getJSONObject(0)
        assertFalse(json.has("routing"))
        assertFalse(json.has("temporality"))
    }

    @Test fun `legacy module without tag fields decodes to null`() {
        val legacy = """{"id":"c1","kind":"WORLD","name":"卡","appearance":{"avatar":null,"cover":null,"readingLayout":"PAGED"},
            "extensions":{},"resources":[],
            "modules":[{"id":"m1","name":"旧模块","blocks":[{"kind":"text","id":"b1","content":"ref://x"}]}],
            "characters":[]}"""
        val module = CardStructureCodec.decode(JSONObject(legacy)).modules.single()
        assertNull(module.routing)
        assertNull(module.temporality)
        // 语义层由消费端 ?: DEFAULT / ?: CONSTANT 兜底（ModuleAdoption 判定）。
    }

    @Test fun `unknown routing value is rejected not dropped`() {
        val json = """{"id":"c1","kind":"WORLD","name":"卡","appearance":{"avatar":null,"cover":null,"readingLayout":"PAGED"},
            "extensions":{},"resources":[],
            "modules":[{"id":"m1","name":"m","blocks":[],"routing":"sideways"}],
            "characters":[]}"""
        var thrown: Exception? = null
        try { CardStructureCodec.decode(JSONObject(json)) } catch (e: Exception) { thrown = e }
        assertTrue("未知取值必须严格报错", thrown is IllegalArgumentException)
    }
}
