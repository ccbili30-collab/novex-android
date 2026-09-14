package novex.runtime

import novex.conversation.*
import novex.model.*
import java.util.concurrent.atomic.AtomicBoolean

/** 停止同时传到网络请求和实际工具执行，不只改变显示状态。 */
enum class DialogueStage { PREPARING, REQUESTING, TOOLS, STOPPING }
class DialogueStop(private val onStage:(DialogueStage)->Unit={}) {
    internal fun stage(value:DialogueStage){if(!tools.isStopped())onStage(value)}
    internal val tools=ToolStop()
    private var active:ChatCompletionCall?=null
    @Synchronized fun stop(){tools.stop();onStage(DialogueStage.STOPPING);active?.cancel()}
    @Synchronized internal fun bind(call:ChatCompletionCall?){active=call;if(tools.isStopped())call?.cancel()}
}
sealed interface ToolLoopResult {
    val outcomes:List<ToolOutcome>
    data class Finished(val result:ModelResult,val requests:Int,override val outcomes:List<ToolOutcome>):ToolLoopResult
    data class Waiting(val pending:ToolRoundResult.Waiting,val requests:Int,override val outcomes:List<ToolOutcome>):ToolLoopResult
    data class BudgetReached(val next:TextRequest,val requests:Int,override val outcomes:List<ToolOutcome>):ToolLoopResult
}

/** 自动继续已完成的工具结果；请求预算由调用方显式给定，不隐式无限循环。 */
class ToolDialogueLoop(private val rounds:CardToolRound,private val callFactory:()->ChatCompletionCall) {
    private val started=AtomicBoolean(false)
    fun run(chatId:String,turnId:String,initial:TextRequest,model:ModelCapacity,selectedWindow:Long,maximumRequests:Int,
            currentPolicy:()->CardToolPolicy,measure:(String)->TokenMeasurement,stop:DialogueStop=DialogueStop(),priorRequests:Int=0):ToolLoopResult {
        require(priorRequests in 0..maximumRequests)
        begin(chatId,turnId,maximumRequests)
        rounds.beginEditing()
        return try{advance(namespace(chatId,turnId),initial,model,selectedWindow,maximumRequests,priorRequests,emptyList(),currentPolicy,measure,stop)}finally{rounds.endEditing()}
    }
    /** 显式继续一个已知暂停点；新的上限是累计总数，不是重新获得一份预算。 */
    fun resume(paused:PausedLoop,currentPolicy:()->CardToolPolicy,measure:(String)->TokenMeasurement,
               stop:DialogueStop=DialogueStop(),maximumRequests:Int=paused.budget.maximumRequests):ToolLoopResult {
        val state=paused.state
        require(state is ToolLoopResult.Waiting || state is ToolLoopResult.BudgetReached)
        val used=when(state){is ToolLoopResult.Waiting->state.requests;is ToolLoopResult.BudgetReached->state.requests;else->error("无暂停点")}
        require(used>=0 && maximumRequests>=used)
        begin(paused.chatId,paused.turnId,maximumRequests)
        rounds.beginEditing()
        try {
        val namespace=namespace(paused.chatId,paused.turnId)
        var next=when(state){is ToolLoopResult.Waiting->state.pending.original;is ToolLoopResult.BudgetReached->state.next;else->error("无暂停点")}
        var accumulated=state.outcomes
        if(state is ToolLoopResult.Waiting) {
            val pending=state.pending
            require(accumulated.takeLast(pending.outcomes.size)==pending.outcomes){"暂停结果与当前批次不一致"}
            val previous=accumulated.dropLast(pending.outcomes.size)
            when(val handled=rounds.consume(namespace,pending.original,pending.response,currentPolicy(),stop.tools,pending.outcomes)) {
                is ToolRoundResult.Waiting->return ToolLoopResult.Waiting(handled,used,previous+handled.outcomes)
                is ToolRoundResult.Stopped->return ToolLoopResult.Finished(ModelResult.Cancelled,used,previous+handled.outcomes)
                is ToolRoundResult.Continue->{next=handled.request;accumulated=previous+handled.outcomes}
            }
        }
        return advance(namespace,next,paused.budget.model,paused.budget.selectedWindow,maximumRequests,used,accumulated,currentPolicy,measure,stop)
        }finally{rounds.endEditing()}
    }

    private fun namespace(chat:String,turn:String)="${chat.length}:$chat${turn.length}:$turn"
    private fun begin(chat:String,turn:String,maximum:Int) {
        require(chat.isNotBlank() && turn.isNotBlank() && maximum>0)
        check(started.compareAndSet(false,true)){"同一运行实例不能重新开始；需要明确的继续或重试"}
    }
    private fun advance(namespace:String,initial:TextRequest,model:ModelCapacity,selectedWindow:Long,maximumRequests:Int,
                        used:Int,previous:List<ToolOutcome>,currentPolicy:()->CardToolPolicy,
                        measure:(String)->TokenMeasurement,stop:DialogueStop):ToolLoopResult {
        var request=initial;var sent=used;val outcomes=previous.toMutableList()
        if(stop.tools.isStopped())return ToolLoopResult.Finished(ModelResult.Cancelled,sent,outcomes.toList())
        while(sent<maximumRequests) {
            if(stop.tools.isStopped())return ToolLoopResult.Finished(ModelResult.Cancelled,sent,outcomes.toList())
            val policy=currentPolicy()
            request=request.copy(tools=rounds.definitions(policy))
            stop.stage(DialogueStage.REQUESTING)
            val call=callFactory();stop.bind(call)
            val result=try{call.execute(request,model,selectedWindow,measure)}finally{stop.bind(null)}
            if(call.networkAttempted)sent++
            if(result !is ModelResult.ToolsRequested)return ToolLoopResult.Finished(result,sent,outcomes.toList())
            stop.stage(DialogueStage.TOOLS)
            // 回复期间权限可能已经改变，执行前使用此刻的策略。
            when(val handled=rounds.consume(namespace,request,result,currentPolicy(),stop.tools)) {
                is ToolRoundResult.Continue->{outcomes+=handled.outcomes;request=handled.request}
                is ToolRoundResult.Waiting->{outcomes+=handled.outcomes;return ToolLoopResult.Waiting(handled,sent,outcomes.toList())}
                is ToolRoundResult.Stopped->{outcomes+=handled.outcomes;return ToolLoopResult.Finished(ModelResult.Cancelled,sent,outcomes.toList())}
            }
        }
        return ToolLoopResult.BudgetReached(request,sent,outcomes.toList())
    }
}
