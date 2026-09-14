package novex.runtime

import org.json.JSONObject

data class AdoptedModuleRecord(val sourceName:String,val moduleName:String,val selected:Boolean,val reason:String,val matchedWords:List<String>)
data class ConversationMaterialRecord(val modules:List<AdoptedModuleRecord>,val imagesNotSent:Int)

/** 按需读取该回合的原始组装记录，不重新读取卡片或重新计算触发。 */
class ConversationMaterials(private val execution:TurnJournal) {
    fun read(chat:String,turn:String):ConversationMaterialRecord? {
        val initial=execution.read("${chat.length}:$chat${turn.length}:$turn","initial")?:return null
        val ref=initial.details?:initial.trace?:return null
        val trace=JSONObject(execution.text(ref))
        val modules=trace.optJSONArray("modules")?:return null
        return ConversationMaterialRecord((0 until modules.length()).map {index->
            val module=modules.getJSONObject(index);val words=module.optJSONArray("matchedWords")
            AdoptedModuleRecord(module.optString("sourceName","作品"),module.optString("moduleName","").ifBlank {"未命名模块"},module.getBoolean("selected"),module.getString("reason"),
                if(words==null)emptyList() else (0 until words.length()).map {words.getString(it)})
        },trace.optJSONArray("imagesNotSent")?.length()?:0)
    }
}
