package novex.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages 线协议（novex.model 自有实现；行为面对齐 Messages API，
 * 不依赖上游代码）。与 OpenAI 兼容线的差异：
 *  - `system` 是顶层字段（content blocks），不是消息序列的一员；
 *  - 消息必须 user/assistant 交替，连续同角色合并；assistant 块序必须
 *    thinking → text/image → tool_use；
 *  - 工具调用历史是 assistant 的 `tool_use` 块 + 下一 user 的 `tool_result` 块，
 *    参数经 `input_json_delta` 增量流式；
 *  - `max_tokens` 必填；legacy（≤4.5）思考开启时要求 temperature=1，4.6+ 拒收
 *    temperature 且改用 adaptive thinking（effort 档）；
 *  - SSE 无 `[DONE]` 哨兵：`message_stop` 事件收尾，`message_delta` 带 stop_reason
 *    与最终用量，`error` 事件带 `{type,message}`。
 */

/** 思考等级（novex.model 的中性枚举，与上游 UI 等级按名一一对应；关闭以 null 表达）。 */
enum class WireThinkingLevel { LOW, MEDIUM, HIGH, XHIGH, MAX, ULTRA }

/** Claude 机型判定的纯函数集：版本解析、adaptive/budget 思考形态、temperature 拒收。 */
object AnthropicWire {
    /**
     * 从 Claude 型 id 解析 (major, minor)。单段 id（claude-opus-5 / claude-*-6）视
     * minor=0；非 Claude id 或无版本数字返回 null。贪婪匹配保证 claude-3-5-sonnet
     * 解析为 (3,5) 而非 (3,0)。
     */
    internal fun parseClaudeVersion(modelId: String): Pair<Int,Int>? {
        val lower=modelId.lowercase()
        if(!lower.contains("claude")) return null
        val match=Regex("""[-/]?(\d+)(?:[-.](\d+))?(?:$|[^0-9])""").find(lower) ?: return null
        val major=match.groupValues[1].toIntOrNull() ?: return null
        return major to (match.groupValues[2].toIntOrNull() ?: 0)
    }

    /** Claude 4.6 起（含 5 系单段 id）拒收 temperature 参数。 */
    fun modelRejectsTemperature(modelId:String):Boolean {
        val (major,minor)=parseClaudeVersion(modelId) ?: return false
        return major>4||(major==4&&minor>=6)
    }

    /** Claude 4.6 起改用 adaptive thinking；旧式 budget_tokens 在其上被静默忽略。 */
    fun modelUsesAdaptiveThinking(modelId:String):Boolean {
        val (major,minor)=parseClaudeVersion(modelId) ?: return false
        return major>4||(major==4&&minor>=6)
    }

    /**
     * 旧式（≤4.5）思考预算：必须严格小于 max_tokens——相等即 400。HIGH 与顶档
     * 会顶到 maxTokens 上限，届时回退 maxTokens-1。
     */
    fun thinkingBudget(maxTokens: Int,level: WireThinkingLevel):Int {
        val cap=when(level) {
            WireThinkingLevel.LOW->8192
            WireThinkingLevel.MEDIUM->32768
            WireThinkingLevel.HIGH->minOf(maxTokens,65536)
            WireThinkingLevel.XHIGH,WireThinkingLevel.MAX,WireThinkingLevel.ULTRA->maxTokens
        }
        val clamped=minOf(cap,maxTokens)
        return if(clamped>=maxTokens && maxTokens>1) maxTokens-1 else clamped
    }

    /** adaptive 档位：Anthropic 最高 max，XHIGH 以上全部折叠到 max。 */
    fun adaptiveEffort(level: WireThinkingLevel):String=when(level) {
        WireThinkingLevel.LOW->"low"
        WireThinkingLevel.MEDIUM->"medium"
        WireThinkingLevel.HIGH->"high"
        WireThinkingLevel.XHIGH,WireThinkingLevel.MAX,WireThinkingLevel.ULTRA->"max"
    }

