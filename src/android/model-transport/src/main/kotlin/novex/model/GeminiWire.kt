package novex.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini generateContent / streamGenerateContent 线协议（novex.model 自有实现；
 * 行为面对齐 v1beta，不依赖上游代码）。与 OpenAI 兼容线的差异：
 *  - `contents[]` 只有 user/model 两种角色；system 提示走顶层 `systemInstruction`；
 *  - 工具历史是 model 轮的 `functionCall` 部分 + user 轮的 `functionResponse` 部分；
 *    functionCall 整块到达（无参数增量），Gemini 3.x 还要求每个历史 functionCall
 *    携带同部分的 `thoughtSignature`，缺签名的调用与结果一起降级为文本摘要；
 *  - `generationConfig` 收 maxOutputTokens / temperature / thinkingConfig /
 *    responseModalities（图像/音频输出机型必须声明才会吐 inlineData）；
 *  - 流式端点 `:streamGenerateContent?alt=sse`，SSE 无哨兵：服务端关流即正常
 *    收尾，finishReason 在最后一个 data 块里（STOP→end_turn、MAX_TOKENS→
 *    max_tokens、SAFETY 等原样小写）；
 *  - thought 部分带 `thought:true` 标记，与正文 text 分通道。
 */

/**
 * Gemini 流式请求体。system 角色的 [WireMessage] 提升为 `systemInstruction`；
 * tool 角色并入 user 轮的 functionResponse 部分（name 从配对的 assistant 调用取）。
 */
