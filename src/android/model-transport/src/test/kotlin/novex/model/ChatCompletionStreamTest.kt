package novex.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import novex.conversation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatCompletionStreamTest {
    private val capacity=ModelCapacity(128_000)
    private val request=TextRequest("test-model",listOf(WireMessage("system","明确资料用途"),WireMessage("user","保留原文\n\"你好\"")),1024)
    private val payload=StreamRequest(request)
    private fun measure(value:String)=TokenMeasurement(value.toByteArray().size.toLong(),"测试用字节估算，非模型分词",true)
    private fun server(block:(HttpServer,ModelEndpoint)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool();server.executor=executor;server.start()
        try { block(server,ModelEndpoint(URI("http://127.0.0.1:${server.address.port}/chat/completions"),"public")) }
        finally { server.stop(0);executor.shutdownNow() }
    }
    /** 以分块方式写出事件流，模拟真实网络分段送达。 */
    private fun streamBody(exchange:HttpExchange,pieces:List<String>) {
        exchange.sendResponseHeaders(200,0)
        exchange.responseBody.use { output -> pieces.forEach { output.write(it.toByteArray(Charsets.UTF_8));output.flush() } }
    }
    private fun assembled(chunks:List<StreamChunk>)=StreamAssembler().also { chunks.forEach(it::accept) }

    @Test fun `纯文本流按序送达增量并在哨兵后收尾`()=server { server,endpoint ->
        val calls=AtomicInteger();var received=""
        server.createContext("/chat/completions") { exchange ->
            calls.incrementAndGet();received=exchange.requestBody.bufferedReader().use { it.readText() }
            streamBody(exchange,listOf(
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":4}}\n\n",
                "data: [DONE]\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"哨兵后不应出现\"}}]}\n\n"))
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        val call=ChatCompletionCall(endpoint)
        val result=call.stream(payload,capacity,128_000,::measure) { chunks.add(it) }
        val delivered=chunks.toList()
        assertEquals(listOf(StreamChunk.TextDelta("你"),StreamChunk.TextDelta("好"),StreamChunk.Usage(20,4),StreamChunk.Done("stop")),delivered)
        assertEquals(StreamResult.Completed,result)
        val outcome=assembled(delivered)
        assertEquals("你好",outcome.text);assertEquals(StreamChunk.Usage(20,4),outcome.usage)
        assertEquals("stop",outcome.finishReason);assertNull(outcome.failure)
        val encoded=JSONObject(received)
        assertEquals(request.messages[1].text,encoded.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertTrue(encoded.getBoolean("stream"));assertEquals(1024,encoded.getInt("max_tokens"))
        assertTrue(encoded.getJSONObject("stream_options").getBoolean("include_usage"))
        assertThrows(IllegalStateException::class.java){call.stream(payload,capacity,128_000,::measure){}}
        assertEquals(1,calls.get())
    }
    @Test fun `思考与文本交错流分通道送达`()=server { server,endpoint ->
        server.createContext("/chat/completions") { exchange ->
            streamBody(exchange,listOf(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想\"}}]}\n",
                "\r\n",
                "data:{\"choices\":[{\"delta\":{\"reasoning_content\":\"清楚\"}}]}\r\n\r\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"答复\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"补充\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"在此\"}}]}\n\n",
                "data: [DONE]\n\n"))
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        assertEquals(StreamResult.Completed,ChatCompletionCall(endpoint).stream(payload,capacity,128_000,::measure){chunks.add(it)})
        val delivered=chunks.toList()
        assertEquals(listOf(StreamChunk.ThinkingDelta("先想"),StreamChunk.ThinkingDelta("清楚"),StreamChunk.TextDelta("答复"),
            StreamChunk.ThinkingDelta("补充"),StreamChunk.TextDelta("在此"),StreamChunk.Done(null)),delivered)
        val outcome=assembled(delivered)
        assertEquals("先想清楚补充",outcome.thinking);assertEquals("答复在此",outcome.text)
    }
    @Test fun `工具调用分片跨块拼装出完整调用`()=server { server,endpoint ->
        server.createContext("/chat/completions") { exchange ->
            streamBody(exchange,listOf(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"save_card\",\"arguments\":\"{\\\"title\\\":\"}}]}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"灯塔\\\"\"}}]}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call-2\",\"type\":\"function\",\"function\":{\"name\":\"read_notes\",\"arguments\":\"{}\"}}]}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\",\\\"urgent\\\":true}\"}}]}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
                "data: [DONE]\n\n"))
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        assertEquals(StreamResult.Completed,ChatCompletionCall(endpoint).stream(payload,capacity,128_000,::measure){chunks.add(it)})
        val delivered=chunks.toList()
        assertEquals(StreamChunk.ToolCallDelta(0,"call-1","save_card","{\"title\":"),delivered[0])
        assertEquals(StreamChunk.ToolCallDelta(0,null,null,"\"灯塔\""),delivered[1])
        val outcome=assembled(delivered)
        assertEquals(listOf(PendingTool("call-1","save_card","{\"title\":\"灯塔\",\"urgent\":true}"),
            PendingTool("call-2","read_notes","{}")),outcome.toolCalls)
        assertEquals("tool_calls",outcome.finishReason)
    }
    @Test fun `关掉用量选项则请求不带 stream_options 但服务端用量仍送达`()=server { server,endpoint ->
        var received=""
        server.createContext("/chat/completions") { exchange ->
            received=exchange.requestBody.bufferedReader().use { it.readText() }
            streamBody(exchange,listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"短答\"}}]}\n\n",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2}}\n\n",
                "data: [DONE]\n\n"))
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        assertEquals(StreamResult.Completed,
            ChatCompletionCall(endpoint).stream(StreamRequest(request,includeUsage=false),capacity,128_000,::measure){chunks.add(it)})
        val encoded=JSONObject(received)
        assertTrue(encoded.getBoolean("stream"));assertFalse(encoded.has("stream_options"))
        // 开关只管请求体；服务端给了用量就照常送达。
        assertEquals(listOf(StreamChunk.TextDelta("短答"),StreamChunk.Usage(9,2),StreamChunk.Done(null)),chunks.toList())
    }
    @Test fun `HTTP 非 200 归类为失败块且不进入流解析`() {
        server { server,endpoint ->
            server.createContext("/"){it.sendResponseHeaders(401,-1);it.close()}
            val chunks=ConcurrentLinkedQueue<StreamChunk>()
            assertEquals(StreamResult.Failed,ChatCompletionCall(endpoint).stream(payload,capacity,128_000,::measure){chunks.add(it)})
            assertEquals(listOf(StreamChunk.Failure("HTTP 401",401,"authentication",null,null)),chunks.toList())
        }
        server { server,endpoint ->
            server.createContext("/"){exchange ->exchange.responseHeaders.set("Retry-After","60");exchange.sendResponseHeaders(429,-1);exchange.close()}
            val chunks=ConcurrentLinkedQueue<StreamChunk>()
            assertEquals(StreamResult.Failed,ChatCompletionCall(endpoint).stream(payload,capacity,128_000,::measure){chunks.add(it)})
            assertEquals(listOf(StreamChunk.Failure("HTTP 429",429,"rate_limit",null,"60")),chunks.toList())
        }
        server { server,endpoint ->
            server.createContext("/"){it.sendResponseHeaders(500,-1);it.close()}
            val chunks=ConcurrentLinkedQueue<StreamChunk>()
            assertEquals(StreamResult.Failed,ChatCompletionCall(endpoint).stream(payload,capacity,128_000,::measure){chunks.add(it)})
            assertEquals(listOf(StreamChunk.Failure("HTTP 500",500,"service",null,null)),chunks.toList())
        }
    }
    @Test fun `流中途 error 对象立即失败且其后数据被忽略`()=server { server,endpoint ->
        server.createContext("/chat/completions") { exchange ->
            streamBody(exchange,listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"开头\"}}]}\n\n",
                "data: {\"error\":{\"code\":\"insufficient_quota\",\"message\":\"额度耗尽\"}}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"不应出现\"}}]}\n\n",
                "data: [DONE]\n\n"))
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        assertEquals(StreamResult.Failed,ChatCompletionCall(endpoint).stream(payload,capacity,128_000,::measure){chunks.add(it)})
        assertEquals(listOf(StreamChunk.TextDelta("开头"),StreamChunk.Failure("额度耗尽",code="insufficient_quota")),chunks.toList())
    }
    @Test fun `读到一半取消立即断开且返回已取消`()=server { server,endpoint ->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        server.createContext("/chat/completions") { exchange ->
            entered.countDown()
            exchange.sendResponseHeaders(200,0)
            exchange.responseBody.use { output ->
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\"开头\"}}]}\n\n".toByteArray(Charsets.UTF_8));output.flush()
                release.await(3,TimeUnit.SECONDS)
            }
        }
        val preCancelled=ChatCompletionCall(endpoint);preCancelled.cancel()
        assertEquals(StreamResult.Cancelled,preCancelled.stream(payload,capacity,128_000,::measure){})
        val firstChunk=CountDownLatch(1)
        val call=ChatCompletionCall(endpoint,5000)
        val executor=Executors.newSingleThreadExecutor()
        try {
            val pending=executor.submit<StreamResult> { call.stream(payload,capacity,128_000,::measure){firstChunk.countDown()} }
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(firstChunk.await(2,TimeUnit.SECONDS))
            call.cancel()
            assertEquals(StreamResult.Cancelled,pending.get(3,TimeUnit.SECONDS))
        } finally { release.countDown();executor.shutdownNow() }
    }
    @Test fun `不发哨兵但已见 finish_reason 的流在流尾收尾`()=server { server,endpoint ->
        server.createContext("/chat/completions") { exchange ->
            streamBody(exchange,listOf(
                "data: {\"choices\":[{\"delta\":{\"content\":\"答复\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"))
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        assertEquals(StreamResult.Completed,ChatCompletionCall(endpoint).stream(payload,capacity,128_000,::measure){chunks.add(it)})
        assertEquals(listOf(StreamChunk.TextDelta("答复"),StreamChunk.Done("stop")),chunks.toList())
    }
    @Test fun `无任何终止信号的断流按网络失败处理`()=server { server,endpoint ->
        server.createContext("/chat/completions") { exchange ->
            streamBody(exchange,listOf("data: {\"choices\":[{\"delta\":{\"content\":\"半截\"}}]}\n\n"))
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        assertEquals(StreamResult.NetworkFailure,ChatCompletionCall(endpoint).stream(payload,capacity,128_000,::measure){chunks.add(it)})
        assertEquals(listOf(StreamChunk.TextDelta("半截")),chunks.toList())
    }
    @Test fun `读超时返回超时且不误报取消`()=server { server,endpoint ->
        val release=CountDownLatch(1)
        server.createContext("/chat/completions") { exchange ->
            exchange.sendResponseHeaders(200,0)
            exchange.responseBody.use { output ->
                output.write("data: {\"choices\":[{\"delta\":{\"content\":\"先到\"}}]}\n\n".toByteArray(Charsets.UTF_8));output.flush()
                release.await(3,TimeUnit.SECONDS)
            }
        }
        val received=ConcurrentLinkedQueue<StreamChunk>()
        try { assertEquals(StreamResult.TimedOut,ChatCompletionCall(endpoint,1000).stream(payload,capacity,128_000,::measure){received.add(it)}) }
        finally { release.countDown() }
        assertEquals(listOf(StreamChunk.TextDelta("先到")),received.toList())
    }
    @Test fun `容量超限不发送任何流式请求`()=server { server,endpoint ->
        val calls=AtomicInteger();server.createContext("/"){calls.incrementAndGet();it.close()}
        val outcome=ChatCompletionCall(endpoint).stream(payload,capacity,2048,{TokenMeasurement(2048,"测试计量",false)},{})
        assertTrue(outcome is StreamResult.NotSent)
        assertEquals(0,calls.get())
    }
    @Test fun `半行跨喂入拼接而畸形行全部丢弃不崩不挂`() {
        // 半行 JSON 跨两次喂入拼接成完整事件。
        val split=SseDecoder()
        assertTrue(split.feed("data: {\"choices\":[{\"delta\":{\"cont").isEmpty())
        assertEquals(listOf(StreamChunk.TextDelta("拼")),split.feed("ent\":\"拼\"}}]}\r\n\r\n"))
        // 注释（心跳）、空行、event 字段行、非 JSON data 行、坏 JSON data 行：全部无事件。
        val tolerant=SseDecoder()
        assertTrue(tolerant.feed(": keep-alive\n\n").isEmpty())
        assertTrue(tolerant.feed("event: message\n").isEmpty())
        assertTrue(tolerant.feed("data: 不是JSON\n").isEmpty())
        assertTrue(tolerant.feed("data: {\"choices\": truncated\n").isEmpty())
        // 哨兵跨喂入拼接，终态之后的一切输入被忽略。
        assertTrue(tolerant.feed("data: [DON").isEmpty())
        assertEquals(listOf(StreamChunk.Done(null)),tolerant.feed("E]\n"))
        assertTrue(tolerant.feed("data: {\"choices\":[{\"delta\":{\"content\":\"之后\"}}]}\n").isEmpty())
        assertTrue(tolerant.finish().isEmpty())
        // EOF 残留半行 JSON：finish 冲掉且不产生收尾（从未见 finish_reason）。
        val truncated=SseDecoder()
        assertTrue(truncated.feed("data: {\"choices").isEmpty())
        assertTrue(truncated.finish().isEmpty())
    }
    @Test fun `reasoning 字段别名与空用量对象不产生事件`() {
        val decoder=SseDecoder()
        assertEquals(listOf(StreamChunk.ThinkingDelta("别名")),decoder.feed("data: {\"choices\":[{\"delta\":{\"reasoning\":\"别名\"}}]}\n\n"))
        assertTrue(decoder.feed("data: {\"choices\":[],\"usage\":{}}\n\n").isEmpty())
        assertTrue(decoder.feed("data: {\"choices\":[{\"delta\":{\"content\":null}}]}\n\n").isEmpty())
    }
}
