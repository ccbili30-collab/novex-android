package novex.runtime

import novex.model.*
import org.json.*
import java.security.MessageDigest

class ConversationStateTools(private val store:ConversationStateStore,private val journal:TurnJournal,private val chat:String,private val message:String,private val activePath:(()->List<String>)?=null) {
    fun definitions(permission:ToolPermission)=if(permission==ToolPermission.READ_ONLY)emptyList() else listOf(definition)+(if(activePath!=null)listOf(readDefinition) else emptyList())
    fun read(call:PendingTool,permission:ToolPermission):CardToolResult {
        if(permission==ToolPermission.READ_ONLY)return CardToolResult.Denied
        require(call.name=="read_conversation_state"){"状态读取工具不匹配"}
        val args=JSONObject(call.arguments)
        require(args.keys().asSequence().toSet()==setOf("keys")){"读取只接受状态字段列表"}
        val list=args.getJSONArray("keys")
        val keys=(0 until list.length()).map {require(list.get(it) is String);list.getString(it).also {key->require(key.isNotBlank())}}
        require(keys.distinct().size==keys.size){"读取字段重复"}
        val path=requireNotNull(activePath){"当前对话路径尚未接通"}.invoke()
        val values=store.view(chat,path,keys)
        val result=JSONObject().put("status","read").put("source","conversation_records")
            .put("values",JSONObject().apply {values.forEach {put(it.key,JSONArray("[${it.valueJson}]").get(0))}})
            .put("missing",JSONArray(keys.filter {key->values.none {it.key==key}}))
        return CardToolResult.Read(result.toString())
    }
    internal fun completed(namespace:String,call:PendingTool):CardToolResult? {
        val record=journal.read(namespace,call.id)?:return null
        val event=parse(namespace,call)
        require(journal.text(record.input)==input(call)){"工具编号已对应其他操作"}
        if(record.state==TurnState.QUEUED)return null
        if(record.state==TurnState.RUNNING)return CardToolResult.Unconfirmed
        return recorded(namespace,call,event,record)
    }
    fun submit(namespace:String,call:PendingTool,permission:ToolPermission,stop:ToolStop=ToolStop()):CardToolResult {
        if(permission==ToolPermission.READ_ONLY)return CardToolResult.Denied
        val event=parse(namespace,call);val record=journal.enqueue(namespace,call.id,input(call))
        if(record.state!=TurnState.QUEUED)return recorded(namespace,call,event,record)
        return if(permission==ToolPermission.APPROVAL)CardToolResult.AwaitingApproval else execute(namespace,call,event,stop)
    }
    fun confirm(namespace:String,call:PendingTool,permission:ToolPermission,stop:ToolStop=ToolStop()):CardToolResult {
        val event=parse(namespace,call);val record=requireNotNull(journal.read(namespace,call.id));require(journal.text(record.input)==input(call))
        if(record.state!=TurnState.QUEUED)return recorded(namespace,call,event,record)
        if(permission==ToolPermission.READ_ONLY)return reject(namespace,call)
        if(permission==ToolPermission.APPROVAL && !record.selected)return CardToolResult.AwaitingApproval
        return execute(namespace,call,event,stop)
    }
    fun select(namespace:String,call:PendingTool,value:Boolean) {
        parse(namespace,call);val record=requireNotNull(journal.read(namespace,call.id));require(journal.text(record.input)==input(call));journal.select(namespace,call.id,value)
    }
    fun reject(namespace:String,call:PendingTool):CardToolResult {
        val event=parse(namespace,call)
        return recorded(namespace,call,event,journal.finishQueued(namespace,call.id,input(call),JSONObject(CardToolProtocol.result(CardToolResult.Denied))))
    }
    private fun execute(namespace:String,call:PendingTool,event:StateEvent,stop:ToolStop):CardToolResult {
        journal.claim(namespace,call.id)?:return recorded(namespace,call,event,requireNotNull(journal.read(namespace,call.id)))
        if(stop.isStopped())return finish(namespace,call,CardToolResult.Stopped(null))
        try {store.record(chat,event)}catch(failure:IllegalArgumentException){return finish(namespace,call,CardToolResult.Failed(failure.message?:"状态参数无效"))}
        catch(_:Exception){return CardToolResult.Unconfirmed}
        return finish(namespace,call,CardToolResult.StateSaved(chat,event.id))
    }
    private fun recorded(namespace:String,call:PendingTool,event:StateEvent,record:StoredTurn):CardToolResult {
        require(journal.text(record.input)==input(call))
        if(record.state==TurnState.FINISHED) {
            val result=JSONObject(journal.text(record.outcome!!))
            return when(result.getString("status")){"state_saved"->CardToolResult.StateSaved(chat,event.id);"denied"->CardToolResult.Denied;"stopped"->CardToolResult.Stopped(null);"failed"->CardToolResult.Failed(result.getString("reason"));else->error("状态回执无效")}
        }
        if(store.event(chat,event.id)==event)return finish(namespace,call,CardToolResult.StateSaved(chat,event.id))
        return CardToolResult.Unconfirmed
    }
    private fun parse(namespace:String,call:PendingTool):StateEvent {
        require(namespace.startsWith("${chat.length}:$chat") && call.name=="update_playthrough_state"){"状态操作不属于当前对话"}
        val id=MessageDigest.getInstance("SHA-256").digest("${namespace.length}:$namespace${call.id.length}:${call.id}".toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        return StateEvent(id,message,updates(call.arguments))
    }
    private fun input(call:PendingTool)=JSONObject().put("chat",chat).put("message",message).put("arguments",call.arguments).toString()
    private fun finish(namespace:String,call:PendingTool,result:CardToolResult):CardToolResult {journal.finish(namespace,call.id,JSONObject(CardToolProtocol.result(result)));return result}
    companion object {
        val readDefinition=ToolDefinition("read_conversation_state","按需读取当前对话路径已保存的状态记录，不修改状态或卡片。返回值属于对话记录，不是软件对其真实性的验证；missing 中的字段尚无记录，不能视为零或否。",
            JSONObject().put("type","object").put("properties",JSONObject().put("keys",JSONObject().put("type","array").put("items",JSONObject().put("type","string")).put("description","要读取的状态字段名，空数组读取当前全部记录"))).put("required",JSONArray(listOf("keys"))).put("additionalProperties",false).toString())
        val definition=ToolDefinition("update_playthrough_state","记录当前对话的文字、数值或真假状态。只属于当前对话，不修改世界或角色卡，不表示软件证实其内容。查看快捷操作可按键展示这些记录。",
            JSONObject().put("type","object").put("properties",JSONObject().put("updates",JSONObject().put("type","string").put("description","JSON（结构化数据）数组文字，每项只含 key 和 value；值只接受文字、数值或真假值。"))).put("required",JSONArray(listOf("updates"))).put("additionalProperties",false).toString())
        fun updates(arguments:String):List<StateUpdate> {
            val args=JSONObject(arguments);require(args.keys().asSequence().toSet()==setOf("updates") && args.get("updates") is String)
            val array=JSONArray(args.getString("updates"));require(array.length()>0)
            val updates=(0 until array.length()).map {index->val item=array.getJSONObject(index)
                require(item.keys().asSequence().toSet()==setOf("key","value") && item.get("key") is String && item.getString("key").isNotBlank())
                val value=item.get("value");require(value is String || value is Number || value is Boolean){"状态值类型不支持"}
                StateUpdate(item.getString("key"),stateValueJson(value))}
            require(updates.map {it.key}.distinct().size==updates.size){"状态字段重复"};return updates
        }
    }
}
