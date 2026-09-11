package novex.model

import novex.conversation.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** 凭据不进入数据类的自动文本输出。 */
class ModelEndpoint(val completionUrl: URI, private val token: String?) {
    init {
        require(completionUrl.userInfo == null && completionUrl.fragment == null && completionUrl.query == null)
        require(completionUrl.scheme == "https" || (completionUrl.scheme == "http" && completionUrl.host in setOf("127.0.0.1","localhost","::1")))
        require(token == null || ('\r' !in token && '\n' !in token))
    }
    internal fun authorize(connection: HttpURLConnection) { if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization","Bearer $token") }
}
data class WireImage(val mediaType:String,val base64:String) {
    init {require(mediaType in setOf("image/png","image/jpeg","image/webp","image/gif"));require(base64.isNotBlank())}
    internal fun encode()=JSONObject().put("type","image_url").put("image_url",JSONObject().put("url","data:$mediaType;base64,$base64"))
    override fun toString()="WireImage（图片内容隐藏）"
}
data class WireMessage(val role: String, val text: String, val toolCalls: List<PendingTool> = emptyList(), val toolCallId: String? = null,val images:List<WireImage> = emptyList()) {
    init {
        require(role in setOf("system","user","assistant","tool"))
        require(images.isEmpty() || role=="user")
        require(toolCalls.isEmpty() || role=="assistant")
        require(if(role=="tool")!toolCallId.isNullOrBlank() else toolCallId==null)
        require(toolCalls.map { it.id }.distinct().size==toolCalls.size)
    }
    internal fun encode():JSONObject = JSONObject().put("role",role).put("content",if(images.isEmpty())text else JSONArray().put(JSONObject().put("type","text").put("text",text)).also {parts->images.forEach {parts.put(it.encode())}}).also { message ->
        if(toolCallId!=null)message.put("tool_call_id",toolCallId)
        if(toolCalls.isNotEmpty())message.put("tool_calls",JSONArray(toolCalls.map { call ->
            require(call.id.isNotBlank() && call.name.isNotBlank());JSONObject(call.arguments)
            JSONObject().put("id",call.id).put("type","function").put("function",JSONObject().put("name",call.name).put("arguments",call.arguments))
        }))
    }
}
data class ToolDefinition(val name:String,val description:String,val parameters:String) {
    init { require(name.isNotBlank() && description.isNotBlank());require(JSONObject(parameters).getString("type")=="object") }
    internal fun encode()=JSONObject().put("type","function").put("function",JSONObject().put("name",name).put("description",description).put("parameters",JSONObject(parameters)))
}
data class TextRequest(val model: String, val messages: List<WireMessage>, val outputReserve: Long, val tools:List<ToolDefinition> = emptyList()) {
    init { require(model.isNotBlank() && messages.isNotEmpty() && outputReserve > 0);require(tools.map { it.name }.distinct().size==tools.size) }
    fun encode(): String {
        val pending=mutableSetOf<String>()
        messages.forEach { message ->
            if(message.role=="tool")require(pending.remove(message.toolCallId)){"工具结果没有对应的待处理调用"}
            else {
                require(pending.isEmpty()){"上一批工具调用尚未补齐结果"}
                pending.addAll(message.toolCalls.map { it.id })
            }
        }
        require(pending.isEmpty()){"工具调用结果尚未齐全，不能继续请求"}
        return JSONObject().put("model",model).put("stream",false).put("max_tokens",outputReserve)
            .put("messages",JSONArray(messages.map { it.encode() })).also {
                if(tools.isNotEmpty())it.put("tools",JSONArray(tools.map { tool -> tool.encode() }))
            }.toString()
    }
}
data class PendingTool(val id: String,val name: String,val arguments: String)
sealed interface ModelResult {
    data class Reply(val text: String,val inputTokens: Long?,val outputTokens: Long?) : ModelResult
    data class Partial(val text: String,val reason: String) : ModelResult
    data class ToolsRequested(val text: String,val calls: List<PendingTool>) : ModelResult
    data class Rejected(val status: Int,val category: String,val retryAfter: String?) : ModelResult
    data class InvalidResponse(val reason: String) : ModelResult
    data class NotSent(val capacity: CapacityDecision) : ModelResult
    data object NetworkFailure : ModelResult
    data object TimedOut : ModelResult
    data object Cancelled : ModelResult
}

/** 一次调用，无隐式重试。当前为非流式文字协议，工具请求只能返回待处理事实。 */
class ChatCompletionCall(private val endpoint: ModelEndpoint,private val timeoutMillis: Int = 30_000) {
    @Volatile var networkAttempted:Boolean=false
        private set
    private val started=AtomicBoolean(false)
    private val cancelled=AtomicBoolean(false)
    @Volatile private var active:HttpURLConnection?=null
    init { require(timeoutMillis > 0) }
    fun cancel() { cancelled.set(true);active?.disconnect() }