data class GeminiGenerateContentRequest(
    val model: String,
    val messages: List<WireMessage>,
    val maxOutputTokens: Long,
    val tools: List<ToolDefinition> = emptyList(),
    /** generationConfig.thinkingConfig（调用方经自家思考规则解析器产出）。 */
    val thinkingConfig: JSONObject? = null,
    /** 响应模态声明：图像输出机型 ["TEXT","IMAGE"]、音频输出机型 ["AUDIO"]；空=默认纯文本。 */
    val responseModalities: List<String> = emptyList(),
    /** 音频输出机型拒收 systemInstruction（400），调用方按模型声明置位。 */
    val rejectsSystemInstruction: Boolean = false,
    /** gemini-3.x：历史 functionCall 必须带 thoughtSignature。 */
    val requiresThoughtSignature: Boolean = false,
    val temperature: Double? = null,
) : CompletionStreamRequest {
    init {
        require(model.isNotBlank() && messages.isNotEmpty() && maxOutputTokens>0)
        require(tools.map { it.name }.distinct().size==tools.size)
        require(messages.count { it.role=="system" }<=1)
    }
    override val outputReserve: Long get()=maxOutputTokens

    override fun encode(): String = wire().toString()

    private fun wire():JSONObject {
        val unsignedIds=unsignedToolCallIds()
        val contents=JSONArray()
        for(message in messages.filter { it.role!="system" }) {
            val role=if(message.role=="assistant") "model" else "user"
            val parts=JSONArray()
            when(message.role) {
                "tool"->parts.put(unsignedToolResult(message,unsignedIds))
                "assistant"-> {
                    if(message.text.isNotEmpty()) parts.put(JSONObject().put("text",message.text))
                    for(call in message.toolCalls) {
                        if(call.id in unsignedIds) {
                            val described=call.arguments.take(500)
                            parts.put(JSONObject().put("text","[Called ${call.name} with: $described]"))
                        } else {
                            parts.put(JSONObject().apply {
                                if(requiresThoughtSignature && !call.thoughtSignature.isNullOrEmpty())
                                    put("thoughtSignature",call.thoughtSignature)
                                put("functionCall",JSONObject().put("name",call.name).put("args",JSONObject(call.arguments)))
                            })
                        }
                    }
                }
                else-> {
                    // 空文本 + 有图片：省略文本部分（parts 路径口径）；纯空文本轮以
                    // 单空格占位（{"text":""} 会触发 oneof 400）。
                    if(message.text.isNotEmpty() || (message.images.isEmpty() && message.audios.isEmpty()))
                        parts.put(JSONObject().put("text",message.text.ifEmpty { " " }))
                    message.images.forEach { image->
                        parts.put(JSONObject().put("inlineData",
                            JSONObject().put("mimeType",image.mediaType).put("data",image.base64)))
                    }
                }
            }
            // 空部件即 400（contents[N].parts must not be empty）：占位兜底。
            if(parts.length()==0) parts.put(JSONObject().put("text","(empty)"))
            contents.put(JSONObject().put("role",role).put("parts",parts))
        }
        val body=JSONObject().put("contents",contents)
        val prompt=messages.firstOrNull { it.role=="system" }?.text
        if(!prompt.isNullOrEmpty() && !rejectsSystemInstruction)
            body.put("systemInstruction",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",prompt))))
        if(tools.isNotEmpty()) {
            val declarations=JSONArray()
            for(tool in tools) declarations.put(tool.geminiDeclaration())
            body.put("tools",JSONArray().put(JSONObject().put("function_declarations",declarations)))
        }
        val config=JSONObject().put("maxOutputTokens",maxOutputTokens)
        temperature?.let { config.put("temperature",it) }
        thinkingConfig?.let { config.put("thinkingConfig",it) }
        if(responseModalities.isNotEmpty()) config.put("responseModalities",JSONArray(responseModalities))
        body.put("generationConfig",config)
        return body
    }

    /** requiresThoughtSignature 开启时，无签名可回放的工具调用 id 集：这些调用与结果都降级为文本。 */
    private fun unsignedToolCallIds():Set<String> {
        if(!requiresThoughtSignature) return emptySet()
        return buildSet {
            for(message in messages) for(call in message.toolCalls)
                if(call.thoughtSignature.isNullOrEmpty()) add(call.id)
        }
    }

    private fun unsignedToolResult(message:WireMessage,unsignedIds:Set<String>):JSONObject {
        if(message.toolCallId !in unsignedIds) {
            val response=JSONObject().put("name",toolNameOf(message.toolCallId!!))
                .put("response",JSONObject().put("result",message.text.ifEmpty { " " })
                    .let { if(message.isError) it.put("error",true) else it })
            return JSONObject().put("functionResponse",response)
        }
        val truncated=message.text.take(1000)
        val prefix=if(message.isError) "Error from" else "Result of"
        return JSONObject().put("text","[$prefix ${toolNameOf(message.toolCallId!!)}: $truncated]")
    }

    /** 配对的 assistant 调用名（functionResponse.name 必须与 functionCall.name 一致）。 */
    private fun toolNameOf(toolCallId:String):String=
        messages.asSequence().flatMap { it.toolCalls.asSequence() }.firstOrNull { it.id==toolCallId }?.name ?: ""

    private fun ToolDefinition.geminiDeclaration():JSONObject {
        val schema=JSONObject(parameters).withUppercaseTypes()
        propertyOrdering?.let { schema.put("propertyOrdering",JSONArray(it)) }
        return JSONObject().put("name",name).put("description",description).put("parameters",schema)
    }

    /** Gemini schema 的 type 取大写（object→OBJECT）；递归处理嵌套 properties/items。 */
    private fun JSONObject.withUppercaseTypes():JSONObject {
        val out=JSONObject()
        for(key in keys()) {
            val value=get(key)
            out.put(key,when(value) {
                is JSONObject->value.withUppercaseTypes()
                is JSONArray->JSONArray((0 until value.length()).map { position->
                    val element=value.get(position)
                    if(element is JSONObject) element.withUppercaseTypes() else element
                })
                else->if(key=="type" && value is String) value.uppercase() else value
            })
        }
        return out
    }
}

/**
 * Gemini SSE 方言（alt=sse 的 data 行）：candidates[0].content.parts[] 的 text 按
 * thought 标记分通道；functionCall 整块合成 `gemini_<纳秒>` id；usageMetadata 计
 * 量；inlineData 产出 [StreamChunk.MediaAttachment]。无哨兵——干净断流即收尾
 * （[shouldFinishAtEndOfStream] 恒真，缺省收尾原因 end_turn）。
 */
internal class GeminiSseDecoder : SseLineDecoder() {
    private var toolCounter=0
    override val acceptsDoneSentinel:Boolean get()=false
    override fun shouldFinishAtEndOfStream():Boolean=true
    override fun decodePayload(event:JSONObject):List<StreamChunk> {
        val out=mutableListOf<StreamChunk>()
        event.optJSONObject("error")?.let { error ->
            markFailed()
            // Gemini 错误对象：code 是数字（HTTP 码）、status 是字符串（如 PERMISSION_DENIED）。
            val httpStatus=error.streamCount("code")?.toInt()
            val apiStatus=error.streamText("status")
            out+=StreamChunk.Failure(error.streamText("message").ifBlank { "服务在流中报告错误" },status=httpStatus,code=apiStatus.ifBlank { null })
            return out
        }
        val candidate=event.optJSONArray("candidates")?.optJSONObject(0)
        val parts=candidate?.optJSONObject("content")?.optJSONArray("parts")
        if(parts!=null) for(position in 0 until parts.length()) {
            val part=parts.optJSONObject(position) ?: continue
            val text=part.streamText("text")
            if(text.isNotEmpty()) {
                if(part.optBoolean("thought",false)) out+=StreamChunk.ThinkingDelta(text)
                else out+=StreamChunk.TextDelta(text)
            }
            part.optJSONObject("functionCall")?.let { call ->
                val name=call.streamText("name")
                if(name.isNotEmpty()) {
                    val id="gemini_${System.nanoTime()}"
                    val signature=part.streamText("thoughtSignature").takeIf { it.isNotEmpty() }
                    out+=StreamChunk.ToolCallDelta(toolCounter++,id,name,call.optJSONObject("args")?.toString() ?: "{}",signature)
                }
            }
            part.optJSONObject("inlineData")?.let { inline ->
                val mime=inline.streamText("mimeType");val data=inline.streamText("data")
                if(mime.isNotEmpty() && data.isNotEmpty()) out+=StreamChunk.MediaAttachment(mime,data)
            }
        }
        event.optJSONObject("usageMetadata")?.let { usage ->
            val input=usage.streamCount("promptTokenCount");val output=usage.streamCount("candidatesTokenCount")
            if(input!=null||output!=null) out+=StreamChunk.Usage(input,output)
        }
        candidate?.streamText("finishReason")?.takeIf { it.isNotBlank() }?.let { raw ->
            finishReason=when(raw) {
                "STOP"->"end_turn"
                "MAX_TOKENS"->"max_tokens"
                else->raw.lowercase()
            }
        }
        return out
    }
}
