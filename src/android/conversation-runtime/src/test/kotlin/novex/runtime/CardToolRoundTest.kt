package novex.runtime

import com.sun.net.httpserver.HttpServer
import novex.content.*
import novex.storage.*
import novex.model.*
import novex.conversation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.URI

class CardToolRoundTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun args(version:String)=JSONObject().put("root_id","card").put("target_id","card").put("draft_version",version).put("module_id","m").put("block_id","").put("name","经历").put("text","新的经历").toString()
    @Test fun `模型工具响应经真实保存后按原调用编号回传并取得后续回复`() {
        val store=CardStore(temporary.newFolder().toPath());val draft=CardDrafts(store).create(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","经历",emptyList()))))
        val policy=CardToolPolicy(setOf(ManagementTarget("card")))
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val request=TextRequest("local-model",listOf(WireMessage("user","完善经历")),512,CardToolProtocol.definitions(policy))
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);var count=0;val received=mutableListOf<JSONObject>()
        server.createContext("/"){exchange ->
            received+=JSONObject(exchange.requestBody.bufferedReader().use { it.readText() });count++
            val message=if(count<=2){
                val name=if(count==1)"read_card" else "write_module_text"
                val arguments=if(count==1)JSONObject().put("root_id","card").put("target_id","card").toString() else {
                    val messages=received.last().getJSONArray("messages")
                    val metadata=JSONObject(messages.getJSONObject(messages.length()-1).getString("content"))
                    args(metadata.getString("draft_version"))
                }
                JSONObject().put("content","").put("tool_calls",org.json.JSONArray().put(JSONObject().put("id","call-$count").put("type","function").put("function",JSONObject().put("name",name).put("arguments",arguments))))
            } else JSONObject().put("content","已经保存经历。")
            val response=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",message).put("finish_reason",if(count<=2)"tool_calls" else "stop"))).toString().toByteArray()
            exchange.sendResponseHeaders(200,response.size.toLong());exchange.responseBody.use { it.write(response) }
        };server.start()
        try {
            fun call(input:TextRequest)=ChatCompletionCall(ModelEndpoint(URI("http://127.0.0.1:${server.address.port}/"),null)).execute(input,ModelCapacity(128000),128000){TokenMeasurement(it.toByteArray().size.toLong(),"测试完整请求字节估算",true)}
            val response=call(request) as ModelResult.ToolsRequested
            val round=CardToolRound(coordinator,CardToolReader(store))
            val read=round.consume("chat",request,response,policy) as ToolRoundResult.Continue
            assertTrue(read.outcomes.single().result is CardToolResult.Read);assertNull(store.open("card"))
            val write=call(read.request) as ModelResult.ToolsRequested
            val next=round.consume("chat",read.request,write,policy) as ToolRoundResult.Continue
            val saved=next.outcomes.single().result as CardToolResult.Saved
            assertEquals(saved.revision,store.open("card")!!.revision)
            assertEquals(ModelResult.Reply("已经保存经历。",null,null),call(next.request))
            val last=received[2].getJSONArray("messages");val tool=last.getJSONObject(last.length()-1)
            assertEquals("tool",tool.getString("role"));assertEquals("call-2",tool.getString("tool_call_id"))
            assertEquals(saved.revision,JSONObject(tool.getString("content")).getString("revision"))
            val offered=received[0].getJSONArray("tools")
            val names=(0 until offered.length()).map {offered.getJSONObject(it).getJSONObject("function").getString("name")}
            assertTrue(names.containsAll(listOf("read_card","write_module_text")))
            assertEquals(names.size,names.distinct().size);assertEquals(3,count)
        } finally {server.stop(0)}
    }
    @Test fun `只读不附工具批准停在等待且损坏参数不会写入`() {
        val store=CardStore(temporary.newFolder().toPath());val draft=CardDrafts(store).create(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","经历",emptyList()))))
        val policy=CardToolPolicy(setOf(ManagementTarget("card")),ToolPermission.APPROVAL)
        val coordinator=CardToolCoordinator(store,TurnJournal(temporary.newFolder().toPath()))
        val round=CardToolRound(coordinator,CardToolReader(store))
        val request=TextRequest("model",listOf(WireMessage("user","修改")),512,CardToolProtocol.definitions(policy))
        val call=PendingTool("call","write_module_text",args(draft.version))
        assertTrue(round.consume("chat",request,ModelResult.ToolsRequested("",listOf(call)),policy) is ToolRoundResult.Waiting)
        assertNull(store.open("card"));assertEquals(draft,CardDrafts(store).read("card"))
        val read=request.copy(tools=CardToolProtocol.definitions(policy.copy(permission=ToolPermission.READ_ONLY)))
        assertFalse(JSONObject(read.encode()).has("tools"))
        val bad=JSONObject(args(draft.version)).put("permission","free").toString()
        assertThrows(IllegalArgumentException::class.java){CardToolProtocol.parse("chat",call.copy(arguments=bad))}
        val parsed=CardToolProtocol.parse("chat",call)
        coordinator.select(parsed,true);val saved=coordinator.confirm(parsed,policy) as CardToolResult.Saved
        val resumed=round.consume("chat",request,ModelResult.ToolsRequested("",listOf(call)),policy) as ToolRoundResult.Continue
        assertEquals(saved,resumed.outcomes.single().result);assertEquals(saved.revision,store.open("card")!!.revision)
        resumed.request.encode()

    }
    @Test fun `工具结果缺失错配和重复都不能发送给模型`() {
        val user=WireMessage("user","请求");val assistant=WireMessage("assistant","",listOf(PendingTool("id","x","{}")))
        assertThrows(IllegalArgumentException::class.java){TextRequest("model",listOf(user,assistant),512).encode()}
        assertThrows(IllegalArgumentException::class.java){TextRequest("model",listOf(user,assistant,WireMessage("tool","{}",toolCallId="wrong")),512).encode()}
        val tool=WireMessage("tool","{}",toolCallId="id")
        assertThrows(IllegalArgumentException::class.java){TextRequest("model",listOf(user,assistant,tool,tool),512).encode()}
    }
}