    /**
     * 抽象思考形态（思考规则解析器与请求编码共用的单一事实源）：
     * `{effort}` → adaptive（4.6+）；`{budget_tokens}` → legacy 预算（≤4.5）；
     * `{disabled}` → adaptive 机型显式关（不发字段会被默认开启，小 max_tokens
     * 整段烧在思考上返回空文本）；空 map → 不发 thinking 字段。
     */
    fun thinkingShape(modelId:String,supportsReasoning:Boolean?,level:WireThinkingLevel?,maxTokens:Int):Map<String,Any> {
        val adaptive=modelUsesAdaptiveThinking(modelId)
        if(level!=null && supportsReasoning!=false) {
            if(adaptive) return mapOf("effort" to adaptiveEffort(level))
            val budget=thinkingBudget(maxTokens,level)
            return if(budget>0) mapOf("budget_tokens" to budget) else emptyMap()
        }
        return if(adaptive) mapOf("disabled" to true) else emptyMap()
    }

    /** Anthropic 工具 id 只收 `[a-zA-Z0-9_-]`；其余字符折叠为 `-`（OpenAI Responses 的 `|` 等）。 */
    internal fun sanitizeToolId(id:String):String=buildString(id.length) {
        for(ch in id) append(if(ch.isLetterOrDigit()||ch=='_'||ch=='-') ch else '-')
    }
}

/**
 * Anthropic Messages 流式请求体。system 角色的 [WireMessage] 被提升为顶层 `system`
 * 块（至多一条）；其余按 user/assistant/tool 角色编码并合并交替。工具结果配对校验
 * 沿用 [WireMessage] 的装配约束。
 */
