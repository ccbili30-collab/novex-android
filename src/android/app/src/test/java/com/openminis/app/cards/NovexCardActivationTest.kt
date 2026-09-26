package com.openminis.app.cards

import novex.content.CardKind
import novex.content.ContentBlock
import novex.content.ContentDocument
import novex.content.ContentModule
import novex.content.ContentRef
import novex.content.ModuleRouting
import novex.content.ModuleTemporality
import novex.content.ModuleUse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [T-stage1-activation] 开局激活协议守护：
 * 资料包只收 default 路由模块（effective 语义：未配置=默认、standby 与
 * use 规则模块不进开局）；per_turn/style 文本分流；清单/启动词形态；
 * parts_json 结构；预算护栏 fail-loud；binding 幂等键（activatedKey 不自引用）。
 */
class NovexCardActivationTest {

    private fun textModule(id: String, name: String, text: String,
                           routing: ModuleRouting? = null, use: ModuleUse? = null,
                           temporality: ModuleTemporality? = null) =
        ContentModule(id, name, listOf(ContentBlock.Text("$id-b", ContentRef("ref://$id"))),
            use = use, routing = routing, temporality = temporality)

    private fun card(vararg modules: ContentModule, kind: CardKind = CardKind.WORLD) =
        ContentDocument("world", kind, "海港", modules.toList())

    private val texts = mapOf(
        "ref://engine" to "引擎宪法正文。",
        "ref://events" to "事件池正文。",
        "ref://perturn" to "每轮注入正文。",
        "ref://style" to "文风正文。",
    )

    private fun build(vararg cards: ContentDocument) = NovexCardActivation.build(
        cards.toList(),
        readText = { ref -> texts[ref.value] ?: "" },
    )

    @Test
    fun `package collects default-routed modules only`() {
        val material = build(
            card(
                textModule("engine", "引擎宪法", "引擎宪法正文。"), // 未配置 → DEFAULT
                textModule("events", "事件池", "事件池正文。", use = ModuleUse.Keywords(listOf("港口"), caseSensitive = false, requireAll = false)), // use 规则 → STANDBY 不进
                textModule("perturn", "每轮注入", "每轮注入正文。", routing = ModuleRouting.PER_TURN),
                textModule("style", "文风", "文风正文。", routing = ModuleRouting.STYLE),
                textModule("explicit", "明示默认", "引擎宪法正文。", routing = ModuleRouting.DEFAULT),
            ),
        )
        // default 收 engine + explicit；events 不进
        assertEquals(2, material.defaultModuleCount)
        assertTrue(material.packageText.contains("引擎宪法正文。"))
        assertTrue(!material.packageText.contains("事件池正文。"))
        assertTrue(material.packageText.contains("已就位"))
        // 分流
        assertEquals("每轮注入正文。", material.perTurnText)
        assertEquals("文风正文。", material.styleText)
        // 清单含模块名
        assertTrue(material.packageText.contains("· 引擎宪法"))
    }

    @Test
    fun `parts json is a single text part carrying the package`() {
        val material = build(card(textModule("engine", "引擎", "引擎宪法正文。")))
        val parts = JSONObject(NovexCardActivation.partsJson(material)).getJSONObject(0)
        assertEquals("text", parts.getString("type"))
        assertEquals(material.packageText, parts.getString("value"))
    }

    @Test
    fun `budget guard fails loud with listing`() {
        val material = build(card(textModule("big", "大模块", "引擎宪法正文。")))
        val e = assertThrows(IllegalArgumentException::class.java) {
            NovexCardActivation.requireFits(material, availableTokens = 3, count = { it.length })
        }
        assertTrue(e.message!!.contains("超出本会话可用上下文"))
        assertTrue(e.message!!.contains("已载入开局资料"))
    }

    @Test
    fun `binding activation key round-trips and excludes itself`() {
        val binding = CardBinding(primary = novex.runtime.SourceSelection("world"))
        val key = binding.copy(activatedKey = null).encode()
        val activated = binding.copy(activatedKey = key)
        // encode→decode 往返保真
        assertEquals(activated, CardBinding.decode(activated.encode()))
        // 已激活的 binding 再次计算激活键 = 同一值（幂等判定成立）
        assertEquals(key, activated.copy(activatedKey = null).encode())
        // 无 activatedKey 的旧卡 JSON 解析为 null（向后兼容）
        assertEquals(null, CardBinding.decode(binding.encode())!!.activatedKey)
    }
}
