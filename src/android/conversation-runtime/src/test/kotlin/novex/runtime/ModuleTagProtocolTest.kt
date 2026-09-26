package novex.runtime

import novex.content.ModuleRouting
import novex.content.ModuleTemporality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [T-stage1-tags] 工具边界标签编解码：snake_case 互转、字符串容错与
 * 中文别名、null 语义（未提供）、非法值报错（CardBulk 会带 JSON 路径
 * 包装）。注意与 use 的 kind 别名集不相交：constant 在 rule 里映射
 * always（历史别名），在 temporality 里是本意，互不干扰。
 */
class ModuleTagProtocolTest {

    @Test fun `routing codec round-trips and tolerates aliases`() {
        assertNull(ModuleOptionsProtocol.decodeRouting(null))
        assertNull(ModuleOptionsProtocol.decodeRouting(JSONObject.NULL))
        assertEquals(ModuleRouting.DEFAULT, ModuleOptionsProtocol.decodeRouting("default"))
        assertEquals(ModuleRouting.DEFAULT, ModuleOptionsProtocol.decodeRouting(" 默认 "))
        assertEquals(ModuleRouting.PER_TURN, ModuleOptionsProtocol.decodeRouting("per-turn"))
        assertEquals(ModuleRouting.STYLE, ModuleOptionsProtocol.decodeRouting("style"))
        assertEquals(ModuleRouting.STANDBY, ModuleOptionsProtocol.decodeRouting("on_demand"))
        ModuleRouting.entries.forEach {
            assertEquals(it, ModuleOptionsProtocol.decodeRouting(ModuleOptionsProtocol.encodeRouting(it)))
        }
    }

    @Test fun `temporality codec round-trips and tolerates chinese aliases`() {
        assertNull(ModuleOptionsProtocol.decodeTemporality(null))
        assertEquals(ModuleTemporality.CONSTANT, ModuleOptionsProtocol.decodeTemporality("constant"))
        assertEquals(ModuleTemporality.SNAPSHOT, ModuleOptionsProtocol.decodeTemporality("快照"))
        ModuleTemporality.entries.forEach {
            assertEquals(it, ModuleOptionsProtocol.decodeTemporality(ModuleOptionsProtocol.encodeTemporality(it)))
        }
    }

    @Test fun `illegal values throw with clear message`() {
        assertThrows(IllegalArgumentException::class.java) { ModuleOptionsProtocol.decodeRouting("sideways") }
        assertThrows(IllegalArgumentException::class.java) { ModuleOptionsProtocol.decodeTemporality("eternal") }
        assertThrows(IllegalArgumentException::class.java) { ModuleOptionsProtocol.decodeRouting(42) }
    }

    @Test fun `bulk tree carries tags through parse and build`() {
        val tree = CardBulk.parseTree(JSONObject("""{"modules":[
            {"name":"世界观","text":"法则","routing":"default","temporality":"constant"},
            {"name":"开局流程","text":"起点","routing":"default","temporality":"snapshot"},
            {"name":"人物库","text":"","routing":"standby","children":[
                {"name":"鲁智深","text":"卡"}]}]}"""), "modules")
        assertEquals(ModuleRouting.DEFAULT, tree[0].routing)
        assertEquals(ModuleTemporality.CONSTANT, tree[0].temporality)
        assertEquals(ModuleTemporality.SNAPSHOT, tree[1].temporality)
        assertEquals(ModuleRouting.STANDBY, tree[2].routing)
        assertNull(tree[2].temporality) // 未提供=落库 null（语义 constant）
        assertNull(tree[2].children[0].routing)
        val built = CardBulk.buildModules(tree, "s", { novex.content.ContentRef("ref://stub") }, intArrayOf(0))
        assertEquals(ModuleRouting.STANDBY, built[2].routing)
        assertNull(built[2].children[0].routing)
    }

    @Test fun `illegal tag in bulk reports json path`() {
        val bad = JSONObject("""{"modules":[{"name":"x","text":"y","routing":"sideways"}]}""")
        val e = assertThrows(IllegalArgumentException::class.java) { CardBulk.parseTree(bad, "modules") }
        assert(e.message.orEmpty().contains("modules[0].routing")) { e.message ?: "" }
    }

    @Test fun `module schema exposes tag fields`() {
        val schema = CardBulk.moduleSchema()
        val props = schema.getJSONObject("properties")
        assertEquals("string", props.getJSONObject("routing").optString("type"))
        assertEquals("string", props.getJSONObject("temporality").optString("type"))
        // required 仍只有 name——标签可选（缺省默认语义）。
        val required = props.let { schema.getJSONArray("required") }
        assertEquals(listOf("name"), (0 until required.length()).map { required.getString(it) })
    }

}
