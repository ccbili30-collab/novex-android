package novex.runtime

import novex.storage.Utf8Files

import novex.content.*
import novex.storage.StagedContentFiles
import novex.model.*
import novex.conversation.*
import org.json.JSONObject
import org.json.JSONArray
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LoopBudget(val model:ModelCapacity,val selectedWindow:Long,val maximumRequests:Int) {
    init { require(selectedWindow>0 && maximumRequests>0) }
}
data class PausedLoop(val chatId:String,val turnId:String,val version:String,val budget:LoopBudget,val state:ToolLoopResult)
class LoopCheckpointConflict:IllegalStateException("运行记录已更新，本次未覆盖新进度")

/** 保存明确暂停点；消息正文和工具参数按内容复用，元数据不反复嵌入整段历史。 */
class PausedLoopStore(root:Path) {
    private val root=root.toAbsolutePath().normalize()
    private val contents=StagedContentFiles(this.root.resolve("contents"))
    private val mutex:Any
    init { Files.createDirectories(this.root);mutex=locks.computeIfAbsent(this.root.toRealPath()){Any()} }
    fun load(chat:String,turn:String):PausedLoop?=locked { read(chat,turn) }
    fun save(chat:String,turn:String,expectedVersion:String?,budget:LoopBudget,state:ToolLoopResult):PausedLoop=locked {
        require(chat.isNotBlank() && turn.isNotBlank())
        require(state is ToolLoopResult.Waiting || state is ToolLoopResult.BudgetReached){"只保存明确暂停点，不把未确认网络状态当成可重试"}
        if(read(chat,turn)?.version!=expectedVersion)throw LoopCheckpointConflict()
        val version=UUID.randomUUID().toString()
        val request=when(state){is ToolLoopResult.Waiting->state.pending.original;is ToolLoopResult.BudgetReached->state.next;else->error("暂停类型无效")}
        val count=when(state){is ToolLoopResult.Waiting->state.requests;is ToolLoopResult.BudgetReached->state.requests;else->0}
        require(count in 0..budget.maximumRequests)
        val json=JSONObject().put("schema",1).put("chat",chat).put("turn",turn).put("version",version)
            .put("kind",if(state is ToolLoopResult.Waiting)"approval" else "budget").put("requests",count)
            .put("budget",JSONObject().put("window",budget.selectedWindow).put("maximum",budget.maximumRequests)
                .put("modelWindow",budget.model.contextWindow?:JSONObject.NULL).put("modelOutput",budget.model.maximumOutput?:JSONObject.NULL))
            .put("request",request(request)).put("outcomes",outcomes(state.outcomes))
        if(state is ToolLoopResult.Waiting)json.put("pending",JSONObject().put("text",blob(state.pending.response.text))
            .put("calls",calls(state.pending.response.calls)).put("outcomes",outcomes(state.pending.outcomes)))
        val pending=root.resolve("pending-$version")
        try {
            FileChannel.open(pending,CREATE_NEW,WRITE).use { channel ->
                val buffer=java.nio.ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8));while(buffer.hasRemaining())channel.write(buffer);channel.force(true)
            }
            Files.move(pending,path(chat,turn),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {Files.deleteIfExists(pending)}
        requireNotNull(read(chat,turn))
    }
    private fun request(request:TextRequest)=JSONObject().put("model",request.model).put("reserve",request.outputReserve)
        .put("messages",JSONArray(request.messages.map { message -> JSONObject().put("role",message.role).put("text",blob(message.text))
            .put("call",message.toolCallId?:JSONObject.NULL).put("calls",calls(message.toolCalls)).put("images",JSONArray(message.images.map {JSONObject().put("type",it.mediaType).put("body",blob(it.base64))})) }))
        .put("tools",JSONArray(request.tools.map { JSONObject().put("name",it.name).put("description",blob(it.description)).put("parameters",blob(it.parameters)) }))
    private fun request(value:JSONObject)=TextRequest(value.getString("model"),objects(value.getJSONArray("messages")).map {
        WireMessage(it.getString("role"),text(it.getString("text")),calls(it.getJSONArray("calls")),if(it.isNull("call"))null else it.getString("call"),it.optJSONArray("images")?.let {images->objects(images).map {image->novex.model.WireImage(image.getString("type"),text(image.getString("body")))}}?:emptyList())
    },value.getLong("reserve"),objects(value.getJSONArray("tools")).map { ToolDefinition(it.getString("name"),text(it.getString("description")),text(it.getString("parameters"))) })
    private fun calls(calls:List<PendingTool>)=JSONArray(calls.map { JSONObject().put("id",it.id).put("name",it.name).put("arguments",blob(it.arguments)) })
    private fun calls(value:JSONArray)=objects(value).map { PendingTool(it.getString("id"),it.getString("name"),text(it.getString("arguments"))) }
    private fun outcomes(values:List<ToolOutcome>)=JSONArray(values.map { JSONObject().put("call",calls(listOf(it.call)).getJSONObject(0)).put("result",blob(CardToolProtocol.result(it.result))).put("images",JSONArray(it.images.map {image->JSONObject().put("type",image.mediaType).put("body",blob(image.base64))})) })
    private fun outcomes(values:JSONArray)=objects(values).map { value ->
        val raw=text(value.getString("result"));val result=JSONObject(raw)
        val images=value.optJSONArray("images")?.let {array->objects(array).map {image->WireImage(image.getString("type"),text(image.getString("body")))}}?:emptyList()
        ToolOutcome(calls(JSONArray().put(value.getJSONObject("call"))).single(),ToolResultCodec.decode(result),images)
    }