    fun execute(request: TextRequest, modelCapacity: ModelCapacity, selectedWindow: Long,
                measureCompletePayload: (String) -> TokenMeasurement): ModelResult {
        check(started.compareAndSet(false,true)) { "同一次请求不能重复执行" }
        if (cancelled.get()) return ModelResult.Cancelled
        val body=request.encode()
        val decision=RequestCapacity.check(modelCapacity,selectedWindow,request.outputReserve,measureCompletePayload(body))
        if (decision !is CapacityDecision.Fits) return ModelResult.NotSent(decision)
        var connection:HttpURLConnection?=null
        try {
            connection=endpoint.completionUrl.toURL().openConnection() as HttpURLConnection
            active=connection
            if(cancelled.get()) return ModelResult.Cancelled
            connection.instanceFollowRedirects=false
            connection.requestMethod="POST";connection.doOutput=true
            connection.connectTimeout=timeoutMillis;connection.readTimeout=timeoutMillis
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
            endpoint.authorize(connection)
            val bytes=body.toByteArray(Charsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.size)
            networkAttempted=true
            connection.outputStream.use { it.write(bytes) }
            val status=connection.responseCode
            if(cancelled.get()) return ModelResult.Cancelled
            if(status !in 200..299) return ModelResult.Rejected(status,when(status) {
                401,403 -> "authentication";429 -> "rate_limit";in 300..399 -> "redirect";in 500..599 -> "service";else -> "request"
            },connection.getHeaderField("Retry-After"))
            val response=connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if(cancelled.get()) return ModelResult.Cancelled
            return decode(response)
        } catch(failure:SocketTimeoutException) { return if(cancelled.get()) ModelResult.Cancelled else ModelResult.TimedOut
        } catch(failure:java.io.IOException) { return if(cancelled.get()) ModelResult.Cancelled else ModelResult.NetworkFailure
        } finally { active=null;connection?.disconnect() }
    }

    private fun decode(body:String):ModelResult = try {
        val root=JSONObject(body);val choices=root.getJSONArray("choices")
        require(choices.length()==1) { "回复数量不符合当前单回复请求" }
        val choice=choices.getJSONObject(0);val message=choice.getJSONObject("message")
        val text=if(message.isNull("content")) "" else message.getString("content")
        val calls=(if(message.has("tool_calls")) message.getJSONArray("tool_calls") else null)?.let { items -> (0 until items.length()).map { index ->
            val call=items.getJSONObject(index);require(call.getString("type")=="function")
            val function=call.getJSONObject("function")
            PendingTool(call.getString("id"),function.getString("name"),function.getString("arguments")).also {
                require(it.id.isNotBlank() && it.name.isNotBlank());JSONObject(it.arguments)
            }
        } }.orEmpty()
        require(calls.map { it.id }.distinct().size==calls.size)
        when {
            calls.isNotEmpty() -> ModelResult.ToolsRequested(text,calls)
            choice.optString("finish_reason")!="stop" -> ModelResult.Partial(text,choice.optString("finish_reason","unknown"))
            !message.isNull("refusal") -> ModelResult.Partial(text,"refusal")
            text.isBlank() -> ModelResult.InvalidResponse("服务没有返回可用回复")
            else -> {
                val usage=root.optJSONObject("usage")
                fun count(key:String):Long?=if(usage==null || usage.isNull(key)) null else usage.getLong(key).also { require(it>=0) }
                ModelResult.Reply(text,count("prompt_tokens"),count("completion_tokens"))
            }
        }
    } catch(failure:Exception) { ModelResult.InvalidResponse("服务回复格式不完整或尚不支持") }
}
