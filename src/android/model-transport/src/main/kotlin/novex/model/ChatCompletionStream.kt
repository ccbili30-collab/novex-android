package novex.model

import novex.conversation.CapacityDecision
import org.json.JSONObject

/**
 * 流式请求：复用 [TextRequest] 的消息/工具编码与配对校验，只把 stream 开关置真。
 *
 * 兼容处理：includeUsage=true（默认）时附 `stream_options.include_usage`，让末块携带用量；
 * 部分 OpenAI 兼容网关（如 OpenRouter）拒绝该字段，此类端点应传 false。
 */
data class StreamRequest(val request: TextRequest, val includeUsage: Boolean = true) : CompletionStreamRequest {
    override val outputReserve: Long get() = request.outputReserve
    override fun encode(): String = request.wire().put("stream",true)
        .also { if(includeUsage) it.put("stream_options",JSONObject().put("include_usage",true)) }.toString()
}

/**
 * 三家线协议共用的流式请求面：请求体自编码（协议方言各自负责），容量预留供
 * [ChatCompletionCall] 的闸门计量。OpenAI 兼容线是 [StreamRequest]，anthropic/gemini
 * 原生线见各自 Wire 文件。
 */
interface CompletionStreamRequest {
    val outputReserve: Long
    fun encode(): String
}

