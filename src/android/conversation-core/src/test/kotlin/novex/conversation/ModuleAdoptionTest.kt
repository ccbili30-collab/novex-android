package novex.conversation

import novex.content.*
import org.junit.Assert.*
import org.junit.Test

class ModuleAdoptionTest {
    private val window = TriggerWindow(2,setOf(MessageRole.USER))
    private fun module(id: String,use: ModuleUse?=null,tags: List<String> = emptyList()) =
        ContentModule(id,id,listOf(ContentBlock.Text("$id-body",ContentRef("$id-text"))),tags,use)
    private fun scope(vararg modules: ContentModule)=MaterialScope(listOf(AdoptedSource("world","revision-1",modules.toList())),setOf("managed-only"))
    private fun message(id: String,text: String,role: MessageRole=MessageRole.USER)=TriggerMessage(id,role,text)

    @Test fun `重复关键词和跨轮命中不叠加且话题退出不改历史`() {
        val m=module("harbor",ModuleUse.Keywords(listOf("港口","灯塔"),false,false));val source=scope(m)
        val messages=listOf(message("1","港口港口，灯塔"),message("2","再去港口"))
        repeat(3){ assertEquals(listOf("harbor"),ModuleAdoption.plan(source,messages,window).selected.map { it.module.id }) }
        val later=messages+message("3","去森林")+message("4","走进山洞")
        assertTrue(ModuleAdoption.plan(source,later,window).selected.isEmpty())
        assertEquals("港口港口，灯塔",later.first().text)
        assertEquals(m,source.adopted.single().modules.single())
    }
    @Test fun `重复来源去重而管理对象与标签不自动采用`() {
        val always=module("identity",ModuleUse.Always);val tag=module("tagged",tags=listOf("必须携带"))
        val source=scope(always,tag)
        val plan=ModuleAdoption.plan(source.copy(adopted=source.adopted+source.adopted),emptyList(),window)
        assertEquals(listOf("identity"),plan.selected.map { it.module.id })
        assertEquals(2,plan.decisions.size)
        assertEquals(AdoptionReason.UNCONFIGURED,plan.decisions.last().reason)
        assertFalse(plan.decisions.any { it.cardId=="managed-only" })
    }
    @Test fun `对话覆盖和本次手动选择不改变卡片或另一对话`() {
        val source=scope(module("a",ModuleUse.Always),module("b",ModuleUse.Manual))
        val first=ModuleAdoption.plan(source,emptyList(),window,mapOf("a" to UseOverride.Disabled),setOf("b"))
        assertEquals(listOf("b"),first.selected.map { it.module.id })
        val second=ModuleAdoption.plan(source,emptyList(),window)
        assertEquals(listOf("a"),second.selected.map { it.module.id })
        assertEquals(ModuleUse.Always,source.adopted.single().modules.first().use)
    }
    @Test fun `条件组合大小写和消息类型按显式配置生效`() {
        val source=scope(module("a",ModuleUse.Keywords(listOf("PORT","灯塔"),true,true)))
        val messages=listOf(message("1","PORT",MessageRole.TOOL),message("2","port 灯塔"))
        assertTrue(ModuleAdoption.plan(source,messages,window).selected.isEmpty())
        val override=mapOf("a" to UseOverride.Rule(ModuleUse.Keywords(listOf("PORT","灯塔"),false,true)))
        assertEquals(1,ModuleAdoption.plan(source,messages,window,override).selected.size)
        assertEquals(1,ModuleAdoption.plan(source,messages,TriggerWindow(2,setOf(MessageRole.USER,MessageRole.TOOL))).selected.size)
    }
    @Test fun `相同编号不同版本拒绝混合而不是静默选择一份`() {
        val source=scope(module("a",ModuleUse.Always))
        val changed=source.copy(adopted=source.adopted+source.adopted.single().copy(revision="revision-2"))
        assertThrows(IllegalArgumentException::class.java){ModuleAdoption.plan(changed,emptyList(),window)}
    }
}
