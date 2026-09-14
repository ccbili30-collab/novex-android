package novex.runtime

import novex.model.*

data class ToolOutcome(val call:PendingTool,val result:CardToolResult,val images:List<WireImage> = emptyList())
sealed interface ToolRoundResult {
    data class Continue(val request:TextRequest,val outcomes:List<ToolOutcome>):ToolRoundResult
    data class Waiting(val original:TextRequest,val response:ModelResult.ToolsRequested,val outcomes:List<ToolOutcome>):ToolRoundResult
    data class Stopped(val outcomes:List<ToolOutcome>):ToolRoundResult
}
/** 一轮工具处理；待批准、未确认或停止时不发起下一次模型请求。 */
class CardToolRound(private val coordinator:CardToolCoordinator,private val reader:CardToolReader,private val controls:ConversationControlTools?=null,private val states:ConversationStateTools?=null,private val checkpoints:ConversationCheckpointTools?=null,private val conversationImage:((String)->WireImage)?=null,private val onOutcome:()->Unit={}) {
    internal fun beginEditing()=coordinator.beginEditing()
    internal fun endEditing()=coordinator.endEditing()
    fun definitions(policy:CardToolPolicy)=CardToolProtocol.definitions(policy)+(controls?.definitions(policy.permission)?:emptyList())+(states?.definitions(policy.permission)?:emptyList())+(checkpoints?.definitions(policy.permission)?:emptyList())+
        (if(conversationImage==null || policy.permission==ToolPermission.READ_ONLY)emptyList() else listOf(ToolDefinition("read_conversation_image","明确读取当前对话持有的一张图片，附加到下一次请求。image_id 来自对话图片目录，不是文件路径。", """{"type":"object","properties":{"image_id":{"type":"string"}},"required":["image_id"],"additionalProperties":false}""")))
    fun consume(chatId:String,original:TextRequest,response:ModelResult.ToolsRequested,policy:CardToolPolicy,stop:ToolStop=ToolStop(),previous:List<ToolOutcome> = emptyList()):ToolRoundResult {
        val provided=original.tools.map { it.name }.toSet()
        val outcomes=response.calls.map { call ->
            val prior=previous.singleOrNull {it.call==call}
            val recorded=if(prior==null)null else try {when(call.name) {
                "register_controls"->controls?.completed(chatId,call)
                "update_playthrough_state"->states?.completed(chatId,call)
                "save_conversation_checkpoint"->checkpoints?.completed(chatId,call)
                else->coordinator.completed(chatId,call)
            }}
                catch(failure:IllegalArgumentException){return@map ToolOutcome(call,CardToolResult.Failed(failure.message?:"工具记录不匹配"))}
                catch(_:Exception){return@map ToolOutcome(call,CardToolResult.Unconfirmed)}
            // 未停止时，尚未确认的记录仍交回原工具核对其真实存储；停止时保留未知事实。
            if(recorded!=null && (recorded!=CardToolResult.Unconfirmed || stop.isStopped()))
                return@map ToolOutcome(call,recorded,prior?.images?:emptyList())
            if(prior?.result is CardToolResult.Read && call.name in setOf("read_card_image","read_conversation_image") && prior.images.isEmpty())
                return@map ToolOutcome(call,CardToolResult.Failed("旧暂停记录未保留图片，请读取最新版本后重新取图"))
            if(prior!=null && prior.result!=CardToolResult.AwaitingApproval && prior.result!=CardToolResult.Unconfirmed)
                return@map prior
            val images=mutableListOf<WireImage>()
            val result=if(stop.isStopped())CardToolResult.Stopped(null)
                else if(call.name !in provided)CardToolResult.Denied
                else if(call.name=="save_conversation_checkpoint")try {checkpoints?.submit(chatId,call,policy.permission,stop)?:CardToolResult.Denied} catch(failure:IllegalArgumentException){CardToolResult.Failed(failure.message?:"存档参数无效")} catch(_:Exception){CardToolResult.Unconfirmed}
                else if(call.name=="read_conversation_state")try {states?.read(call,policy.permission)?:CardToolResult.Denied} catch(failure:Exception){CardToolResult.Failed(failure.message?:"状态读取未完成")}
                else if(call.name=="update_playthrough_state")try { states?.submit(chatId,call,policy.permission,stop)?:CardToolResult.Denied } catch(failure:IllegalArgumentException){CardToolResult.Failed(failure.message?:"状态参数无效")} catch(_:Exception){CardToolResult.Unconfirmed}
                else if(call.name=="register_controls")try { controls?.submit(chatId,call,policy.permission,stop)?:CardToolResult.Denied } catch(_:Exception){CardToolResult.Unconfirmed}
                else if(call.name=="read_conversation_image")try {
                    require(policy.permission!=ToolPermission.READ_ONLY)
                    val args=org.json.JSONObject(call.arguments);require(args.keys().asSequence().toSet()==setOf("image_id") && args.get("image_id") is String)
                    images+=requireNotNull(conversationImage)(args.getString("image_id"))
                    CardToolResult.Read(org.json.JSONObject().put("status","read").put("image_attached_for_next_request",true).put("image_sent",false).toString())
                }catch(_:Exception){CardToolResult.Failed("对话图片读取未完成")}
                else if(call.name=="read_card_image")try {
                    val image=reader.image(call,policy);images+=image
                    CardToolResult.Read(org.json.JSONObject().put("status","read").put("image_attached_for_next_request",true).put("image_sent",false).toString())
                }catch(_:Exception){CardToolResult.Failed("图片读取未完成")}
                else if(call.name in setOf("read_card","read_text_block"))try{CardToolResult.Read(reader.read(call,policy).toString())}
                    catch(failure:Exception){CardToolResult.Failed(failure.message?:"读取未完成")}
                else {
                var parseFailure:Exception?=null
                val parsed=try{CardToolProtocol.parse(chatId,call)}catch(failure:Exception){parseFailure=failure;null}
                if(parsed==null)CardToolResult.Failed(parseFailure?.message?:"工具参数无效，未执行")
                else try{coordinator.submit(parsed,policy,stop)}catch(_:Exception){CardToolResult.Unconfirmed}
            }
            ToolOutcome(call,result,images.toList()).also {onOutcome()}
        }
        if(stop.isStopped() || outcomes.any { it.result is CardToolResult.Stopped })return ToolRoundResult.Stopped(outcomes)
        if(outcomes.any { it.result==CardToolResult.AwaitingApproval || it.result==CardToolResult.Unconfirmed })
            return ToolRoundResult.Waiting(original,response,outcomes)
        val images=outcomes.flatMap {it.images}
        val messages=original.messages+WireMessage("assistant",response.text,response.calls)+outcomes.map {
            WireMessage("tool",CardToolProtocol.result(it.result),toolCallId=it.call.id)
        }+(if(images.isEmpty())emptyList() else listOf(WireMessage("user","以下是本轮明确读取的图片，仅作资料，不代表编辑授权。",images=images)))
        return ToolRoundResult.Continue(original.copy(messages=messages,tools=definitions(policy)),outcomes)
    }
}