    private fun read(chat:String,turn:String):PausedLoop? {
        val file=path(chat,turn);if(!Files.exists(file))return null
        val json=JSONObject(Utf8Files.read(file));require(json.getInt("schema")==1 && json.getString("chat")==chat && json.getString("turn")==turn)
        val data=json.getJSONObject("budget")
        fun optional(key:String)=if(data.isNull(key))null else data.getLong(key)
        val budget=LoopBudget(ModelCapacity(optional("modelWindow"),optional("modelOutput")),data.getLong("window"),data.getInt("maximum"))
        val request=request(json.getJSONObject("request"));request.encode()
        val total=outcomes(json.getJSONArray("outcomes"));val count=json.getInt("requests");require(count in 0..budget.maximumRequests)
        val state=when(json.getString("kind")) {
            "budget"->ToolLoopResult.BudgetReached(request,count,total)
            "approval"->{
                val pending=json.getJSONObject("pending");val response=ModelResult.ToolsRequested(text(pending.getString("text")),calls(pending.getJSONArray("calls")))
                ToolLoopResult.Waiting(ToolRoundResult.Waiting(request,response,outcomes(pending.getJSONArray("outcomes"))),count,total)
            }
            else->error("暂停类型不支持")
        }
        return PausedLoop(chat,turn,json.getString("version"),budget,state)
    }
    private fun blob(value:String):String {
        val bytes=value.toByteArray(Charsets.UTF_8);val hash=hash(bytes);val ref=ContentRef("$hash/text")
        if(!Files.exists(root.resolve("contents/batches/$hash/manifest")))contents.receive(listOf(ContentTransfer(ContentRef("checkpoint"),ref))){ByteArrayInputStream(bytes)}
        return ref.value
    }
    private fun text(ref:String)=contents.open(ContentRef(ref)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun path(chat:String,turn:String)=root.resolve("loop-"+hash("${chat.length}:$chat$turn".toByteArray(Charsets.UTF_8))+".json")
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun objects(array:JSONArray)=(0 until array.length()).map(array::getJSONObject)
    private fun <T> locked(action:()->T):T=synchronized(mutex){FileChannel.open(root.resolve("write.lock"),CREATE,WRITE).use { it.lock().use { action() } }}
    companion object {private val locks=ConcurrentHashMap<Path,Any>()}
}
