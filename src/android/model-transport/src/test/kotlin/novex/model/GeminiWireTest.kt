package novex.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import novex.conversation.ModelCapacity
import novex.conversation.TokenMeasurement
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Gemini 线协议：请求编码（contents/systemInstruction/functionCall·Response/
 * thoughtSignature 降级/thinkingConfig）与 alt=sse 解码（thought 分通道/整块
 * functionCall/inlineData 媒体/干净断流收尾）。
 */
class GeminiWireTest {
    private fun measure(@Suppress("UNUSED_PARAMETER") value:String)=TokenMeasurement(0,"测试直通",true)
    private fun server(block:(HttpServer,ModelEndpoint)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool();server.executor=executor;server.start()
        try { block(server,ModelEndpoint(URI("http://127.0.0.1:${server.address.port}/models/gemini-2.5-flash:streamGenerateContent?alt=sse"),"public",permitQueryParams=true)) }
        finally { server.stop(0);executor.shutdownNow() }
    }
    private fun streamBody(exchange:HttpExchange,pieces:List<String>) {
        exchange.sendResponseHeaders(200,0)
        exchange.responseBody.use { output -> pieces.forEach { output.write(it.toByteArray(Charsets.UTF_8));output.flush() } }
    }

    // ---------------------------------------------------------------------
    // 请求编码
    // ---------------------------------------------------------------------

