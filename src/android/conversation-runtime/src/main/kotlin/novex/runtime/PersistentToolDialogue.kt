package novex.runtime

import novex.conversation.TokenMeasurement
import novex.model.TextRequest
import novex.model.WireMessage
import org.json.JSONArray
import org.json.JSONObject

/** 领取先落盘，再继续网络；中断保持 RUNNING，不自动重发未知请求。使用专属运行日志目录。 */
class PersistentToolDialogue(
    private val checkpoints:PausedLoopStore,
    private val journal:TurnJournal,
    private val loop:()->ToolDialogueLoop
) {
    fun start(chat:String,turn:String,request:TextRequest,budget:LoopBudget,
              policy:()->CardToolPolicy,measure:(String)->TokenMeasurement,
              stop:DialogueStop=DialogueStop()):StoredTurn {
        return startInitial(chat,turn,JSONObject().put("request",JSONObject(request.encode())),budget,policy,measure,stop) {
            PreparedDialogue(request,JSONObject())
        }
    }
    fun startConversation(chat:String,turn:String,input:String,history:List<WireMessage>,settings:TurnSettings,
                          builder:DialogueRequestBuilder,maximumRequests:Int,policy:()->CardToolPolicy,
                          measure:(String)->TokenMeasurement,stop:DialogueStop=DialogueStop()):StoredTurn {
        val budget=LoopBudget(settings.capacity,settings.selectedWindow,maximumRequests)
        return startInitial(chat,turn,builder.intent(input,history,settings),budget,policy,measure,stop) {
            builder.prepare(input,history,settings)
        }
    }
    private fun startInitial(chat:String,turn:String,intent:JSONObject,budget:LoopBudget,policy:()->CardToolPolicy,
                             measure:(String)->TokenMeasurement,stop:DialogueStop,prepare:()->PreparedDialogue):StoredTurn {
        require(chat.isNotBlank() && turn.isNotBlank())
        val namespace="${chat.length}:$chat${turn.length}:$turn"
        val input=intent.put("window",budget.selectedWindow).put("maximum",budget.maximumRequests)
            .put("modelWindow",budget.model.contextWindow?:JSONObject.NULL)
            .put("modelOutput",budget.model.maximumOutput?:JSONObject.NULL).toString()
        journal.enqueue(namespace,"initial",input)
        journal.claim(namespace,"initial") ?: return requireNotNull(journal.read(namespace,"initial"))
        check(checkpoints.load(chat,turn)==null){"此回合已有暂停进度，不能作为新回合启动"}
        val prepared=try { prepare().also { it.request.encode() } } catch(failure:ModuleSelectionFailure) {
            return journal.finish(namespace,"initial",JSONObject().put("kind","finished").put("requests",failure.requests)
                .put("result",ModelResultRecord.encode(failure.result)).put("outcomes",JSONArray()))
        } catch(failure:Exception) {
            return journal.finish(namespace,"initial",JSONObject().put("kind","preparation_failed").put("networkAttempted",false))
        }
        val details=prepared.trace.optJSONArray("modules")?.let {modules->
            JSONObject().put("modules",modules).put("imagesNotSent",prepared.trace.optJSONArray("imagesNotSent")?:JSONArray())
        }
        journal.prepared(namespace,"initial",prepared.trace.put("request",JSONObject(prepared.request.encode())),details)
        val result=loop().run(chat,turn,prepared.request,budget.model,budget.selectedWindow,budget.maximumRequests,policy,measure,stop,prepared.priorRequests)
        return finish(chat,turn,namespace,"initial",null,budget,result)
    }
    fun resume(chat:String,turn:String,version:String,maximumRequests:Int,
               policy:()->CardToolPolicy,measure:(String)->TokenMeasurement,
               stop:DialogueStop=DialogueStop()):StoredTurn {
        val namespace="${chat.length}:$chat${turn.length}:$turn"
        val input=JSONObject().put("version",version).put("maximum",maximumRequests).toString()
        val previous=journal.read(namespace,version)
        if(previous!=null && previous.state!=TurnState.QUEUED) {
            require(journal.text(previous.input)==input){"此暂停点已按其他预算开始运行"}
            return previous
        }
        val paused=requireNotNull(checkpoints.load(chat,turn)){"暂停点不存在"}
        if(paused.version!=version)throw LoopCheckpointConflict()
        val used=when(val state=paused.state){is ToolLoopResult.Waiting->state.requests;is ToolLoopResult.BudgetReached->state.requests;else->error("不是暂停点")}
        require(maximumRequests>0 && maximumRequests>=used)
        journal.enqueue(namespace,version,input)
        journal.claim(namespace,version) ?: return requireNotNull(journal.read(namespace,version))
        // 后续异常保留已领取状态；不伪造已完成或自动重试。
        val result=loop().resume(paused,policy,measure,stop,maximumRequests)
        return finish(chat,turn,namespace,version,version,paused.budget.copy(maximumRequests=maximumRequests),result)
    }
    private fun finish(chat:String,turn:String,namespace:String,runId:String,expectedVersion:String?,budget:LoopBudget,result:ToolLoopResult):StoredTurn {
        val record=when(result) {
            is ToolLoopResult.Finished->JSONObject().put("kind","finished").put("requests",result.requests)
                .put("result",ModelResultRecord.encode(result.result))
            else->{
                val saved=checkpoints.save(chat,turn,expectedVersion,budget,result)
                JSONObject().put("kind","paused").put("version",saved.version)
            }
        }
        record.put("outcomes",JSONArray(result.outcomes.map { outcome->JSONObject().put("call",outcome.call.id).put("result",JSONObject(CardToolProtocol.result(outcome.result))) }))
        return journal.finish(namespace,runId,record)
    }
}
