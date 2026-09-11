package novex.runtime

import novex.content.ModuleUse
import org.json.JSONArray
import org.json.JSONObject

/** 工具边界的规则编码；实际修改仍交给共同编辑器。 */
internal object ModuleOptionsProtocol {
    fun encode(use:ModuleUse?):JSONObject=when(use) {
        null->JSONObject().put("kind","automatic")
        ModuleUse.Always->JSONObject().put("kind","always")
        ModuleUse.Manual->JSONObject().put("kind","manual")
        is ModuleUse.Keywords->JSONObject().put("kind","keywords").put("words",JSONArray(use.words)).put("case_sensitive",use.caseSensitive).put("require_all",use.requireAll)
    }
    fun decode(rule:JSONObject):ModuleUse? {
        val kind=rule.getString("kind")
        val fields=if(kind=="keywords")setOf("kind","words","case_sensitive","require_all") else setOf("kind")
        require(rule.keys().asSequence().toSet()==fields){"携带规则字段不完整或多余"}
        return when(kind) {
            "automatic"->null;"always"->ModuleUse.Always;"manual"->ModuleUse.Manual
            "keywords"->{
                require(rule.get("case_sensitive") is Boolean && rule.get("require_all") is Boolean)
                ModuleUse.Keywords(strings(rule.getJSONArray("words")),rule.getBoolean("case_sensitive"),rule.getBoolean("require_all"))
            }
            else->error("未提供的携带规则")
        }
    }
    fun strings(array:JSONArray):List<String> = (0 until array.length()).map {require(array.get(it) is String);array.getString(it)}.also {
        require(it.all(String::isNotBlank) && it.distinct().size==it.size){"文字项不能为空或重复"}
    }
}
