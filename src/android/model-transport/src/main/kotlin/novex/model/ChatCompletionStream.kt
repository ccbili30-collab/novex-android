package novex.model

import novex.conversation.CapacityDecision
import org.json.JSONObject

/**
 * 流式请求：复用 [TextRequest] 的消息/工具编码与配对校验，只把 stream 开关置真。
 *
 * 兼容处理：includeUsage=true（默认）时附 `stream_options.include_usage`，让末块携带用量；
 * 部分 OpenAI 兼容网关（如 OpenRouter）拒绝该字段，此类端点应传 false。
 */
data class StreamRequest(val request: TextRequest, val includeUsage: Boolean = true) {
    fun encode(): String = request.wire().put("stream",true)
        .also { if(includeUsage) it.put("stream_options",JSONObject().put("include_usage",true)) }.toString()
}

/** 流事件，块顺序即服务端事件顺序；同一 data 行内先思考增量，再文本增量，再工具分片，最后用量。 */
sealed interface StreamChunk {
    data class TextDelta(val text: String) : StreamChunk
    data class ThinkingDelta(val text: String) : StreamChunk
    /**
     * 工具调用分片。契约：解析器只吐分片，拼装由 [StreamAssembler] 负责——
     * id/name 仅在服务端首次给出的分片上非空（后续分片省略），argumentsDelta 是
     * 参数串的增量片段，按 index 归组、按到达顺序拼接即得完整调用。
     */
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argumentsDelta: String) : StreamChunk
    data class Usage(val inputTokens: Long?, val outputTokens: Long?) : StreamChunk
    /** 流收尾：`data: [DONE]` 哨兵，或哨兵缺失但出现过 finish_reason 的流尾。 */
    data class Done(val finishReason: String?) : StreamChunk
    /**
     * 流失败，两种来源：HTTP 非 200（status/category/retryAfter 齐备）与流中途
     * error 对象（code/message）。出现后流终止，之后的数据不再产生任何块。
     */
    data class Failure(val message: String, val status: Int? = null, val category: String? = null,
                       val code: String? = null, val retryAfter: String? = null) : StreamChunk
}

/** stream() 的收尾结论。内容与失败细节已经通过块送达，这里只报告这次调用是怎么结束的。 */
sealed interface StreamResult {
    /** 走到收尾，Done 块已发出。 */
    data object Completed : StreamResult
    /** 容量闸门拦下，未发送任何请求。 */
    data class NotSent(val capacity: CapacityDecision) : StreamResult
    /** cancel() 生效。 */
    data object Cancelled : StreamResult
    /** 读超时（未被取消）。 */
    data object TimedOut : StreamResult
    /** 连接异常：IO 中断，或服务端在没有任何终止信号的情况下断流，内容可能不完整。 */
    data object NetworkFailure : StreamResult
    /** 发出过 Failure 块。 */
    data object Failed : StreamResult
}

/** org.json 的 optString 对 JSON null 会给出字面量 "null"，统一在此挡掉。 */
private fun textOf(container: JSONObject, key: String): String =
    if(container.isNull(key)) "" else container.optString(key,"")

private fun countOf(container: JSONObject, key: String): Long? {
    if(container.isNull(key)) return null
    return container.optLong(key,-1).takeIf { it>=0 }
}

/**
 * SSE 逐行状态机：喂入任意切割的原始文本（含半行），吐出完整行解析出的事件。
 * 容错：CRLF 行尾、空行、`:` 注释（心跳）行、`data:` 无前导空格、非 JSON 数据行
 * （丢弃不崩）、跨喂入调用的半行缓冲。进入终态（Done/Failure）后忽略一切后续输入。
 */
