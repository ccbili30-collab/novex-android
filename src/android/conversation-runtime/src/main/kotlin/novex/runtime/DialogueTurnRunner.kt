package novex.runtime

import novex.conversation.*
import novex.model.*
import org.json.JSONArray
import org.json.JSONObject

data class TurnSettings(val model:String,val capacity:ModelCapacity,val selectedWindow:Long,val outputReserve:Long,
                        val instruction:String,val selections:List<SourceSelection>,val managedIds:Set<String>,
                        val triggerWindow:TriggerWindow,val overrides:Map<String,UseOverride> = emptyMap(),val manual:Set<String> = emptySet(),
                        val managementTargets:Set<ManagementTarget>? = null,
                        val toolPermission:ToolPermission=ToolPermission.FREE,val images:List<WireImage> = emptyList(),val imageDirectory:List<Pair<String,String>> = emptyList()) {
    val effectiveManagementTargets:Set<ManagementTarget> get()=managementTargets?:managedIds.map {ManagementTarget(it)}.toSet()
}

/** 先保存输入，再执行一次。结果落盘不是工具已执行或卡片已保存的证明。 */
class DialogueTurnRunner(private val journal:TurnJournal,private val materials:RequestMaterials) {
    fun run(chat:String,turnId:String,input:String,history:List<WireMessage>,settings:TurnSettings,
            call:ChatCompletionCall,measure:(String)->TokenMeasurement):StoredTurn {
        journal.enqueue(chat,turnId,input)
        journal.claim(chat,turnId) ?: return requireNotNull(journal.read(chat,turnId))
        val request:TextRequest
        try {
            val prepared=DialogueRequestBuilder(materials).prepare(input,history,settings)
            journal.prepared(chat,turnId,prepared.trace)
            request=prepared.request
        } catch(failure:Exception) {
            return journal.finish(chat,turnId,JSONObject().put("kind","preparation_failed").put("networkAttempted",false))
        }
        val result=call.execute(request,settings.capacity,settings.selectedWindow,measure)
        return journal.finish(chat,turnId,ModelResultRecord.encode(result))
    }
}