data class AnthropicMessagesRequest(
    val model: String,
    val messages: List<WireMessage>,
    val maxTokens: Long,
    val tools: List<ToolDefinition> = emptyList(),
    /** 思考等级；null=关。形态判定（adaptive/budget/disabled）在编码时按机型自决。 */
    val thinkingLevel: WireThinkingLevel? = null,
    val supportsReasoning: Boolean? = null,
    /** 调用方温度；legacy 思考开启时被协议强制的 temperature=1 覆盖。 */
    val temperature: Double? = null,
    /** 增强缓存：cache_control 断点携带 ttl:"1h"（默认 5 分钟）。 */
    val cacheTtlOneHour: Boolean = false,
    /** OAuth（Claude Code）线路：Bearer 鉴权 + 系统前缀块拆分（前缀块不缓存）。 */
    val isOAuth: Boolean = false,
    /** OAuth 线路的系统前缀（调用方注入自家常量）；非 OAuth 传 null。 */
    val oauthSystemPrefix: String? = null,
    /** Anthropic 兼容中继的交错思考回放：assistant 历史块首合成无签名 thinking 块。 */
    val echoUnsignedThinking: Boolean = false,
) : CompletionStreamRequest {
    init {
        require(model.isNotBlank() && messages.isNotEmpty() && maxTokens>0)
        require(tools.map { it.name }.distinct().size==tools.size)
        require(messages.count { it.role=="system" }<=1)
    }
    override val outputReserve: Long get()=maxTokens

    override fun encode(): String = wire().toString()

    private fun wire():JSONObject {
        val shape=AnthropicWire.thinkingShape(model,supportsReasoning,thinkingLevel,maxTokens.toInt())
        val body=JSONObject().put("model",model).put("max_tokens",maxTokens).put("stream",true)
        if(shape.isEmpty() && temperature!=null && !AnthropicWire.modelRejectsTemperature(model)) body.put("temperature",temperature)
        when {
            shape.containsKey("effort") -> {
                // adaptive 机型：display 固定 summarized——新机型默认 omitted 会吞掉思考文本。
                body.put("thinking",JSONObject().put("type","adaptive").put("display","summarized"))
                body.put("output_config",JSONObject().put("effort",shape["effort"]))
            }
            shape.containsKey("budget_tokens") -> {
                body.put("thinking",JSONObject().put("type","enabled").put("budget_tokens",shape["budget_tokens"]))
                if(!AnthropicWire.modelRejectsTemperature(model)) body.put("temperature",1)
            }
            shape.containsKey("disabled") -> body.put("thinking",JSONObject().put("type","disabled"))
        }
        systemBlocks()?.let { body.put("system",it) }
        if(tools.isNotEmpty()) {
            val toolsArray=JSONArray()
            for((index,tool) in tools.withIndex()) {
                val toolJson=JSONObject().put("name",tool.name).put("description",tool.description)
                    .put("input_schema",JSONObject(tool.parameters))
                toolJson.put("eager_input_streaming",true)
                if(index==tools.lastIndex) toolJson.put("cache_control",cacheControl())
                toolsArray.put(toolJson)
            }
            body.put("tools",toolsArray)
            body.put("tool_choice",JSONObject().put("type","auto"))
        }
        body.put("messages",messageArray())
        return body
    }

    /** 顶层 system 块：OAuth 拆前缀（不缓存）+尾部（缓存）；API key 单块缓存；空提示不发 system 字段。 */
    private fun systemBlocks():JSONArray? {
        val prompt=messages.firstOrNull { it.role=="system" }?.text
        if(isOAuth) {
            val prefix=oauthSystemPrefix.orEmpty()
            val tail=when {
                prompt==null -> ""
                prefix.isNotEmpty() && prompt.startsWith(prefix) -> prompt.removePrefix(prefix).trimStart('\n')
                else -> prompt
            }
            val blocks=JSONArray()
            if(prefix.isNotEmpty()) blocks.put(textBlock(prefix))
            if(tail.isNotEmpty()) blocks.put(textBlock(tail).put("cache_control",cacheControl()))
            return if(blocks.length()>0) blocks else null
        }
        if(prompt.isNullOrEmpty()) return null
        return JSONArray().put(textBlock(prompt).put("cache_control",cacheControl()))
    }

    private fun cacheControl():JSONObject=JSONObject().put("type","ephemeral").let {
        if(cacheTtlOneHour) it.put("ttl","1h") else it
    }

    private fun textBlock(text:String)=JSONObject().put("type","text").put("text",text)

    private fun imageBlock(image:WireImage)=JSONObject().put("type","image").put("source",
        JSONObject().put("type","base64").put("media_type",image.mediaType).put("data",image.base64))

    /** 消息序列：role → content blocks；连续同角色合并；assistant 块序 thinking→text/image→tool_use。 */
    private fun messageArray():JSONArray {
        val encoded=messages.filter { it.role!="system" }.map { message->
            val role=if(message.role=="assistant") "assistant" else "user"  // tool 结果并入 user 轮
            val blocks=JSONArray()
            when(message.role) {
                "tool"-> {
                    val content=JSONArray().put(textBlock(message.text))
                    blocks.put(JSONObject().put("type","tool_result")
                        .put("tool_use_id",AnthropicWire.sanitizeToolId(message.toolCallId!!))
                        .put("content",content)
                        .let { if(message.isError) it.put("is_error",true) else it })
                }
                "assistant"-> {
                    if(echoUnsignedThinking) blocks.put(JSONObject().put("type","thinking")
                        .put("thinking",message.reasoningContent ?: ""))
                    if(message.text.isNotEmpty()) blocks.put(textBlock(message.text))
                    for(call in message.toolCalls) blocks.put(JSONObject().put("type","tool_use")
                        .put("id",AnthropicWire.sanitizeToolId(call.id))
                        .put("name",call.name)
                        .put("input",JSONObject(call.arguments)))
                }
                else-> {
                    if(message.text.isNotEmpty() || message.images.isEmpty()) blocks.put(textBlock(message.text))
                    message.images.forEach { blocks.put(imageBlock(it)) }
                }
            }
            role to blocks
        }
        val merged=JSONArray()
        var index=0
        while(index<encoded.size) {
            val (role,blocks)=encoded[index]
            val combined=JSONArray(blocks)
            var next=index+1
            while(next<encoded.size && encoded[next].first==role) {
                for(position in 0 until encoded[next].second.length()) combined.put(encoded[next].second.get(position))
                next++
            }
            val ordered=if(role=="assistant") reorderAssistant(combined) else combined
            merged.put(JSONObject().put("role",role).put("content",ordered))
            index=next
        }
        injectMessageCacheControl(merged)
        return merged
    }

    /** assistant 块序：thinking 最先、tool_use 最后——Anthropic 拒收 tool_use 之后的文本。 */
    private fun reorderAssistant(blocks:JSONArray):JSONArray {
        val thinking=JSONArray();val middle=JSONArray();val toolUse=JSONArray()
        for(position in 0 until blocks.length()) {
            val block=blocks.getJSONObject(position)
            when(block.optString("type")) {
                "thinking"->thinking.put(block)
                "tool_use"->toolUse.put(block)
                else->middle.put(block)
            }
        }
        val out=JSONArray()
        for(source in listOf(thinking,middle,toolUse)) for(position in 0 until source.length()) out.put(source.get(position))
        return out
    }

    /** 最近两条 user 消息的末块挂 cache_control（Anthropic 四断点中的消息侧两个）。 */
    private fun injectMessageCacheControl(merged:JSONArray) {
        var userCount=0
        for(position in merged.length()-1 downTo 0) {
            val message=merged.getJSONObject(position)
            if(message.getString("role")!="user") continue
            userCount++
            if(userCount>2) break
            val content=message.getJSONArray("content")
            if(content.length()>0) content.getJSONObject(content.length()-1).put("cache_control",cacheControl())
        }
    }
}

