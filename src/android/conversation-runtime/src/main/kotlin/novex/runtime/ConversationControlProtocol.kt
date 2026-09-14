package novex.runtime

import novex.conversation.*
import novex.model.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/** 对话和回复编号由软件提供，模型只能定义操作，不能指定别的对话或自行发送。 */
object ConversationControlProtocol {
    val definition=ToolDefinition("register_controls",
        "注册当前对话的快捷操作。动作入口点击后发送对应指令，查看入口只展示状态。注册不会执行动作，也不修改卡片。",
        JSONObject().put("type","object").put("properties",JSONObject().put("controls",JSONObject().put("type","string")
            .put("description","操作数组的 JSON（结构化数据）文字。每项包含 actionKey、label、behavior（action 动作或 view 查看）；动作使用 prompt，查看可带 stateKeys，enabled 可选。")))
            .put("required",JSONArray(listOf("controls"))).put("additionalProperties",false).toString())

    fun parse(registrationId:String,messageId:String,arguments:String):ControlRegistration {
        val envelope=JSONObject(arguments)
        require(envelope.keys().asSequence().toSet()==setOf("controls") && envelope.get("controls") is String){"注册参数必须只包含 controls 数组文字"}
        val array=JSONArray(envelope.getString("controls"))
        val definitions=(0 until array.length()).map { index->
            val item=array.getJSONObject(index)
            require(item.keys().asSequence().toSet().all { it in setOf("actionKey","label","behavior","prompt","instruction","stateKeys","enabled") }){"快捷操作包含未提供字段"}
            fun string(key:String):String {require(item.get(key) is String){"快捷操作文字字段类型错误"};return item.getString(key)}
            val key=string("actionKey");val label=string("label")
            val enabled=if(item.has("enabled")){require(item.get("enabled") is Boolean);item.getBoolean("enabled")}else true
            val behavior=when(string("behavior")) {
                "action"->{
                    require(!item.has("stateKeys")){"动作不能混用查看字段"}
                    require(item.has("prompt") || item.has("instruction")){"动作缺少指令"}
                    val prompt=if(item.has("prompt"))string("prompt")else string("instruction")
                    if(item.has("prompt") && item.has("instruction"))require(prompt==string("instruction")){"动作指令字段冲突"}
                    ControlBehavior.Action(prompt)
                }
                "view"->{
                    require(!item.has("prompt") && !item.has("instruction")){"查看操作不能附带发送指令"}
                    val keys=if(item.has("stateKeys")){val values=item.getJSONArray("stateKeys");(0 until values.length()).map {require(values.get(it) is String);values.getString(it)}}else emptyList()
                    ControlBehavior.View(keys)
                }
                else->error("快捷操作类型无效")
            }
            ControlDefinition(key,label,behavior,enabled)
        }
        val registration=ControlRegistration(registrationId,messageId,definitions)
        ConversationControls.empty("validation").register(registration)
        return registration
    }
}