/** 流事件，块顺序即服务端事件顺序；同一 data 行内先思考增量，再文本增量，再工具分片，最后用量。 */
sealed interface StreamChunk {
    data class TextDelta(val text: String) : StreamChunk
    data class ThinkingDelta(val text: String) : StreamChunk
    /**
     * 工具调用分片。契约：解析器只吐分片，拼装由 [StreamAssembler] 负责——
     * id/name 仅在服务端首次给出的分片上非空（后续分片省略），argumentsDelta 是
     * 参数串的增量片段，按 index 归组、按到达顺序拼接即得完整调用。
     * signature 是 Gemini 3.x 的 thoughtSignature 回放令牌：仅随 functionCall
     * 整块到达的分片携带，其余协议恒为 null，拼装时原样透传到 [PendingTool]。
     */
    data class ToolCallDelta(val index: Int, val id: String?, val name: String?, val argumentsDelta: String,
                             val signature: String? = null) : StreamChunk
    data class Usage(val inputTokens: Long?, val outputTokens: Long?) : StreamChunk
    /**
     * 模型产出的内联媒体（Gemini 图像/音频输出机型的 inlineData 部分）。
     * base64 原样携带，解码归调用方；本层不落盘不解码。
     */
    data class MediaAttachment(val mimeType: String, val base64: String) : StreamChunk
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

internal fun JSONObject.streamText(key: String): String = textOf(this,key)
internal fun JSONObject.streamCount(key: String): Long? = countOf(this,key)

/**
 * SSE 逐行状态机（协议无关骨架）：喂入任意切割的原始文本（含半行），吐出完整行
 * 解析出的事件。容错：CRLF 行尾、空行、`:` 注释（心跳）行、`data:` 无前导空格、
 * 非 JSON 数据行（丢弃不崩）、跨喂入调用的半行缓冲。进入终态（Done/Failure）后
 * 忽略一切后续输入。协议方言实现 [decodePayload]，并声明两处差异：
 *  - [acceptsDoneSentinel]：OpenAI 兼容线认 `data: [DONE]` 哨兵；anthropic 以
 *    message_stop 事件收尾、gemini 以流的干净结束收尾，均无哨兵；
 *  - [shouldFinishAtEndOfStream]：干净断流（无哨兵 EOF）是否补发收尾。OpenAI /
 *    anthropic 只在见过终止语义（finish_reason / stop_reason）时补，gemini 恒补
 *    （其协议以服务端关流为正常收尾，缺省收尾原因记 end_turn）。
 */
internal abstract class SseLineDecoder {
    private val pending=StringBuilder()
    protected var finishReason:String?=null
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
    /** 流结束：冲掉缓冲里残留的半行（截断 JSON 自然解析失败被丢弃），并按方言决定是否补发收尾。 */
    fun finish(): List<StreamChunk> {
        if(done||failed) return emptyList()
        val tail=pending.toString().removeSuffix("\r");pending.clear()
        val events=decodeLine(tail).toMutableList()
        if(!done && !failed && shouldFinishAtEndOfStream()) { done=true;events+=StreamChunk.Done(endOfStreamDoneReason()) }
        return events
    }
    private fun decodeLine(raw: String): List<StreamChunk> {
        if(raw.isBlank()||raw.startsWith(":")) return emptyList()
        if(!raw.startsWith("data:")) return emptyList() // event:/id:/retry: 等其余 SSE 字段与本协议无关
        val payload=raw.removePrefix("data:").let { if(it.startsWith(" ")) it.removePrefix(" ") else it } // 只剥一个前导空格（HTML5 SSE 规则，兼容无空格服务端）
        if(acceptsDoneSentinel && payload.trim()=="[DONE]") { done=true;return listOf(StreamChunk.Done(finishReason)) }
        val event=try { JSONObject(payload) } catch(_:Exception) { return emptyList() }
        return decodePayload(event)
    }
    /** 协议方言：一个 data 载荷对象 → 若干流块。终态经 [markDone]/[markFailed] 上报。 */
    protected abstract fun decodePayload(event: JSONObject): List<StreamChunk>
    protected open val acceptsDoneSentinel: Boolean get()=true
    /** 干净断流时是否补发 Done；默认仅当已见过终止语义。 */
    protected open fun shouldFinishAtEndOfStream():Boolean=finishReason!=null
    /** 干净断流补发 Done 的收尾原因；默认取已见过的终止语义，gemini 方言缺省 end_turn。 */
    protected open fun endOfStreamDoneReason():String?=finishReason
    protected fun markDone(){done=true}
    protected fun markFailed(){failed=true}
}

/**
 * OpenAI Chat Completions 方言：choices[0].delta 的 reasoning/content/tool_calls、
 * 末块 usage、`data: [DONE]` 哨兵。
 */
internal class SseDecoder : SseLineDecoder() {
    override fun decodePayload(event: JSONObject): List<StreamChunk> {
        val out=mutableListOf<StreamChunk>()
        event.optJSONObject("error")?.let { error ->
            markFailed()
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
 * 工具调用（按 index 归组拼装 id/name/arguments/signature）、用量与收尾原因。块只
 * 喂一遍即可取值；内联媒体块不参与聚合（媒体语义归调用方，[media] 按序留档）。
 */
class StreamAssembler {
    private val textBuffer=StringBuilder()
    private val thinkingBuffer=StringBuilder()
    private class ToolAssembly { var id="";var name="";var signature:String?=null;val arguments=StringBuilder() }
    private val calls=java.util.TreeMap<Int,ToolAssembly>()
    private val mediaItems=mutableListOf<StreamChunk.MediaAttachment>()
    var usage: StreamChunk.Usage?=null;private set
    var finishReason: String?=null;private set
    var failure: StreamChunk.Failure?=null;private set
    val text:String get()=textBuffer.toString()
    val thinking:String get()=thinkingBuffer.toString()
    val media:List<StreamChunk.MediaAttachment> get()=mediaItems
    /** 按 index 顺序输出；id 或 name 缺失（无法回传服务端）的调用被丢弃。 */
    val toolCalls:List<PendingTool> get()=calls.values.filter { it.id.isNotBlank() && it.name.isNotBlank() }
        .map { PendingTool(it.id,it.name,it.arguments.toString(),it.signature) }
    fun accept(chunk: StreamChunk) {
        when(chunk) {
            is StreamChunk.TextDelta -> textBuffer.append(chunk.text)
            is StreamChunk.ThinkingDelta -> thinkingBuffer.append(chunk.text)
            is StreamChunk.ToolCallDelta -> {
                val assembly=calls.getOrPut(chunk.index) { ToolAssembly() }
                chunk.id?.let { assembly.id=it }
                chunk.name?.let { assembly.name=it }
                chunk.signature?.let { assembly.signature=it }
                assembly.arguments.append(chunk.argumentsDelta)
            }
            is StreamChunk.Usage -> usage=chunk
            is StreamChunk.MediaAttachment -> mediaItems+=chunk
            is StreamChunk.Done -> finishReason=chunk.finishReason
            is StreamChunk.Failure -> failure=chunk
        }
    }
}