/**
 * Anthropic SSE 方言：`event:` 行忽略（类型在 data 载荷的 type 字段里），事件族
 * message_start / content_block_start / content_block_delta / content_block_stop /
 * message_delta / message_stop / ping / error。工具入参按 content_index 归组为
 * [StreamChunk.ToolCallDelta] 分片；message_stop 即收尾（无 [DONE] 哨兵）。
 */
internal class AnthropicSseDecoder : SseLineDecoder() {
    override val acceptsDoneSentinel:Boolean get()=false
    override fun decodePayload(event:JSONObject):List<StreamChunk> {
        val out=mutableListOf<StreamChunk>()
        when(event.streamText("type")) {
            "message_start"->event.optJSONObject("message")?.optJSONObject("usage")?.let { usage(it,out) }
            "content_block_start"-> {
                val block=event.optJSONObject("content_block")
                if(block?.streamText("type")=="tool_use") {
                    val id=block.streamText("id");val name=block.streamText("name")
                    if(id.isNotEmpty()&&name.isNotEmpty())
                        out+=StreamChunk.ToolCallDelta(blockIndexOf(event),id,name,"")
                }
            }
            "content_block_delta"-> {
                val delta=event.optJSONObject("delta") ?: return out
                when(delta.streamText("type")) {
                    "text_delta"->delta.streamText("text").takeIf { it.isNotEmpty() }?.let { out+=StreamChunk.TextDelta(it) }
                    "thinking_delta"->delta.streamText("thinking").takeIf { it.isNotEmpty() }?.let { out+=StreamChunk.ThinkingDelta(it) }
                    "input_json_delta"->delta.streamText("partial_json").takeIf { it.isNotEmpty() }?.let { partial->
                        out+=StreamChunk.ToolCallDelta(blockIndexOf(event),null,null,partial)
                    }
                }
            }
            "message_delta"-> {
                event.optJSONObject("usage")?.let { usage(it,out) }
                event.optJSONObject("delta")?.streamText("stop_reason")?.takeIf { it.isNotBlank() }?.let { finishReason=it }
            }
            "message_stop"-> { markDone();out+=StreamChunk.Done(finishReason) }
            "error"-> {
                markFailed()
                val error=event.optJSONObject("error")
                val type=error?.streamText("type").orEmpty()
                val message=error?.streamText("message").orEmpty()
                out+=StreamChunk.Failure(message.ifBlank { "服务在流中报告错误" },code=type.ifBlank { null })
            }
        }
        return out
    }
    private fun usage(container:JSONObject,out:MutableList<StreamChunk>) {
        val input=container.streamCount("input_tokens");val output=container.streamCount("output_tokens")
        if(input!=null||output!=null) out+=StreamChunk.Usage(input,output)
    }
    private companion object {
        /** 事件携带的内容块序号：正式字段名是 index（个别中继写 content_block_index，兜底兼容）。 */
        private fun blockIndexOf(event:JSONObject):Int {
            if(!event.isNull("index")) return event.optInt("index",0)
            return event.optInt("content_block_index",0)
        }
    }
}
