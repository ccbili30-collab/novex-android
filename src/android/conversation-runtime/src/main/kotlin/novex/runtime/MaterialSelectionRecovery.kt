package novex.runtime

import org.json.JSONObject

data class RecoveredSelection(val ids:Set<String>,val recovered:Boolean)

/** Invalid helper output is not evidence that no source is relevant. */
object MaterialSelectionRecovery {
    fun resolve(answer:String?,allowed:Set<String>):RecoveredSelection {
        val ids=try {
            val text=requireNotNull(answer).trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val array=JSONObject(text).getJSONArray("modules")
            (0 until array.length()).map {array.getString(it)}.toSet().also {require(allowed.containsAll(it))}
        }catch(_:Exception){return RecoveredSelection(allowed,true)}
        return RecoveredSelection(ids,false)
    }
}
