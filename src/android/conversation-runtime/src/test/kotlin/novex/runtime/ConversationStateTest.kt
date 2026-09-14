package novex.runtime

import novex.model.PendingTool
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationStateTest {
    @get:Rule val temp=TemporaryFolder()
    private fun call(id:String="state",value:Int=72)=PendingTool(id,"update_playthrough_state",JSONObject().put("updates",JSONArray().put(JSONObject().put("key","体力").put("value",value)).put(JSONObject().put("key","位置").put("value","山门")).toString()).toString())
    @Test fun `批准记录重开重试不倒退且只读不提供工具`() {
        val root=temp.newFolder().toPath();val journal=TurnJournal(temp.newFolder().toPath());val store=ConversationStateStore(root)
        val tool=ConversationStateTools(store,journal,"chat","turn");val namespace="4:chat4:turn"
        assertEquals(CardToolResult.AwaitingApproval,tool.submit(namespace,call(),ToolPermission.APPROVAL))
        tool.select(namespace,call(),true);assertTrue(store.view("chat",listOf("turn")).isEmpty())
        val saved=tool.confirm(namespace,call(),ToolPermission.APPROVAL)
        assertTrue(saved is CardToolResult.StateSaved)
        assertEquals(listOf("72","山门"),ConversationStateStore(root).view("chat",listOf("turn")).map {it.display()})
        assertTrue(tool.submit(namespace,call("next",68),ToolPermission.FREE) is CardToolResult.StateSaved)
        assertEquals(saved,tool.submit(namespace,call(),ToolPermission.FREE))
        assertEquals("68",store.view("chat",listOf("turn"),listOf("体力")).single().display())
        assertTrue(tool.definitions(ToolPermission.READ_ONLY).isEmpty())
        assertEquals(CardToolResult.Denied,tool.submit(namespace,call("readonly"),ToolPermission.READ_ONLY))
    }
    @Test fun `状态路径及对话隔离并保留空缺值`() {
        val store=ConversationStateStore(temp.newFolder().toPath())
        store.record("one",StateEvent("event-a","a",listOf(StateUpdate("体力","100"))))
        store.record("one",StateEvent("event-b","b",listOf(StateUpdate("体力","20"))))
        store.record("one",StateEvent("event-c","c",listOf(StateUpdate("体力","90"))))
        assertEquals("20",store.view("one",listOf("a","b")).single().display())
        assertEquals("90",store.view("one",listOf("a","c")).single().display())
        assertTrue(store.view("two",listOf("a","b")).isEmpty())
        assertTrue(store.view("one",listOf("a"),listOf("不存在")).isEmpty())
        assertThrows(IllegalArgumentException::class.java){store.record("one",StateEvent("event-a","a",listOf(StateUpdate("体力","0"))))}
    }
    @Test fun `模型读取同当前路径保留类型缺失信息且不能越界`() {
        val store=ConversationStateStore(temp.newFolder().toPath());val journal=TurnJournal(temp.newFolder().toPath())
        store.record("chat",StateEvent("one","first",listOf(StateUpdate("体力","72"),StateUpdate("开启","false"),StateUpdate("地点","\"山门\""))))
        store.record("chat",StateEvent("another","off-path",listOf(StateUpdate("体力","0"))))
        val tool=ConversationStateTools(store,journal,"chat","current"){listOf("first","current")}
        val request=PendingTool("read","read_conversation_state","{\"keys\":[\"体力\",\"开启\",\"地点\",\"背包\"]}")
        val result=JSONObject((tool.read(request,ToolPermission.APPROVAL) as CardToolResult.Read).content)
        assertEquals(72,result.getJSONObject("values").getInt("体力"));assertFalse(result.getJSONObject("values").getBoolean("开启"))
        assertEquals("山门",result.getJSONObject("values").getString("地点"));assertEquals("背包",result.getJSONArray("missing").getString(0))
        assertEquals(CardToolResult.Denied,tool.read(request,ToolPermission.READ_ONLY))
        val other=ConversationStateTools(store,journal,"other","current"){listOf("first","current")}
        assertEquals(0,JSONObject((other.read(request,ToolPermission.FREE) as CardToolResult.Read).content).getJSONObject("values").length())
        assertThrows(IllegalArgumentException::class.java){tool.read(request.copy(arguments="{\"keys\":[],\"chat\":\"other\"}"),ToolPermission.FREE)}
        assertThrows(IllegalArgumentException::class.java){tool.read(request.copy(arguments="{\"keys\":[1]}"),ToolPermission.FREE)}
    }
    @Test fun `拒绝和坏参数不写状态且不能指定其他对话`() {
        val store=ConversationStateStore(temp.newFolder().toPath());val tools=ConversationStateTools(store,TurnJournal(temp.newFolder().toPath()),"chat","turn")
        val namespace="4:chat4:turn"
        tools.submit(namespace,call(),ToolPermission.APPROVAL)
        assertEquals(CardToolResult.Denied,tools.reject(namespace,call()));assertTrue(store.view("chat",listOf("turn")).isEmpty())
        assertThrows(IllegalArgumentException::class.java){tools.submit("another",call(),ToolPermission.FREE)}
        for(updates in listOf("[{\"key\":\"x\",\"value\":null}]","[{\"key\":\"x\",\"value\":{}}]","[{\"key\":\"x\",\"value\":1},{\"key\":\"x\",\"value\":2}]")) {
            assertThrows(IllegalArgumentException::class.java){ConversationStateTools.updates(JSONObject().put("updates",updates).toString())}
        }
    }
}
