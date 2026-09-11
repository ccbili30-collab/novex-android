package novex.runtime

import novex.conversation.*
import org.json.*
import java.nio.file.Path

/** 原始事件保留编号、消息归属和停用状态；不以当前显示值伪造历史事件。 */
internal object CheckpointEventRestoration {
    fun restore(store:ConversationCheckpoints,chat:String,id:String,directory:Path,path:List<String>):Boolean {
        val sections=requireNotNull(store.read(chat,id)).sections.keys
        val hasState="state-events" in sections;val hasControls="control-events" in sections
        require(hasState==hasControls){"存档事件记录不完整"}
        if(!hasState)return false // 较早开发存档只有显示投影，不能声称恢复原始事件。
        fun text(key:String)=store.open(chat,id,key).bufferedReader().use {it.readText()}
        val states=ConversationStateStore(directory.resolve("state"));states.restoreSnapshot(chat,text("state-events"))
        val controls=ConversationControlStore(directory.resolve("controls"));controls.restoreSnapshot(chat,text("control-events"))
        val expected=JSONObject(text("state")).getJSONObject("values")
        require(states.view(chat,path).associate {it.key to it.valueJson}==expected.keys().asSequence().associateWith {stateValueJson(expected.get(it))}){"存档状态与原始记录不一致"}
        val shown=JSONObject(text("controls")).getJSONArray("controls")
        val visible=controls.read(chat).controls.visible(path)
        require(shown.length()==visible.size){"存档菜单与原始记录不一致"}
        visible.forEachIndexed {index,item->
            val value=shown.getJSONObject(index);val definition=item.definition
            require(value.getString("key")==definition.key && value.getString("label")==definition.label && value.getString("registration")==item.handle.registrationId)
            when(val behavior=definition.behavior) {
                is ControlBehavior.Action->require(value.getString("behavior")=="action" && value.getString("instruction")==behavior.instruction)
                is ControlBehavior.View->{val keys=value.getJSONArray("keys");require(value.getString("behavior")=="view" && (0 until keys.length()).map {keys.getString(it)}==behavior.stateKeys)}
            }
        }
        return true
    }
}
