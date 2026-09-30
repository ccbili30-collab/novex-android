package novex.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import novex.conversation.ModelCapacity
import novex.conversation.TokenMeasurement
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Anthropic Messages 线协议：请求编码（system 块/工具历史/思考形态/缓存断点/
 * OAuth 前缀拆分）与 SSE 事件解码（六类块映射/错误/无哨兵断流）。
 */
class AnthropicWireTest {
    private fun measure(@Suppress("UNUSED_PARAMETER") value:String)=TokenMeasurement(0,"测试直通",true)
    private fun server(path:String,block:(HttpServer,ModelEndpoint)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool();server.executor=executor;server.start()
        try { block(server,ModelEndpoint(URI("http://127.0.0.1:${server.address.port}$path"),"public")) }
        finally { server.stop(0);executor.shutdownNow() }
    }
    private fun streamBody(exchange:HttpExchange,pieces:List<String>) {
        exchange.sendResponseHeaders(200,0)
        exchange.responseBody.use { output -> pieces.forEach { output.write(it.toByteArray(Charsets.UTF_8));output.flush() } }
    }

    // ---------------------------------------------------------------------
    // 请求编码
    // ---------------------------------------------------------------------

    @Test fun `system 提升为顶层缓存块且消息序列只含对话轮`() {
        val request=AnthropicMessagesRequest(
            model="claude-sonnet-4-5",
            messages=listOf(WireMessage("system","系统提示"),WireMessage("user","问"),WireMessage("assistant","答")),
            maxTokens=1024)
        val body=JSONObject(request.encode())
        assertEquals("claude-sonnet-4-5",body.getString("model"))
        assertEquals(1024,body.getInt("max_tokens"))
        assertTrue(body.getBoolean("stream"))
        val system=body.getJSONArray("system")
        assertEquals(1,system.length())
        assertEquals("text",system.getJSONObject(0).getString("type"))
        assertEquals("系统提示",system.getJSONObject(0).getString("text"))
        assertEquals("ephemeral",system.getJSONObject(0).getJSONObject("cache_control").getString("type"))
        assertNull(system.getJSONObject(0).getJSONObject("cache_control").opt("ttl"))
        val messages=body.getJSONArray("messages")
        assertEquals(2,messages.length())
        assertEquals("user",messages.getJSONObject(0).getString("role"))
        assertEquals("答",messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(body.has("temperature"))
        assertFalse(body.has("thinking"))
    }

    @Test fun `legacy 机型思考开启走 budget 形态并强制 temperature 一`() {
        val request=AnthropicMessagesRequest(
            model="claude-sonnet-4-5",
            messages=listOf(WireMessage("user","想清楚")),
            maxTokens=8192,
            thinkingLevel=WireThinkingLevel.MEDIUM,
            supportsReasoning=true)
        val body=JSONObject(request.encode())
        assertEquals("enabled",body.getJSONObject("thinking").getString("type"))
        // budget_tokens 必须严格小于 max_tokens：8192 档被钳到 8191。
        assertEquals(8191,body.getJSONObject("thinking").getInt("budget_tokens"))
        assertEquals(1.0,body.getDouble("temperature"),0.0)
    }

    @Test fun `四点六后机型思考走 adaptive 形态且不带 temperature`() {
        val request=AnthropicMessagesRequest(
            model="claude-sonnet-4-6",
            messages=listOf(WireMessage("user","想")),
            maxTokens=4096,
            thinkingLevel=WireThinkingLevel.HIGH,
            supportsReasoning=true)
        val body=JSONObject(request.encode())
        assertEquals("adaptive",body.getJSONObject("thinking").getString("type"))
        assertEquals("summarized",body.getJSONObject("thinking").getString("display"))
        assertEquals("high",body.getJSONObject("output_config").getString("effort"))
        assertFalse(body.has("temperature"))
    }

    @Test fun `adaptive 机型关思考显式发 disabled`() {
        val request=AnthropicMessagesRequest(
            model="claude-opus-5",
            messages=listOf(WireMessage("user","问")),
            maxTokens=2048)
        val body=JSONObject(request.encode())
        assertEquals("disabled",body.getJSONObject("thinking").getString("type"))
    }

    @Test fun `工具定义映射 input_schema 并挂 eager 流式与末位缓存`() {
        val request=AnthropicMessagesRequest(
            model="claude-sonnet-4-5",
            messages=listOf(WireMessage("user","查")),
            maxTokens=512,
            tools=listOf(
                ToolDefinition("get_weather","查天气","{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}"),
                ToolDefinition("save_card","存卡","{\"type\":\"object\",\"properties\":{}}")))
        val body=JSONObject(request.encode())
        val tools=body.getJSONArray("tools")
        assertEquals(2,tools.length())
        val first=tools.getJSONObject(0)
        assertEquals("get_weather",first.getString("name"))
        assertEquals("object",first.getJSONObject("input_schema").getString("type"))
        assertTrue(first.getBoolean("eager_input_streaming"))
        assertFalse(first.has("cache_control"))
        assertEquals("ephemeral",tools.getJSONObject(1).getJSONObject("cache_control").getString("type"))
        assertEquals("auto",body.getJSONObject("tool_choice").getString("type"))
    }

    @Test fun `工具历史映射 tool_use 与 tool_result 且同角色轮次合并`() {
        val request=AnthropicMessagesRequest(
            model="claude-sonnet-4-5",
            messages=listOf(
                WireMessage("user","查天气"),
                WireMessage("assistant","",toolCalls=listOf(
                    PendingTool("call-1","get_weather","{\"city\":\"上海\"}"),
                    PendingTool("bad|id","probe","{}"))),
                WireMessage("tool","晴 28°C",toolCallId="call-1"),
                WireMessage("tool","探测失败",toolCallId="bad|id",isError=true),
                WireMessage("user","继续")),
            maxTokens=512)
        val body=JSONObject(request.encode())
        val messages=body.getJSONArray("messages")
        // user(查天气) + assistant(tool_use×2) + user(tool_result×2+继续) —— 连续 user 合并。
        assertEquals(3,messages.length())
        val assistant=messages.getJSONObject(1)
        assertEquals("assistant",assistant.getString("role"))
        assertEquals(2,assistant.getJSONArray("content").length())
        val use=assistant.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use",use.getString("type"))
        assertEquals("call-1",use.getString("id"))
        assertEquals("上海",use.getJSONObject("input").getString("city"))
        // Anthropic 工具 id 只收字母数字下划线连字符：管道折叠为连字符。
        assertEquals("bad-id",assistant.getJSONArray("content").getJSONObject(1).getString("id"))
        val merged=messages.getJSONObject(2)
        assertEquals("user",merged.getString("role"))
        val blocks=merged.getJSONArray("content")
        assertEquals(3,blocks.length())
        val result=blocks.getJSONObject(0)
        assertEquals("tool_result",result.getString("type"))
        assertEquals("call-1",result.getString("tool_use_id"))
        assertEquals("晴 28°C",result.getJSONArray("content").getJSONObject(0).getString("text"))
        assertFalse(result.has("is_error"))
        val failed=blocks.getJSONObject(1)
        assertEquals("tool_result",failed.getString("type"))
        assertEquals("bad-id",failed.getString("tool_use_id"))
        assertTrue(failed.getBoolean("is_error"))
        assertEquals("text",blocks.getJSONObject(2).getString("type"))
    }

    @Test fun `连续 assistant 轮合并后 thinking 文本 tool_use 依次排序`() {
        val request=AnthropicMessagesRequest(
            model="deepseek-v4-anthropic",
            messages=listOf(
                WireMessage("user","问"),
                WireMessage("assistant","先想",reasoningContent="思考片段"),
                WireMessage("assistant","",toolCalls=listOf(PendingTool("call-1","probe","{}"))),
                WireMessage("tool","结果",toolCallId="call-1")),
            maxTokens=512,
            echoUnsignedThinking=true)
        val body=JSONObject(request.encode())
        val messages=body.getJSONArray("messages")
        assertEquals(3,messages.length())
        val merged=messages.getJSONObject(1)
        assertEquals("assistant",merged.getString("role"))
        val blocks=merged.getJSONArray("content")
        // 每条 assistant 各带自己的 thinking 块（第二条无记录补空串占位），合并后
        // thinking 全部前置：thinking(片段)、thinking(占位)、text、tool_use。
        assertEquals(4,blocks.length())
        assertEquals("thinking",blocks.getJSONObject(0).getString("type"))
        assertEquals("思考片段",blocks.getJSONObject(0).getString("thinking"))
        assertEquals("thinking",blocks.getJSONObject(1).getString("type"))
        assertEquals("",blocks.getJSONObject(1).getString("thinking"))
        assertEquals("text",blocks.getJSONObject(2).getString("type"))
        assertEquals("tool_use",blocks.getJSONObject(3).getString("type"))
    }

    @Test fun `用户图片编码为 base64 image 块且缓存断点落最近两条 user 末块`() {
        val request=AnthropicMessagesRequest(
            model="claude-sonnet-4-5",
            messages=listOf(
                WireMessage("user","第一轮"),
                WireMessage("assistant","第一答"),
                WireMessage("user","第二轮"),
                WireMessage("assistant","第二答"),
                WireMessage("user","看图",images=listOf(WireImage("image/png","aGk=")))),
            maxTokens=512)
        val body=JSONObject(request.encode())
        val messages=body.getJSONArray("messages")
        assertEquals(5,messages.length())
        val imageBlock=messages.getJSONObject(4).getJSONArray("content").getJSONObject(1)
        assertEquals("image",imageBlock.getString("type"))
        assertEquals("base64",imageBlock.getJSONObject("source").getString("type"))
        assertEquals("image/png",imageBlock.getJSONObject("source").getString("media_type"))
        assertEquals("aGk=",imageBlock.getJSONObject("source").getString("data"))
        // 最近两条 user（含末轮）末块挂 cache_control，更早的不挂。
        assertTrue(messages.getJSONObject(4).getJSONArray("content").getJSONObject(1).has("cache_control"))
        assertTrue(messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).has("cache_control"))
        assertFalse(messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).has("cache_control"))
    }

    @Test fun `增强缓存把 ttl 一小时带进全部断点`() {
        val request=AnthropicMessagesRequest(
            model="claude-sonnet-4-5",
            messages=listOf(WireMessage("system","系统"),WireMessage("user","问")),
            maxTokens=256,
            cacheTtlOneHour=true)
        val body=JSONObject(request.encode())
        assertEquals("1h",body.getJSONArray("system").getJSONObject(0).getJSONObject("cache_control").getString("ttl"))
        assertEquals("1h",body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).getJSONObject("cache_control").getString("ttl"))
    }

    @Test fun `OAuth 拆前缀块不缓存尾部块缓存且已带前缀时不重复`() {
        val prefix="You are Claude Code, Anthropic's official CLI for Claude."
        val request=AnthropicMessagesRequest(
            model="claude-sonnet-4-6",
            messages=listOf(WireMessage("system","$prefix\n\n用户系统提示"),WireMessage("user","问")),
            maxTokens=256,
            isOAuth=true,
            oauthSystemPrefix=prefix)
        val body=JSONObject(request.encode())
        val system=body.getJSONArray("system")
        assertEquals(2,system.length())
        assertEquals(prefix,system.getJSONObject(0).getString("text"))
        assertFalse(system.getJSONObject(0).has("cache_control"))
        assertEquals("用户系统提示",system.getJSONObject(1).getString("text"))
        assertTrue(system.getJSONObject(1).has("cache_control"))
        // 未带前缀的提示被强制补前缀（OAuth 门禁要求）。
        val bare=AnthropicMessagesRequest(
            model="claude-sonnet-4-6",
            messages=listOf(WireMessage("system","只有尾部"),WireMessage("user","问")),
            maxTokens=256,isOAuth=true,oauthSystemPrefix=prefix)
        val bareSystem=JSONObject(bare.encode()).getJSONArray("system")
        assertEquals(prefix,bareSystem.getJSONObject(0).getString("text"))
        assertEquals("只有尾部",bareSystem.getJSONObject(1).getString("text"))
    }

    @Test fun `消息角色与请求形状的装配校验`() {
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicMessagesRequest("claude-x",emptyList(),16)
        }
        // system 至多一条。
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicMessagesRequest("claude-x",listOf(WireMessage("system","a"),WireMessage("system","b")),16)
        }
        // 工具名重复拒绝。
        assertThrows(IllegalArgumentException::class.java) {
            AnthropicMessagesRequest("claude-x",listOf(WireMessage("user","问")),16,
                tools=listOf(ToolDefinition("t","d","{\"type\":\"object\"}"),ToolDefinition("t","d2","{\"type\":\"object\"}")))
        }
    }

    // ---------------------------------------------------------------------
    // SSE 解码（假服务器 + 分片送达）
    // ---------------------------------------------------------------------

    private val thinkEvents=listOf(
        "event: message_start\n","data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":25,\"output_tokens\":1}}}\n\n",
        "event: content_block_start\n","data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你\"}}\n",
        "\r\n",
        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}\n\n",
        "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\"}}\n\n",
        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}\n\n",
        "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"上海\\\"}\"}}\n\n",
        "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n",
        "event: ping\n","data: {\"type\":\"ping\"}\n\n",
        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}}\n\n",
        "data: {\"type\":\"message_stop\"}\n\n")

    @Test fun `六类事件按序映射为块族且 message_stop 收尾`()=server("/v1/messages") { server,endpoint ->
        val calls=ArrayList<String>()
        server.createContext("/v1/messages") { exchange ->
            calls+=exchange.requestBody.bufferedReader().use { it.readText() }
            streamBody(exchange,thinkEvents)
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        val call=ChatCompletionCall(endpoint,protocol=WireProtocol.ANTHROPIC_MESSAGES)
        val result=call.stream(AnthropicMessagesRequest("claude-sonnet-4-5",
            listOf(WireMessage("user","查天气")),256),ModelCapacity(128_000),128_000,::measure){chunks.add(it)}
        assertEquals(StreamResult.Completed,result)
        val delivered=chunks.toList()
        assertEquals(listOf(
            StreamChunk.Usage(25,1),
            StreamChunk.TextDelta("你"),
            StreamChunk.TextDelta("好"),
            StreamChunk.ToolCallDelta(1,"toolu_1","get_weather",""),
            StreamChunk.ToolCallDelta(1,null,null,"{\"city\":"),
            StreamChunk.ToolCallDelta(1,null,null,"\"上海\"}"),
            StreamChunk.Usage(null,9),
            StreamChunk.Done("tool_use")),delivered)
        val assembled=StreamAssembler().also { delivered.forEach(it::accept) }
        assertEquals(listOf(PendingTool("toolu_1","get_weather","{\"city\":\"上海\"}")),assembled.toolCalls)
        assertEquals("tool_use",assembled.finishReason)
        assertEquals(256,JSONObject(calls.single()).getInt("max_tokens"))
    }

    @Test fun `thinking 增量与文本交错分通道`() {
        val decoder=AnthropicSseDecoder()
        assertEquals(listOf(StreamChunk.ThinkingDelta("先想")),decoder.feed("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"先想\"}}\n\n"))
        assertEquals(listOf(StreamChunk.TextDelta("答")),decoder.feed("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"答\"}}\n\n"))
        // 块序号独立归组：第二个工具块的 index=2。
        assertEquals(listOf(StreamChunk.ToolCallDelta(2,"toolu_2","probe","")),
            decoder.feed("data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"probe\"}}\n\n"))
        assertTrue(decoder.finish().isEmpty())
    }

    @Test fun `流中 error 事件立即失败且带类型码`()=server("/v1/messages") { server,endpoint ->
        server.createContext("/v1/messages") { exchange ->
            streamBody(exchange,listOf(
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}}\n\n",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"半句\"}}\n\n",
                "event: error\n","data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n",
                "data: {\"type\":\"message_stop\"}\n\n"))
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        val result=ChatCompletionCall(endpoint,protocol=WireProtocol.ANTHROPIC_MESSAGES)
            .stream(AnthropicMessagesRequest("claude-x",listOf(WireMessage("user","问")),64),
                ModelCapacity(128_000),128_000,::measure){chunks.add(it)}
        assertEquals(StreamResult.Failed,result)
        assertEquals(listOf(StreamChunk.Usage(5,1),StreamChunk.TextDelta("半句"),
            StreamChunk.Failure("Overloaded",code="overloaded_error")),chunks.toList())
    }

    @Test fun `无 message_stop 的断流按网络失败处理而见过 stop_reason 的补发收尾`() {
        // 干净断开但没有任何终止信号：NetworkFailure，已送达内容保留。
        server("/v1/messages") { server,endpoint ->
            server.createContext("/v1/messages") { exchange ->
                streamBody(exchange,listOf("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"半截\"}}\n\n"))
            }
            val chunks=ConcurrentLinkedQueue<StreamChunk>()
            assertEquals(StreamResult.NetworkFailure,ChatCompletionCall(endpoint,protocol=WireProtocol.ANTHROPIC_MESSAGES)
                .stream(AnthropicMessagesRequest("claude-x",listOf(WireMessage("user","问")),64),
                    ModelCapacity(128_000),128_000,::measure){chunks.add(it)})
            assertEquals(listOf(StreamChunk.TextDelta("半截")),chunks.toList())
        }
        // message_delta 带 stop_reason 后干净断开（缺 message_stop）：流尾补发 Done。
        server("/v1/messages") { server,endpoint ->
            server.createContext("/v1/messages") { exchange ->
                streamBody(exchange,listOf(
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"答\"}}\n\n",
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}\n\n"))
            }
            val chunks=ConcurrentLinkedQueue<StreamChunk>()
            assertEquals(StreamResult.Completed,ChatCompletionCall(endpoint,protocol=WireProtocol.ANTHROPIC_MESSAGES)
                .stream(AnthropicMessagesRequest("claude-x",listOf(WireMessage("user","问")),64),
                    ModelCapacity(128_000),128_000,::measure){chunks.add(it)})
            assertEquals(listOf(StreamChunk.TextDelta("答"),StreamChunk.Usage(null,2),StreamChunk.Done("end_turn")),chunks.toList())
        }
    }

    @Test fun `HTTP 400 错误体解析出类型与消息`()=server("/v1/messages") { server,endpoint ->
        server.createContext("/v1/messages") { exchange ->
            val body="{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: field required\"}}"
            val bytes=body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(400,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        val chunks=ConcurrentLinkedQueue<StreamChunk>()
        assertEquals(StreamResult.Failed,ChatCompletionCall(endpoint,protocol=WireProtocol.ANTHROPIC_MESSAGES)
            .stream(AnthropicMessagesRequest("claude-x",listOf(WireMessage("user","问")),64),
                ModelCapacity(128_000),128_000,::measure){chunks.add(it)})
        // 错误体进 message（[type] 前缀贴被替换实现的 mapHttpError 口径），type 进 code。
        assertEquals(listOf(StreamChunk.Failure("HTTP 400: [invalid_request_error] max_tokens: field required",
            400,"request","invalid_request_error",null)),chunks.toList())
    }

    @Test fun `HTTP 非 200 与半行容错`() {
        server("/v1/messages") { server,endpoint ->
            server.createContext("/"){it.sendResponseHeaders(529,-1);it.close()}
            val chunks=ConcurrentLinkedQueue<StreamChunk>()
            assertEquals(StreamResult.Failed,ChatCompletionCall(endpoint,protocol=WireProtocol.ANTHROPIC_MESSAGES)
                .stream(AnthropicMessagesRequest("claude-x",listOf(WireMessage("user","问")),64),
                    ModelCapacity(128_000),128_000,::measure){chunks.add(it)})
            assertEquals(listOf(StreamChunk.Failure("HTTP 529",529,"service",null,null)),chunks.toList())
        }
        // 半行跨喂入拼接；event:/注释/非 JSON 行不产事件；[DONE] 不是本协议哨兵（按坏 JSON 丢弃）。
        val decoder=AnthropicSseDecoder()
        assertTrue(decoder.feed("data: {\"type\":\"content_block_del").isEmpty())
        assertEquals(listOf(StreamChunk.TextDelta("拼")),decoder.feed("ta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"拼\"}}\r\n\r\n"))
        assertTrue(decoder.feed("event: message_start\n").isEmpty())
        assertTrue(decoder.feed(": keep-alive\n").isEmpty())
        assertTrue(decoder.feed("data: [DONE]\n").isEmpty())
        assertTrue(decoder.feed("data: 不是JSON\n").isEmpty())
    }

    // -----------------------------------------------------------------
    // P3.1d 净眼挂账：工具结果内嵌图片 / usage 缓存计量 / 协议防呆
    // -----------------------------------------------------------------

    @Test fun `工具结果内嵌图片编码进 tool_result 内容块且文本在前`() {
        val image=java.util.Base64.getEncoder().encodeToString(byteArrayOf(1,2,3))
        val request=AnthropicMessagesRequest("claude-sonnet-4-6",listOf(
            WireMessage("assistant","",toolCalls=listOf(PendingTool("toolu_1","screenshot","{}",null))),
            WireMessage("tool","截图结果",toolCallId="toolu_1",images=listOf(WireImage("image/png",image)))),256)
        val content=JSONObject(request.encode()).getJSONArray("messages")
            .getJSONObject(1).getJSONArray("content")   // [0]=assistant 轮 tool_use，[1]=user 轮 tool_result
        val result=content.getJSONObject(0)
        assertEquals("tool_result",result.getString("type"))
        assertEquals("toolu_1",result.getString("tool_use_id"))
        val inner=result.getJSONArray("content")
        assertEquals("text",inner.getJSONObject(0).getString("type"))
        assertEquals("截图结果",inner.getJSONObject(0).getString("text"))
        assertEquals("image",inner.getJSONObject(1).getString("type"))
        assertEquals("base64",inner.getJSONObject(1).getJSONObject("source").getString("type"))
        assertEquals("image/png",inner.getJSONObject(1).getJSONObject("source").getString("media_type"))
        assertEquals(image,inner.getJSONObject(1).getJSONObject("source").getString("data"))
    }

    @Test fun `usage 块携带缓存计量字段`() {
        val decoder=AnthropicSseDecoder()
        assertEquals(listOf(StreamChunk.Usage(25,1,1024,2048)),decoder.feed(
            "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":25,\"output_tokens\":1," +
                "\"cache_creation_input_tokens\":1024,\"cache_read_input_tokens\":2048}}}\n\n"))
        // message_delta 的 usage 只带 output：缓存字段缺省为 null（后到覆盖先到）。
        assertEquals(listOf(StreamChunk.Usage(null,9,null,null)),decoder.feed(
            "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":9}}\n\n"))
    }

    @Test fun `协议与请求体方言错配在开连接前早失败`()=server("/v1/messages") { server,endpoint ->
        var touched=0
        server.createContext("/"){touched++;it.sendResponseHeaders(200,-1);it.close()}
        // anthropic 请求体进了 OpenAI 兼容线的调用：SSE 会用错方言静默解码成空流，这里必须早失败。
        assertThrows(IllegalArgumentException::class.java) {
            ChatCompletionCall(endpoint).stream(AnthropicMessagesRequest("claude-x",listOf(WireMessage("user","问")),64),
                ModelCapacity(128_000),128_000,::measure){}
        }
        // 反向：OpenAI 兼容请求体进了 anthropic 线的调用。
        assertThrows(IllegalArgumentException::class.java) {
            ChatCompletionCall(endpoint,protocol=WireProtocol.ANTHROPIC_MESSAGES)
                .stream(StreamRequest(TextRequest("m",listOf(WireMessage("user","问")),64)),
                    ModelCapacity(128_000),128_000,::measure){}
        }
        // 非流式 execute 只实现 OpenAI 兼容线：anthropic 协议直接拒绝。
        assertThrows(IllegalArgumentException::class.java) {
            ChatCompletionCall(endpoint,protocol=WireProtocol.ANTHROPIC_MESSAGES)
                .execute(TextRequest("m",listOf(WireMessage("user","问")),64),ModelCapacity(128_000),128_000,::measure)
        }
        assertEquals(0,touched)
    }
}
