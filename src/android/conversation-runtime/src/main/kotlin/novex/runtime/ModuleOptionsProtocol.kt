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
    /**
     * [T-rule-lenient] 2026-09-16 用户批γ：rule 容错（对话包实测 set_module_options
     * 21/21 全灭：字符串 rule、多余字段、keywords 缺布尔位）。多余字段忽略；
     * kind 别名归一；keywords 的 words 缺失视为空、布尔位缺省 false、字符串
     * 布尔（"true"/"false"）归一。仍不合法才拒绝并说明可用形态。
     */
    fun decodeLenient(raw:Any?):ModuleUse? {
        val rule=when(raw) {
            is JSONObject->raw
            is String->runCatching{JSONObject(raw)}.getOrElse{throw IllegalArgumentException("携带规则不是合法 JSON 对象：$raw")}
            null->return null
            else->throw IllegalArgumentException("携带规则必须是对象或 JSON 字符串")
        }
        val kind=rule.optString("kind").trim().lowercase()
            .replace("auto","automatic").replace("default","automatic")
            .replace("constant","always").replace("always_carry","always")
            .replace("on_demand","manual")
        return when(kind) {
            "automatic"->null;"always"->ModuleUse.Always;"manual"->ModuleUse.Manual
            "keywords"->{
                val words=rule.optJSONArray("words")?.let{a->(0 until a.length()).map{i->a.optString(i)}}?:emptyList()
                require(words.all(String::isNotBlank)&&words.distinct().size==words.size){"关键词不能为空或重复"}
                fun boolean(key:String):Boolean=when(val v=rule.opt(key)){is Boolean->v;is String->v.equals("true",true);else->false}
                ModuleUse.Keywords(words,boolean("case_sensitive"),boolean("require_all"))
            }
            else->throw IllegalArgumentException("携带规则 kind 必须是 automatic/always/manual/keywords（收到：${rule.optString("kind")}）")
        }
    }
    fun decode(rule:JSONObject):ModuleUse? = decodeLenient(rule)
    fun strings(array:JSONArray):List<String> = (0 until array.length()).map {require(array.get(it) is String);array.getString(it)}.also {
        require(it.all(String::isNotBlank) && it.distinct().size==it.size){"文字项不能为空或重复"}
    }
}