internal class SseDecoder {
    private val pending=StringBuilder()
    private var finishReason:String?=null
    private var done=false
    private var failed=false
    fun sawDone()=done
    fun sawFailure()=failed
    fun feed(text: String): List<StreamChunk> {
        if(done||failed) return emptyList()
        pending.append(text)
        val events=mutableListOf<StreamChunk>()
        while(true) {
            val end=pending.indexOf("\n")
            if(end<0) break
            val line=pending.substring(0,end).removeSuffix("\r")
            pending.delete(0,end+1)
            events+=decodeLine(line)
            if(done||failed) break
        }
        return events
    }
    /** 流结束：冲掉缓冲里残留的半行（截断 JSON 自然解析失败被丢弃），并给见过 finish_reason 却没等到哨兵的流补发收尾。 */
    fun finish(): List<StreamChunk> {
        if(done||failed) return emptyList()
        val tail=pending.toString().removeSuffix("\r");pending.clear()
        val events=decodeLine(tail).toMutableList()
        if(!done && !failed && finishReason!=null) { done=true;events+=StreamChunk.Done(finishReason) }
        return events
    }
    private fun decodeLine(raw: String): List<StreamChunk> {
        if(raw.isBlank()||raw.startsWith(":")) return emptyList()
        if(!raw.startsWith("data:")) return emptyList() // event:/id:/retry: 等其余 SSE 字段与本协议无关
        val payload=raw.removePrefix("data:").let { if(it.startsWith(" ")) it.removePrefix(" ") else it } // 只剥一个前导空格（HTML5 SSE 规则，兼容无空格服务端）
        if(payload.trim()=="[DONE]") { done=true;return listOf(StreamChunk.Done(finishReason)) }
        val event=try { JSONObject(payload) } catch(_:Exception) { return emptyList() }
        val out=mutableListOf<StreamChunk>()
        event.optJSONObject("error")?.let { error ->
            failed=true
            val code=textOf(error,"code")
            out+=StreamChunk.Failure(textOf(error,"message").ifBlank { "服务在流中报告错误" },code=code.ifBlank { null })
            return out
        }
        val choice=event.optJSONArray("choices")?.optJSONObject(0) // 用量末块的 choices 是空数组
        val delta=choice?.optJSONObject("delta")
        if(delta!=null) {
            textOf(delta,"reasoning_content").ifEmpty { textOf(delta,"reasoning") }.takeIf { it.isNotEmpty() }?.let { out+=StreamChunk.ThinkingDelta(it) }
            textOf(delta,"content").takeIf { it.isNotEmpty() }?.let { out+=StreamChunk.TextDelta(it) }
            delta.optJSONArray("tool_calls")?.let { calls ->
                for(position in 0 until calls.length()) {
                    val call=calls.optJSONObject(position)?:continue
                    val function=call.optJSONObject("function")
                    val id=textOf(call,"id").takeIf { it.isNotBlank() }
                    val name=function?.let { textOf(it,"name") }?.takeIf { it.isNotBlank() }
                    val arguments=function?.let { textOf(it,"arguments") }?: ""
                    if(id!=null||name!=null||arguments.isNotEmpty())
                        out+=StreamChunk.ToolCallDelta(call.optInt("index",0),id,name,arguments)
                }
            }
        }
        choice?.let { textOf(it,"finish_reason").takeIf { value -> value.isNotBlank() } }?.let { finishReason=it }
        event.optJSONObject("usage")?.let { usage ->
            val input=countOf(usage,"prompt_tokens");val output=countOf(usage,"completion_tokens")
            if(input!=null||output!=null) out+=StreamChunk.Usage(input,output)
        }
        return out
    }
}

/**
 * 流聚合器：把 [StreamChunk] 分片折成与 [ModelResult] 同形的最终事实——文本、思考、
 * 工具调用（按 index 归组拼装 id/name/arguments）、用量与收尾原因。块只喂一遍即可取值。
 */
class StreamAssembler {
    private val textBuffer=StringBuilder()
    private val thinkingBuffer=StringBuilder()
    private class ToolAssembly { var id="";var name="";val arguments=StringBuilder() }
    private val calls=java.util.TreeMap<Int,ToolAssembly>()
    var usage: StreamChunk.Usage?=null;private set
    var finishReason: String?=null;private set
    var failure: StreamChunk.Failure?=null;private set
    val text:String get()=textBuffer.toString()
    val thinking:String get()=thinkingBuffer.toString()
    /** 按 index 顺序输出；id 或 name 缺失（无法回传服务端）的调用被丢弃。 */
    val toolCalls:List<PendingTool> get()=calls.values.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        .map { PendingTool(it.id,it.name,it.arguments.toString()) }
    fun accept(chunk: StreamChunk) {
        when(chunk) {
            is StreamChunk.TextDelta -> textBuffer.append(chunk.text)
            is StreamChunk.ThinkingDelta -> thinkingBuffer.append(chunk.text)
            is StreamChunk.ToolCallDelta -> {
                val assembly=calls.getOrPut(chunk.index) { ToolAssembly() }
                chunk.id?.let { assembly.id=it }
                chunk.name?.let { assembly.name=it }
                assembly.arguments.append(chunk.argumentsDelta)
            }
            is StreamChunk.Usage -> usage=chunk
            is StreamChunk.Done -> finishReason=chunk.finishReason
            is StreamChunk.Failure -> failure=chunk
        }
    }
}