    @Test fun `system 提升为 systemInstruction 且角色映射 user model`() {
        val request=GeminiGenerateContentRequest(
            model="gemini-2.5-flash",
            messages=listOf(WireMessage("system","系统提示"),WireMessage("user","问"),WireMessage("assistant","答")),
            maxOutputTokens=1024)
        val body=JSONObject(request.encode())
        assertEquals("系统提示",body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        val contents=body.getJSONArray("contents")
        assertEquals(2,contents.length())
        assertEquals("user",contents.getJSONObject(0).getString("role"))
        assertEquals("问",contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("model",contents.getJSONObject(1).getString("role"))
        assertEquals(1024,body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
        assertFalse(body.getJSONObject("generationConfig").has("thinkingConfig"))
        assertFalse(body.getJSONObject("generationConfig").has("responseModalities"))
    }

    @Test fun `带签名工具历史映射 functionCall 与 functionResponse 配对`() {
        val request=GeminiGenerateContentRequest(
            model="gemini-3-pro-preview",
            messages=listOf(
                WireMessage("user","查天气"),
                WireMessage("assistant","",toolCalls=listOf(PendingTool("gemini_1","get_weather","{\"city\":\"上海\"}","sig-abc"))),
                WireMessage("tool","晴 28°C",toolCallId="gemini_1"),
                WireMessage("user","继续")),
            maxOutputTokens=512,
            requiresThoughtSignature=true)
        val body=JSONObject(request.encode())
        val contents=body.getJSONArray("contents")
        // gemini 不合并同角色轮次：user/model/tool(并入 user 角色但独立成轮)/user 各一。
        assertEquals(4,contents.length())
        assertEquals("model",contents.getJSONObject(1).getString("role"))
        val callPart=contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0)
        assertEquals("sig-abc",callPart.getString("thoughtSignature"))
        assertEquals("get_weather",callPart.getJSONObject("functionCall").getString("name"))
        assertEquals("上海",callPart.getJSONObject("functionCall").getJSONObject("args").getString("city"))
        // tool 结果是独立 user 轮：functionResponse.name 取配对调用名。
        val merged=contents.getJSONObject(2)
        assertEquals("user",merged.getString("role"))
        val responsePart=merged.getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("get_weather",responsePart.getString("name"))
        assertEquals("晴 28°C",responsePart.getJSONObject("response").getString("result"))
        assertFalse(responsePart.getJSONObject("response").has("error"))
        assertEquals("继续",contents.getJSONObject(3).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun `缺签名的三系调用与结果双双降级为文本摘要`() {
        val request=GeminiGenerateContentRequest(
            model="gemini-3-pro-preview",
            messages=listOf(
                WireMessage("assistant","",toolCalls=listOf(PendingTool("gemini_1","probe","{\"k\":true}"))),
                WireMessage("tool","探测出错",toolCallId="gemini_1",isError=true)),
            maxOutputTokens=256,
            requiresThoughtSignature=true)
        val body=JSONObject(request.encode())
        val contents=body.getJSONArray("contents")
        val callText=contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(callText.startsWith("[Called probe with: "))
        assertTrue(callText.contains("\"k\":true"))
        val resultText=contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(resultText.startsWith("[Error from probe: "))
        // 空 key 的历史模型不做签名要求：照常 functionCall/functionResponse。
        val legacy=GeminiGenerateContentRequest(
            model="gemini-2.5-flash",
            messages=listOf(
                WireMessage("assistant","",toolCalls=listOf(PendingTool("gemini_1","probe","{}"))),
                WireMessage("tool","结果",toolCallId="gemini_1")),
            maxOutputTokens=256,requiresThoughtSignature=false)
        val legacyBody=JSONObject(legacy.encode())
        assertEquals("functionCall",legacyBody.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).keys().asSequence().first())
    }

    @Test fun `错误工具结果落 error 标记且空内容补空格`() {
        val request=GeminiGenerateContentRequest(
            model="gemini-2.5-flash",
            messages=listOf(
                WireMessage("assistant","",toolCalls=listOf(PendingTool("gemini_1","probe","{}"))),
                WireMessage("tool","",toolCallId="gemini_1",isError=true)),
            maxOutputTokens=256)
        val response=JSONObject(request.encode()).getJSONArray("contents").getJSONObject(1)
            .getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getJSONObject("response")
        assertTrue(response.getBoolean("error"))
        assertEquals(" ",response.getString("result"))
    }

    @Test fun `图片编码 inlineData 与空轮占位`() {
        val request=GeminiGenerateContentRequest(
            model="gemini-2.5-flash",
            messages=listOf(
                WireMessage("user","看图",images=listOf(WireImage("image/png","aGk="))),
                WireMessage("user","",images=listOf(WireImage("image/png","aGk=")))),
            maxOutputTokens=256)
        val contents=JSONObject(request.encode()).getJSONArray("contents")
        // 连续 user 各成一轮（gemini 不要求交替，无需合并）。
        assertEquals(2,contents.length())
        val parts=contents.getJSONObject(0).getJSONArray("parts")
        assertEquals("看图",parts.getJSONObject(0).getString("text"))
        assertEquals("image/png",parts.getJSONObject(1).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("aGk=",parts.getJSONObject(1).getJSONObject("inlineData").getString("data"))
        // 空文本 + 只有图片：文本块省略，parts 非空不触发占位。
        assertEquals(1,contents.getJSONObject(1).getJSONArray("parts").length())
    }

    @Test fun `thinkingConfig 模态声明与工具声明的大小写和排序`() {
        val request=GeminiGenerateContentRequest(
            model="gemini-2.5-flash-image",
            messages=listOf(WireMessage("user","画")),
            maxOutputTokens=1024,
            tools=listOf(ToolDefinition("draw","画图",
                "{\"type\":\"object\",\"properties\":{\"style\":{\"type\":\"string\"}},\"required\":[\"style\"]}",
                propertyOrdering=listOf("style"))),
            thinkingConfig=JSONObject().put("thinkingBudget",0),
            responseModalities=listOf("TEXT","IMAGE"))
        val body=JSONObject(request.encode())
        val config=body.getJSONObject("generationConfig")
        assertEquals(0,config.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertEquals("TEXT",config.getJSONArray("responseModalities").getString(0))
        assertEquals("IMAGE",config.getJSONArray("responseModalities").getString(1))
        val declaration=body.getJSONArray("tools").getJSONObject(0).getJSONArray("function_declarations").getJSONObject(0)
        assertEquals("draw",declaration.getString("name"))
        assertEquals("OBJECT",declaration.getJSONObject("parameters").getString("type"))
        assertEquals("STRING",declaration.getJSONObject("parameters").getJSONObject("properties").getJSONObject("style").getString("type"))
        assertEquals("style",declaration.getJSONObject("parameters").getJSONArray("propertyOrdering").getString(0))
    }

    @Test fun `音频输出机型拒收 systemInstruction`() {
        val request=GeminiGenerateContentRequest(
            model="gemini-2.5-flash-tts",
            messages=listOf(WireMessage("system","不复述"),WireMessage("user","念")),
            maxOutputTokens=512,
            rejectsSystemInstruction=true,
            responseModalities=listOf("AUDIO"))
        val body=JSONObject(request.encode())
        assertFalse(body.has("systemInstruction"))
        assertEquals("AUDIO",body.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
    }

    // ---------------------------------------------------------------------
    // SSE 解码（alt=sse，无哨兵）
    // ---------------------------------------------------------------------

    @Test fun `thought 与文本分通道 用量同批送达且干净断流收尾`()=server { server,endpoint ->
        var received=""
        server.createContext("/") { exchange ->
            received=exchange.requestBody.bufferedReader().use { it.readText() }
            streamBody(exchange,listOf(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"想一下\",\"thought\":true}]}}]}\n",
                "\r\n",
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"答\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":3}}\n\n"))
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        val result=ChatCompletionCall(endpoint,protocol=WireProtocol.GEMINI_GENERATE_CONTENT)
            .stream(GeminiGenerateContentRequest("gemini-2.5-flash",listOf(WireMessage("user","问")),256),
                ModelCapacity(128_000),128_000,::measure){chunks.add(it)}
        // 无哨兵协议：干净断流即正常收尾，Done 由流尾合成。
        assertEquals(StreamResult.Completed,result)
        val delivered=chunks.toList()
        assertEquals(listOf(
            StreamChunk.ThinkingDelta("想一下"),
            StreamChunk.TextDelta("答"),
            StreamChunk.Usage(12,3),
            StreamChunk.Done("end_turn")),delivered)
        val body=JSONObject(received)
        assertEquals(1,body.getJSONArray("contents").length())
        assertEquals("user",body.getJSONArray("contents").getJSONObject(0).getString("role"))
        assertEquals(256,body.getJSONObject("generationConfig").getInt("maxOutputTokens"))
        assertFalse(body.has("stream")) // 流式语义在 URL 的 alt=sse，不在请求体
    }

    @Test fun `functionCall 整块合成 id 与签名并进聚合器`() {
        val decoder=GeminiSseDecoder()
        val events=decoder.feed(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"get_weather\",\"args\":{\"city\":\"上海\"}},\"thoughtSignature\":\"sig-1\"}]}}]}\n\n" +
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"完\"}]},\"finishReason\":\"STOP\"}]}\n\n")
        assertEquals(2,events.size)
        val call=events[0] as StreamChunk.ToolCallDelta
        assertEquals(0,call.index)
        assertTrue(call.id!!.startsWith("gemini_"))
        assertEquals("get_weather",call.name)
        assertEquals("{\"city\":\"上海\"}",call.argumentsDelta)
        assertEquals("sig-1",call.signature)
        assertEquals(listOf(StreamChunk.TextDelta("完")),decoder.feed("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"完\"}]}}]}\n\n"))
        val done=decoder.finish()
        assertEquals(listOf(StreamChunk.Done("end_turn")),done)
        val assembled=StreamAssembler().also { (events+done).forEach(it::accept) }
        assertEquals(1,assembled.toolCalls.size)
        assertEquals("sig-1",assembled.toolCalls.single().thoughtSignature)
    }

    @Test fun `usageMetadata 与 MAX_TOKENS SAFETY 收尾映射`() {
        val decoder=GeminiSseDecoder()
        assertEquals(listOf(StreamChunk.TextDelta("截"),StreamChunk.Usage(7,2)),decoder.feed(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"截\"}]},\"finishReason\":\"MAX_TOKENS\"}],\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":2}}\n\n"))
        assertEquals(listOf(StreamChunk.Done("max_tokens")),decoder.finish())
        val safety=GeminiSseDecoder()
        safety.feed("data: {\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"SAFETY\"}]}\n\n")
        assertEquals(listOf(StreamChunk.Done("safety")),safety.finish())
    }

    @Test fun `inlineData 产出媒体块且按 mime 归类留档`() {
        val decoder=GeminiSseDecoder()
        val events=decoder.feed(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"aGk=\"}}]}}]}\n\n")
        assertEquals(listOf(StreamChunk.MediaAttachment("image/png","aGk=")),events)
        val assembled=StreamAssembler().also { events.forEach(it::accept) }
        assertEquals(1,assembled.media.size)
        assertEquals("image/png",assembled.media.single().mimeType)
    }

    @Test fun `干净断流未见 finishReason 时缺省 end_turn`() {
        val decoder=GeminiSseDecoder()
        assertEquals(listOf(StreamChunk.TextDelta("答")),decoder.feed(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"答\"}]}}]}\n\n"))
        // 对齐被替换实现：Finished(lastFinishReason ?: "end_turn")——无 finishReason 的
        // 干净关流是正常收尾而非网络失败。
        assertEquals(listOf(StreamChunk.Done("end_turn")),decoder.finish())
    }

    @Test fun `HTTP 400 错误体解析出消息与 api 状态`() {
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool();server.executor=executor;server.start()
        try {
            val endpoint=ModelEndpoint(URI("http://127.0.0.1:${server.address.port}/models/gemini-2.5-flash:streamGenerateContent?alt=sse"),"public",permitQueryParams=true)
            server.createContext("/") { exchange ->
                val body="{\"error\":{\"code\":400,\"message\":\"API key not valid\",\"status\":\"INVALID_ARGUMENT\"}}"
                val bytes=body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(400,bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            val chunks=ConcurrentLinkedQueue<StreamChunk>()
            assertEquals(StreamResult.Failed,ChatCompletionCall(endpoint,protocol=WireProtocol.GEMINI_GENERATE_CONTENT)
                .stream(GeminiGenerateContentRequest("gemini-2.5-flash",listOf(WireMessage("user","问")),64),
                    ModelCapacity(128_000),128_000,::measure){chunks.add(it)})
            assertEquals(listOf(StreamChunk.Failure("HTTP 400: API key not valid",400,"request","INVALID_ARGUMENT",null)),chunks.toList())
        } finally { server.stop(0);executor.shutdownNow() }
    }

    @Test fun `流中 error 对象与半行容错`() {
        val decoder=GeminiSseDecoder()
        assertEquals(listOf(StreamChunk.Failure("配额耗尽",status=429,code="RESOURCE_EXHAUSTED")),
            decoder.feed("data: {\"error\":{\"code\":429,\"message\":\"配额耗尽\",\"status\":\"RESOURCE_EXHAUSTED\"}}\n\n"))
        assertTrue(decoder.sawFailure())
        assertTrue(decoder.feed("data: {\"candidates\":[]}\n\n").isEmpty())
        // 半行跨喂入拼接；注释与事件字段行不产事件。
        val tolerant=GeminiSseDecoder()
        assertTrue(tolerant.feed("data: {\"candidates\":[{\"content\":{\"par").isEmpty())
        assertEquals(listOf(StreamChunk.TextDelta("拼")),tolerant.feed("ts\":[{\"text\":\"拼\"}]}}]}\r\n\r\n"))
        assertTrue(tolerant.feed(": keep-alive\n").isEmpty())
        assertTrue(tolerant.feed("data: 不是JSON\n").isEmpty())
    }
}
