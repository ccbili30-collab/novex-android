package novex.runtime

import novex.conversation.ControlRegistration
import novex.model.PendingTool
import org.json.JSONObject
import java.security.MessageDigest

/** 当前对话及回复由宿主绑定，工具参数不能切换目标。使用独立工具日志。 */
class ConversationControlTools(private val store:ConversationControlStore,private val journal:TurnJournal,
                               private val chat:String,private val message:String) {
    init {require(chat.isNotBlank() && message.isNotBlank())}
    fun definitions(permission:ToolPermission)=if(permission==ToolPermission.READ_ONLY)emptyList() else listOf(ConversationControlProtocol.definition)
    internal fun completed(namespace:String,call:PendingTool):CardToolResult? {
        val record=journal.read(namespace,call.id)?:return null
        val registration=parse(namespace,call)
        require(journal.text(record.input)==input(call)){"工具编号已对应其他操作"}
        if(record.state==TurnState.QUEUED)return null
        if(record.state==TurnState.RUNNING)return CardToolResult.Unconfirmed
        return recorded(namespace,call,registration,record)
    }
    fun submit(namespace:String,call:PendingTool,permission:ToolPermission,stop:ToolStop=ToolStop()):CardToolResult {
        if(permission==ToolPermission.READ_ONLY)return CardToolResult.Denied
        val registration=try{parse(namespace,call)}catch(failure:Exception){return CardToolResult.Failed(failure.message?:"注册参数无效")}
        val record=journal.enqueue(namespace,call.id,input(call))
        if(record.state!=TurnState.QUEUED)return recorded(namespace,call,registration,record)
        return if(permission==ToolPermission.APPROVAL)CardToolResult.AwaitingApproval else execute(namespace,call,registration,stop)
    }
    /** 只供用户选择入口，不向模型暴露。 */
    fun select(namespace:String,call:PendingTool,selected:Boolean) {
        parse(namespace,call)
        val record=requireNotNull(journal.read(namespace,call.id));require(journal.text(record.input)==input(call))
        journal.select(namespace,call.id,selected)
    }
    fun confirm(namespace:String,call:PendingTool,permission:ToolPermission,stop:ToolStop=ToolStop()):CardToolResult {
        val registration=parse(namespace,call)
        val record=requireNotNull(journal.read(namespace,call.id));require(journal.text(record.input)==input(call))
        if(record.state!=TurnState.QUEUED)return recorded(namespace,call,registration,record)
        if(permission==ToolPermission.READ_ONLY)return reject(namespace,call)
        if(permission==ToolPermission.APPROVAL && !record.selected)return CardToolResult.AwaitingApproval
        return execute(namespace,call,registration,stop)
    }
    fun reject(namespace:String,call:PendingTool):CardToolResult {
        val registration=parse(namespace,call)
        val record=journal.finishQueued(namespace,call.id,input(call),JSONObject(CardToolProtocol.result(CardToolResult.Denied)))
        return recorded(namespace,call,registration,record)
    }
    private fun parse(namespace:String,call:PendingTool):ControlRegistration {
        require(namespace.startsWith("${chat.length}:$chat")){"工具运行不属于当前对话"}
        require(call.name=="register_controls")
        val identity="${namespace.length}:$namespace${call.id.length}:${call.id}"
        val id=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        return ConversationControlProtocol.parse(id,message,call.arguments)
    }
    private fun input(call:PendingTool)=JSONObject().put("chat",chat).put("message",message).put("arguments",call.arguments).toString()
    private fun execute(namespace:String,call:PendingTool,registration:ControlRegistration,stop:ToolStop):CardToolResult {
        journal.claim(namespace,call.id)?:return recorded(namespace,call,registration,requireNotNull(journal.read(namespace,call.id)))
        if(stop.isStopped())return finish(namespace,call,CardToolResult.Stopped(null))
        try {store.register(chat,store.read(chat).version,registration)}
        catch(failure:IllegalArgumentException){return finish(namespace,call,CardToolResult.Failed(failure.message?:"注册参数无效"))}
        catch(failure:IllegalStateException){return finish(namespace,call,CardToolResult.Failed(failure.message?:"注册内容已变化"))}
        catch(_:Exception){return CardToolResult.Unconfirmed}
        return finish(namespace,call,CardToolResult.Registered(chat,registration.id))
    }
    private fun recorded(namespace:String,call:PendingTool,registration:ControlRegistration,record:StoredTurn):CardToolResult {
        require(journal.text(record.input)==input(call))
        if(record.state==TurnState.FINISHED) {
            val result=JSONObject(journal.text(record.outcome!!))
            return when(result.getString("status")) {
                "denied"->CardToolResult.Denied
                "registered"->CardToolResult.Registered(result.getString("conversation_id"),result.getString("registration_id"))
                "stopped"->CardToolResult.Stopped(null)
                "failed"->CardToolResult.Failed(result.getString("reason"))
                else->error("注册结果无效")
            }
        }
        if(store.registered(chat,registration.id)==registration)return finish(namespace,call,CardToolResult.Registered(chat,registration.id))
        return CardToolResult.Unconfirmed
    }
    private fun finish(namespace:String,call:PendingTool,result:CardToolResult):CardToolResult {
        journal.finish(namespace,call.id,JSONObject(CardToolProtocol.result(result)));return result
    }
}
