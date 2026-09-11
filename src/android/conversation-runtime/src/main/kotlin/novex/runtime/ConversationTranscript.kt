package novex.runtime

import org.json.JSONObject

data class OperationReceipt(val callId:String,val status:String,val rootId:String?=null,val targetId:String?=null,val revision:String?=null,val checkpointId:String?=null,val reason:String?=null,val preservedDraft:String?=null)

enum class ReplyState { QUEUED, RUNNING, PAUSED, REPLIED, PARTIAL, FAILED }
data class ConversationTurn(val id:String,val input:String,val state:ReplyState,val reply:String?=null,
    val reason:String?=null,val pauseVersion:String?=null,val requests:Int?=null,val operations:List<OperationReceipt> = emptyList())

/** 只读取既有执行链，恢复产生的新记录仍投影到原用户消息，不启动请求。 */
class ConversationTranscript(private val timeline:ConversationTimeline,private val execution:TurnJournal,private val tools:TurnJournal?=null) {
    fun read(chat:String):List<ConversationTurn> = timeline.read(chat).map {turn->
        val input=timeline.input(turn)
        val operations=linkedMapOf<String,OperationReceipt>()
        val namespace="${chat.length}:$chat${turn.id.length}:${turn.id}"
        fun receipt(id:String,result:JSONObject):OperationReceipt {
            fun field(name:String)=if(result.isNull(name))null else result.getString(name)
            return OperationReceipt(id,result.getString("status"),field("root_id"),field("target_id"),field("revision"),field("checkpoint_id"),field("reason"),field("preserved_draft"))
        }
        fun entry(id:String,input:String,state:ReplyState,reply:String?=null,reason:String?=null,pauseVersion:String?=null,requests:Int?=null):ConversationTurn {
            // 暂停快照可能早于用户的批准/拒绝；真实完成日志覆盖旧的待执行投影。
            if(state in setOf(ReplyState.QUEUED,ReplyState.RUNNING,ReplyState.PAUSED))tools?.let {journal->
                journal.entries(namespace).forEach {record->record.outcome?.let {ref->
                    val result=ToolResultCodec.decode(JSONObject(journal.text(ref)))
                    operations[record.id]=receipt(record.id,JSONObject(CardToolProtocol.result(result)))
                }}
            }
            return ConversationTurn(id,input,state,reply,reason,pauseVersion,requests,operations.values.toList())
        }
        var run=execution.read(namespace,"initial")
        if(run==null)return@map entry(turn.id,input,ReplyState.QUEUED)
        val visited=mutableSetOf<String>()
        while(true) {
            val current=requireNotNull(run)
            require(visited.add(current.id)){"执行恢复链出现循环"}
            when(current.state) {
                TurnState.QUEUED->return@map entry(turn.id,input,ReplyState.QUEUED)
                TurnState.RUNNING->return@map entry(turn.id,input,ReplyState.RUNNING)
                TurnState.FINISHED->Unit
            }
            val outcome=JSONObject(execution.text(requireNotNull(current.outcome){"执行结果缺失"}))
            outcome.optJSONArray("outcomes")?.let {values->
                for(index in 0 until values.length()) {
                    val item=values.getJSONObject(index);val result=item.getJSONObject("result")
                    val status=result.optString("status")
                    if(status in setOf("saved","registered","state_saved","checkpoint_saved","denied","failed","stopped","unconfirmed","awaiting_approval")) {
                        val id=item.getString("call")
                        operations[id]=receipt(id,result)
                    }
                }
            }
            when(outcome.getString("kind")) {
                "paused"->{
                    val version=outcome.getString("version")
                    val next=execution.read(namespace,version)
                    if(next==null || next.state==TurnState.QUEUED)return@map entry(turn.id,input,ReplyState.PAUSED,pauseVersion=version)
                    run=next
                }
                "interrupted"->return@map entry(turn.id,input,ReplyState.FAILED,reason="interrupted")
                "preparation_failed"->return@map entry(turn.id,input,ReplyState.FAILED,reason="preparation_failed")
                "finished"->{
                    val result=outcome.getJSONObject("result")
                    val requests=outcome.getInt("requests")
                    require(requests>=0){"实际请求次数无效"}
                    val kind=result.getString("kind")
                    return@map when(kind) {
                        "reply"->entry(turn.id,input,ReplyState.REPLIED,reply=result.getString("text"),requests=requests)
                        "partial"->entry(turn.id,input,ReplyState.PARTIAL,reply=result.getString("text"),reason=result.getString("reason"),requests=requests)
                        else->entry(turn.id,input,ReplyState.FAILED,reason=kind,requests=requests)
                    }
                }
                else->error("执行结果类型尚不支持")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("执行链未返回结果")
    }
}
