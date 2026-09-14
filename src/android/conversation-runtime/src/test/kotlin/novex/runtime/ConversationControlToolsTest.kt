package novex.runtime

import novex.model.PendingTool
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationControlToolsTest {
    @get:Rule val temporary=TemporaryFolder()
    private val namespace="4:chat4:turn"
    private val call=PendingTool("registration","register_controls",JSONObject().put("controls","""[{"actionKey":"go","label":"前进","behavior":"action","prompt":"向前走"}]""").toString())
    @Test fun `只读无接口批准勾选不注册确认才保存重开返回同一回执`() {
        val root=temporary.newFolder().toPath();val records=temporary.newFolder().toPath()
        fun tools()=ConversationControlTools(ConversationControlStore(root),TurnJournal(records),"chat","reply")
        val store=ConversationControlStore(root)
        assertTrue(tools().definitions(ToolPermission.READ_ONLY).isEmpty())
        assertEquals(CardToolResult.Denied,tools().submit(namespace,call,ToolPermission.READ_ONLY))
        assertNull(TurnJournal(records).read(namespace,call.id))
        assertEquals(CardToolResult.AwaitingApproval,tools().submit(namespace,call,ToolPermission.APPROVAL))
        assertEquals(CardToolResult.AwaitingApproval,tools().confirm(namespace,call,ToolPermission.APPROVAL))
        tools().select(namespace,call,true)
        assertTrue(store.read("chat").controls.visible(listOf("reply")).isEmpty())
        assertEquals(CardToolResult.Denied,tools().confirm(namespace,call,ToolPermission.READ_ONLY))
        assertEquals(TurnState.FINISHED,TurnJournal(records).read(namespace,call.id)!!.state)
        assertEquals(CardToolResult.Denied,tools().confirm(namespace,call,ToolPermission.APPROVAL))
        val fresh=call.copy(id="fresh-registration")
        tools().submit(namespace,fresh,ToolPermission.APPROVAL);tools().select(namespace,fresh,true)
        val registered=tools().confirm(namespace,fresh,ToolPermission.APPROVAL) as CardToolResult.Registered
        assertEquals("chat",registered.conversationId)
        val saved=store.read("chat");assertEquals(1,saved.controls.visible(listOf("reply")).size)
        assertEquals(registered,tools().submit(namespace,fresh,ToolPermission.APPROVAL))
        assertEquals(saved.version,store.read("chat").version)
    }
    @Test fun `自由直接注册停止和跨对话运行不产生菜单`() {
        val store=ConversationControlStore(temporary.newFolder().toPath());val journal=TurnJournal(temporary.newFolder().toPath())
        val tools=ConversationControlTools(store,journal,"chat","reply")
        val stopped=ToolStop();stopped.stop()
        assertTrue(tools.submit(namespace,call,ToolPermission.FREE,stopped) is CardToolResult.Stopped)
        assertTrue(store.read("chat").controls.visible(listOf("reply")).isEmpty())
        assertTrue(tools.submit("5:other4:turn",call,ToolPermission.FREE) is CardToolResult.Failed)
        assertTrue(tools.submit(namespace,call.copy(id="new"),ToolPermission.FREE) is CardToolResult.Registered)
        assertEquals(1,store.read("chat").controls.visible(listOf("reply")).size)
    }
    @Test fun `拒绝快捷注册持久保存且不产生按钮`() {
        val root=temporary.newFolder().toPath();val records=temporary.newFolder().toPath()
        fun tools()=ConversationControlTools(ConversationControlStore(root),TurnJournal(records),"chat","reply")
        tools().submit(namespace,call,ToolPermission.APPROVAL);tools().select(namespace,call,true)
        assertEquals(CardToolResult.Denied,tools().reject(namespace,call))
        assertEquals(CardToolResult.Denied,tools().submit(namespace,call,ToolPermission.FREE))
        assertTrue(ConversationControlStore(root).read("chat").controls.visible(listOf("reply")).isEmpty())
    }

}
