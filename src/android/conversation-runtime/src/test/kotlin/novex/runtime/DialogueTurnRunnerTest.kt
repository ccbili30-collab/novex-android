package novex.runtime

import com.sun.net.httpserver.HttpServer
import novex.content.*
import novex.conversation.*
import novex.model.*
import novex.storage.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.InetSocketAddress
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

class DialogueTurnRunnerTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun settings()=TurnSettings("local-test",ModelCapacity(128000),128000,1024,"在世界中叙述场景，可扮演多个人物。",
        listOf(SourceSelection("world")),setOf("managed-only"),TriggerWindow(1,setOf(MessageRole.USER)))
    @Test fun `保存用户消息实际带入卡片回复落盘且重开不重复发请求`() {
        val cardStore=CardStore(temporary.newFolder().toPath())
        val ref=cardStore.contents.allocator()()
        cardStore.contents.receive(listOf(ContentTransfer(ContentRef("raw"),ref))){ByteArrayInputStream("港口有一座灯塔".toByteArray())}
        cardStore.save(ContentDocument("world",CardKind.WORLD,"港口",listOf(ContentModule("m","背景",listOf(ContentBlock.Text("b",ref)),use=ModuleUse.Always))),null,ChangeSource.HUMAN,"revision")
        val managedText=cardStore.contents.allocator()()
        cardStore.contents.receive(listOf(ContentTransfer(ContentRef("private"),managedText))){ByteArrayInputStream("管理正文不得作为背景".toByteArray())}
        cardStore.save(ContentDocument("managed-only",CardKind.CHARACTER,"允许管理的角色",listOf(ContentModule("private-module","资料",listOf(ContentBlock.Text("private-block",managedText)),use=ModuleUse.Always))),null,ChangeSource.HUMAN,"managed-revision")
        val directory=temporary.newFolder().toPath();val journal=TurnJournal(directory)
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0);val requests=AtomicInteger();var payload=""
        server.createContext("/"){exchange ->
            requests.incrementAndGet();payload=exchange.requestBody.bufferedReader().use{it.readText()}
            assertEquals("去港口",journal.text(journal.read("chat","turn")!!.input))
            val bytes="""{"choices":[{"message":{"content":"你来到港口。"},"finish_reason":"stop"}]}""".toByteArray()
            exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        };server.start()
        try {
            fun call()=ChatCompletionCall(ModelEndpoint(URI("http://127.0.0.1:${server.address.port}/"),null))
            val runner=DialogueTurnRunner(journal,RequestMaterials(cardStore))
            val result=runner.run("chat","turn","去港口",emptyList(),settings(),call()){TokenMeasurement(it.toByteArray().size.toLong(),"测试字节估算",true)}
            assertTrue(payload.contains("港口有一座灯塔"));assertTrue(payload.contains("managed-only"));assertFalse(payload.contains("管理正文不得作为背景"))
            val reopened=TurnJournal(directory);val saved=reopened.read("chat","turn")!!
            assertEquals(TurnState.FINISHED,saved.state);assertEquals("你来到港口。",JSONObject(reopened.text(saved.outcome!!)).getString("text"))
            assertEquals("revision",JSONObject(reopened.text(saved.trace!!)).getJSONArray("modules").getJSONObject(0).getString("revision"))
            assertEquals(result,runner.run("chat","turn","去港口",emptyList(),settings(),call()){error("不应重新计量或发送")})
            assertEquals(1,requests.get())
        } finally {server.stop(0)}
    }
    @Test fun `运行中断记录保持未确认且新运行器不会自动重发`() {
        val directory=temporary.newFolder().toPath();val journal=TurnJournal(directory)
        journal.enqueue("chat","turn","保留输入");journal.claim("chat","turn")
        val reopened=TurnJournal(directory)
        val runner=DialogueTurnRunner(reopened,RequestMaterials(CardStore(temporary.newFolder().toPath())))
        val result=runner.run("chat","turn","保留输入",emptyList(),settings(),ChatCompletionCall(ModelEndpoint(URI("http://127.0.0.1:1/"),null))){error("不应重新发送")}
        assertEquals(TurnState.RUNNING,result.state);assertEquals("保留输入",reopened.text(result.input));assertNull(result.outcome)
    }
    @Test fun `准备失败仍保留用户输入和明确未发请求结果`() {
        val journal=TurnJournal(temporary.newFolder().toPath())
        val runner=DialogueTurnRunner(journal,RequestMaterials(CardStore(temporary.newFolder().toPath())))
        val result=runner.run("chat","turn","我的输入",emptyList(),settings(),ChatCompletionCall(ModelEndpoint(URI("http://127.0.0.1:1/"),null))){error("不应调用")}
        assertEquals("我的输入",journal.text(result.input));val outcome=JSONObject(journal.text(result.outcome!!))
        assertEquals("preparation_failed",outcome.getString("kind"));assertFalse(outcome.getBoolean("networkAttempted"))
    }
}
