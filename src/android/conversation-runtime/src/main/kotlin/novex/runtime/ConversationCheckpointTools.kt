package novex.runtime

import novex.model.*
import org.json.*
import java.security.MessageDigest

/** 保存动作共用人工存档入口；调用编号决定存档编号，重试不能生成另一份。 */
class ConversationCheckpointTools(private val store:ConversationCheckpoints,private val journal:TurnJournal,
    private val chat:String,private val message:String,private val save:(String,String)->SavedCheckpoint) {
    fun definitions(permission:ToolPermission)=if(permission==ToolPermission.READ_ONLY)emptyList() else listOf(definition)
    internal fun completed(namespace:String,call:PendingTool):CardToolResult? {
        val record=journal.read(namespace,call.id)?:return null
        val id=parse(namespace,call)
        require(journal.text(record.input)==input(call)){"工具编号已对应其他操作"}
        if(record.state==TurnState.QUEUED)return null
        if(record.state==TurnState.RUNNING)return CardToolResult.Unconfirmed
        return recorded(namespace,call,id,record)
    }
    fun submit(namespace:String,call:PendingTool,permission:ToolPermission,stop:ToolStop=ToolStop()):CardToolResult {
        if(permission==ToolPermission.READ_ONLY)return CardToolResult.Denied
        val id=parse(namespace,call);val record=journal.enqueue(namespace,call.id,input(call))
        if(record.state!=TurnState.QUEUED)return recorded(namespace,call,id,record)
        return if(permission==ToolPermission.APPROVAL)CardToolResult.AwaitingApproval else execute(namespace,call,id,stop)
    }
    fun confirm(namespace:String,call:PendingTool,permission:ToolPermission,stop:ToolStop=ToolStop()):CardToolResult {
        val id=parse(namespace,call);val record=requireNotNull(journal.read(namespace,call.id));require(journal.text(record.input)==input(call))
        if(record.state!=TurnState.QUEUED)return recorded(namespace,call,id,record)
        if(permission==ToolPermission.READ_ONLY)return reject(namespace,call)
        if(permission==ToolPermission.APPROVAL && !record.selected)return CardToolResult.AwaitingApproval
        return execute(namespace,call,id,stop)
    }
    fun select(namespace:String,call:PendingTool,value:Boolean) {
        parse(namespace,call);val record=requireNotNull(journal.read(namespace,call.id));require(journal.text(record.input)==input(call));journal.select(namespace,call.id,value)
    }
    fun reject(namespace:String,call:PendingTool):CardToolResult {
        val id=parse(namespace,call)
        return recorded(namespace,call,id,journal.finishQueued(namespace,call.id,input(call),JSONObject(CardToolProtocol.result(CardToolResult.Denied))))
    }
    private fun execute(namespace:String,call:PendingTool,id:String,stop:ToolStop):CardToolResult {
        journal.claim(namespace,call.id)?:return recorded(namespace,call,id,requireNotNull(journal.read(namespace,call.id)))
        if(stop.isStopped())return finish(namespace,call,CardToolResult.Stopped(null))
        try {
            val saved=save(id,name(call.arguments))
            check(saved.chat==chat && saved.id==id && saved.name==name(call.arguments) && store.read(chat,id)==saved){"存档尚未登记"}
        }catch(failure:IllegalArgumentException){return finish(namespace,call,CardToolResult.Failed(failure.message?:"存档参数无效"))}
        catch(_:Exception){return CardToolResult.Unconfirmed}
        return finish(namespace,call,CardToolResult.CheckpointSaved(chat,id))
    }
    private fun recorded(namespace:String,call:PendingTool,id:String,record:StoredTurn):CardToolResult {
        require(journal.text(record.input)==input(call))
        if(record.state==TurnState.FINISHED) {
            val result=JSONObject(journal.text(requireNotNull(record.outcome)))
            when(result.getString("status")) {
                "denied"->return CardToolResult.Denied
                "stopped"->return CardToolResult.Stopped(null)
                "failed"->return CardToolResult.Failed(result.getString("reason"))
                "checkpoint_saved"->Unit
                else->error("存档回执无效")
            }
        }
        val saved=store.read(chat,id)
        if(saved!=null && saved.name==name(call.arguments)) {
            val result=CardToolResult.CheckpointSaved(chat,id)
            return if(record.state==TurnState.FINISHED)result else finish(namespace,call,result)
        }
        return CardToolResult.Unconfirmed
    }
    private fun parse(namespace:String,call:PendingTool):String {
        require(namespace=="${chat.length}:$chat${message.length}:$message" && call.name=="save_conversation_checkpoint"){"存档操作不属于当前对话"}
        name(call.arguments)
        return MessageDigest.getInstance("SHA-256").digest("${namespace.length}:$namespace${call.id.length}:${call.id}".toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun input(call:PendingTool)=JSONObject().put("chat",chat).put("message",message).put("arguments",call.arguments).toString()
    private fun finish(namespace:String,call:PendingTool,result:CardToolResult):CardToolResult {journal.finish(namespace,call.id,JSONObject(CardToolProtocol.result(result)));return result}
    companion object {
        fun name(arguments:String):String {val args=JSONObject(arguments);require(args.keys().asSequence().toSet()==setOf("name") && args.get("name") is String);return args.getString("name").trim().also {require(it.isNotEmpty()){"存档名称不能为空"}}}
        val definition=ToolDefinition("save_conversation_checkpoint","将当前对话已有记录、状态、快捷操作、来源作品和当前设置保存为命名存档。只需名称，由软件复制原始内容；不接受模型编写的摘要替代原文。不包含此操作之后产生的回复，成功以 checkpoint_saved 回执为准。",
            JSONObject().put("type","object").put("properties",JSONObject().put("name",JSONObject().put("type","string").put("description","存档名称"))).put("required",JSONArray(listOf("name"))).put("additionalProperties",false).toString())
    }
}
