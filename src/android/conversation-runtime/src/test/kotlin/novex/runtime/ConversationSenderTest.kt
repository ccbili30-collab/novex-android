package novex.runtime

import com.sun.net.httpserver.HttpServer
import novex.content.*
import novex.conversation.*
import novex.model.*
import novex.storage.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList

class ConversationSenderTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun `会话发送保存输入实际采用最新资料带入历史且重复发送不再次请求`() {
        val cards=CardStore(temp.newFolder().toPath())
        fun text(value:String):ContentRef {
            val ref=cards.contents.allocator()()
            cards.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){value.byteInputStream()};return ref
        }
        val card=ContentDocument("world",CardKind.WORLD,"港口",listOf(ContentModule("module","背景",listOf(ContentBlock.Text("block",text("旧港口设定"))),use=ModuleUse.Always)))
        val first=cards.save(card,null,ChangeSource.HUMAN,"first")
        val sessions=ConversationSessions(temp.newFolder().toPath(),cards)
        sessions.create("chat","互动",SourceSelection("world"))
        val timeline=ConversationTimeline(temp.newFolder().toPath(),sessions,TurnJournal(temp.newFolder().toPath()))
        val runs=TurnJournal(temp.newFolder().toPath())
        val transcript=ConversationTranscript(timeline,runs)
        val requests=CopyOnWriteArrayList<JSONObject>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/"){exchange->
            requests.add(JSONObject(exchange.requestBody.bufferedReader().use {it.readText()}))
            val body=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content","回复 ${requests.size}")).put("finish_reason","stop"))).toString().toByteArray()
            exchange.sendResponseHeaders(200,body.size.toLong());exchange.responseBody.use {it.write(body)}
        };server.start()
        try {
            val rounds=CardToolRound(CardToolCoordinator(cards,TurnJournal(temp.newFolder().toPath())),CardToolReader(cards))
            val host=PersistentToolDialogue(PausedLoopStore(temp.newFolder().toPath()),runs){ToolDialogueLoop(rounds){ChatCompletionCall(ModelEndpoint(URI("http://127.0.0.1:${server.address.port}/"),null))}}
            val sender=ConversationSender(temp.newFolder().toPath(),sessions,timeline,transcript,host,DialogueRequestBuilder(RequestMaterials(cards)))
            val settings=TurnSettings("local",ModelCapacity(128000),128000,512,"叙述世界",listOf(SourceSelection("world")),emptySet(),TriggerWindow(1,setOf(MessageRole.USER)))
            val policy={CardToolPolicy(emptySet())}
            val measure:(String)->TokenMeasurement={TokenMeasurement(it.toByteArray().size.toLong(),"检查用字节计量",true)}
            val one=sender.send("chat","one","开始",settings,2,policy,measure)
            assertEquals(ReplyState.REPLIED,one.state);assertEquals("回复 1",one.reply)
            assertEquals(one,sender.send("chat","one","开始",settings,2,policy,measure))
            assertEquals(1,requests.size)
            cards.save(card.copy(modules=listOf(card.modules.single().copy(blocks=listOf(ContentBlock.Text("block",text("新港口设定")))))),first.revision,ChangeSource.HUMAN,"second")
            val two=sender.send("chat","two","继续",settings,2,policy,measure)
            assertEquals("回复 2",two.reply)
            assertTrue(requests[0].toString().contains("旧港口设定"))
            assertTrue(requests[1].toString().contains("新港口设定"));assertFalse(requests[1].toString().contains("旧港口设定"))
            val messages=requests[1].getJSONArray("messages")
            assertEquals("开始",messages.getJSONObject(messages.length()-3).getString("content"))
            assertEquals("回复 1",messages.getJSONObject(messages.length()-2).getString("content"))
            assertEquals("继续",messages.getJSONObject(messages.length()-1).getString("content"))
            assertEquals(listOf("one","two"),transcript.read("chat").map {it.id})
            assertThrows(IllegalArgumentException::class.java){sender.send("chat","bad","保留输入",settings.copy(selections=emptyList()),2,policy,measure)}
            assertEquals(2,requests.size)
            assertEquals("保留输入",transcript.read("chat").last().input)
        } finally {server.stop(0)}
    }
}
