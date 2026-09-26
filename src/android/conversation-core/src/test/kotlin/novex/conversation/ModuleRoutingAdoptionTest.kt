package novex.conversation

import novex.content.CardKind
import novex.content.ContentBlock
import novex.content.ContentDocument
import novex.content.ContentModule
import novex.content.ContentRef
import novex.content.ModuleRouting
import novex.content.ModuleUse
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-stage1-tags] 路由标签判定矩阵（阶段 1 PR-A 最小接入）：
 * - null（存量卡）与 DEFAULT：selected=ROUTED_ACTIVATION（视同 Always，
 *   "存量全标默认"，能塞就塞）；
 * - PER_TURN / STYLE：ROUTED_SLOT 不进材料流（阶段 1 PR-B 槽位消费）；
 * - STANDBY：沿用 use 三态（ALWAYS/KEYWORD_MATCH/MANUAL/UNCONFIGURED）；
 * - 会话级 Disabled 覆盖仍然最高优先。
 */
class ModuleRoutingAdoptionTest {

    private fun module(routing: ModuleRouting?, use: ModuleUse? = null) = ContentModule(
        id = "m-${routing?.name ?: "null"}-${use?.javaClass?.simpleName ?: "noUse"}",
        name = "m", blocks = listOf(ContentBlock.Text("b", ContentRef("r"))),
        use = use, routing = routing,
    )

    private fun plan(vararg modules: ContentModule, overrideDisabled: Boolean = false, keywordText: String = "城门") = ModuleAdoption.plan(
        MaterialScope(listOf(AdoptedSource("c1", "r1", modules.toList())), emptySet()),
        listOf(TriggerMessage("t1", MessageRole.USER, keywordText)),
        TriggerWindow(6, setOf(MessageRole.USER, MessageRole.ASSISTANT)),
        overrides = if (overrideDisabled) mapOf(modules.first().id to UseOverride.Disabled) else emptyMap(),
    ).decisions.associateBy { it.module.id }

    @Test fun `null routing (legacy card) selects as activation`() {
        val d = plan(module(null))["m-null-noUse"]!!
        assertEquals(true, d.selected)
        assertEquals(AdoptionReason.ROUTED_ACTIVATION, d.reason)
    }

    @Test fun `default routing selects as activation`() {
        val d = plan(module(ModuleRouting.DEFAULT))["m-DEFAULT-noUse"]!!
        assertEquals(true, d.selected)
        assertEquals(AdoptionReason.ROUTED_ACTIVATION, d.reason)
    }

    @Test fun `slot routings leave the material stream`() {
        val ds = plan(module(ModuleRouting.PER_TURN), module(ModuleRouting.STYLE))
        assertEquals(false to AdoptionReason.ROUTED_SLOT, ds["m-PER_TURN-noUse"]!!.selected to ds["m-PER_TURN-noUse"]!!.reason)
        assertEquals(false to AdoptionReason.ROUTED_SLOT, ds["m-STYLE-noUse"]!!.selected to ds["m-STYLE-noUse"]!!.reason)
    }

    @Test fun `standby keeps use semantics`() {
        val always = plan(module(ModuleRouting.STANDBY, ModuleUse.Always))["m-STANDBY-Always"]!!
        val hit = plan(module(ModuleRouting.STANDBY, ModuleUse.Keywords(listOf("城门"), caseSensitive = false, requireAll = false)))["m-STANDBY-Keywords"]!!
        val miss = plan(module(ModuleRouting.STANDBY, ModuleUse.Keywords(listOf("城门"), caseSensitive = false, requireAll = false)), keywordText = "别处")["m-STANDBY-Keywords"]!!
        val unconfigured = plan(module(ModuleRouting.STANDBY, null))["m-STANDBY-noUse"]!!
        assertEquals(AdoptionReason.ALWAYS, always.reason)
        assertEquals(AdoptionReason.KEYWORD_MATCH, hit.reason)
        assertEquals(false, miss.selected)
        assertEquals(AdoptionReason.UNCONFIGURED, unconfigured.reason)
    }

    @Test fun `disabled override wins over routing`() {
        val d = plan(module(ModuleRouting.DEFAULT), overrideDisabled = true)["m-DEFAULT-noUse"]!!
        assertEquals(false, d.selected)
        assertEquals(AdoptionReason.DISABLED, d.reason)
    }

}
