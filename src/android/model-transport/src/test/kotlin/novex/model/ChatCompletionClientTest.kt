package novex.model

import com.sun.net.httpserver.HttpServer
import novex.conversation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatCompletionClientTest {
    private val capacity=ModelCapacity(128_000)
    private val request=TextRequest("test-model",listOf(WireMessage("system","明确资料用途"),WireMessage("user","保留原文\n\"你好\"")),1024)
    private fun measure(value:String)=TokenMeasurement(value.toByteArray().size.toLong(),"测试用字节估算，非模型分词",true)
    private fun server(block:(HttpServer,ModelEndpoint)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool();server.executor=executor;server.start()
        try { block(server,ModelEndpoint(URI("http://127.0.0.1:${server.address.port}/chat/completions"),"public")) }
        finally { server.stop(0);executor.shutdownNow() }
    }
    @Test fun `真实本地传输保持消息并解析回复用量且不会重复执行`()=server { server,endpoint ->
        val calls=AtomicInteger();var received=""
        server.createContext("/chat/completions") { exchange ->
            calls.incrementAndGet();received=exchange.requestBody.bufferedReader().use { it.readText() }
            val bytes="""{"choices":[{"message":{"content":"正常回复"},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":4}}""".toByteArray()
            exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use { it.write(bytes) }
        }
        val call=ChatCompletionCall(endpoint)
        assertEquals(ModelResult.Reply("正常回复",20,4),call.execute(request,capacity,128_000,::measure))
        val encoded=JSONObject(received);assertEquals(request.messages[1].text,encoded.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertFalse(encoded.getBoolean("stream"));assertEquals(1024,encoded.getInt("max_tokens"))
        assertThrows(IllegalStateException::class.java){call.execute(request,capacity,128_000,::measure)}
        assertEquals(1,calls.get())
    }
    @Test fun `超容量或事先取消不产生任何请求`()=server { server,endpoint ->
        val calls=AtomicInteger();server.createContext("/"){calls.incrementAndGet();it.close()}
        val blocked=ChatCompletionCall(endpoint).execute(request,capacity,2048){TokenMeasurement(2048,"测试计量",false)}
        assertTrue(blocked is ModelResult.NotSent)
        val call=ChatCompletionCall(endpoint);call.cancel();assertEquals(ModelResult.Cancelled,call.execute(request,capacity,128_000,::measure))
        assertEquals(0,calls.get())
    }
    @Test fun `限流保留重试时点且不自动重试`()=server { server,endpoint ->
        val calls=AtomicInteger();server.createContext("/"){exchange ->
            calls.incrementAndGet();exchange.responseHeaders.set("Retry-After","60");exchange.sendResponseHeaders(429,-1);exchange.close()
        }
        assertEquals(ModelResult.Rejected(429,"rate_limit","60"),ChatCompletionCall(endpoint).execute(request,capacity,128_000,::measure))
        assertEquals(1,calls.get())
    }
    @Test fun `截断与工具调用不能伪装成普通完成`()=server { server,endpoint ->
        var response="""{"choices":[{"message":{"content":"未完成文字"},"finish_reason":"length"}]}"""
        server.createContext("/"){exchange ->val bytes=response.toByteArray();exchange.sendResponseHeaders(200,bytes.size.toLong());exchange.responseBody.use{it.write(bytes)}}
        assertEquals(ModelResult.Partial("未完成文字","length"),ChatCompletionCall(endpoint).execute(request,capacity,128_000,::measure))
        response="""{"choices":[{"message":{"content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"save_card","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        val tools=ChatCompletionCall(endpoint).execute(request,capacity,128_000,::measure) as ModelResult.ToolsRequested
        assertEquals("save_card",tools.calls.single().name)
        response="""{"choices":[{"message":{"content":"看似成功","tool_calls":"损坏的工具字段"},"finish_reason":"stop"}]}"""
        assertTrue(ChatCompletionCall(endpoint).execute(request,capacity,128_000,::measure) is ModelResult.InvalidResponse)
    }
    @Test fun `等待服务期间可取消且未返回正常完成`()=server { server,endpoint ->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        server.createContext("/"){exchange ->entered.countDown();release.await(3,TimeUnit.SECONDS);exchange.close()}
        val executor=Executors.newSingleThreadExecutor();val call=ChatCompletionCall(endpoint,2000)
        try {
            val pending=executor.submit<ModelResult>{call.execute(request,capacity,128_000,::measure)}
            assertTrue(entered.await(2,TimeUnit.SECONDS));call.cancel();release.countDown()
            assertEquals(ModelResult.Cancelled,pending.get(3,TimeUnit.SECONDS))
        } finally { release.countDown();executor.shutdownNow() }
    }
}
