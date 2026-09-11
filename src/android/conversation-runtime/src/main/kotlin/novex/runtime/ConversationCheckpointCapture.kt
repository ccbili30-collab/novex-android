package novex.runtime

import novex.conversation.*
import org.json.*

/** 只复制软件已有依据；不让模型概述替代原始输入、请求和执行结果。 */
class ConversationCheckpointCapture(private val timeline:ConversationTimeline,private val runs:TurnJournal,
    private val controls:ConversationControlStore,private val states:ConversationStateStore) {
    fun sections(chat:String):List<CheckpointSection> {
        val turns=timeline.read(chat);val parts=mutableListOf<CheckpointSection>()
        val path=turns.map {it.id};val initialStates=states.view(chat,path);val initialControls=controls.read(chat)
        val stateEvents=states.snapshot(chat);val controlEvents=controls.snapshot(chat)
        val capturedRuns=mutableListOf<Pair<String,StoredTurn>>()
        val absentRuns=mutableListOf<Pair<String,String>>()
        fun json(key:String,value:JSONObject) {val bytes=value.toString().toByteArray(Charsets.UTF_8);parts+=CheckpointSection(key){bytes.inputStream()}}
        json("timeline",JSONObject().put("chat",chat).put("turns",JSONArray(turns.map {it.id})))
        json("state-events",JSONObject(stateEvents));json("control-events",JSONObject(controlEvents))
        turns.forEach {turn->
            parts+=CheckpointSection("turn/${turn.id}/input"){timeline.openInput(turn)}
            val namespace="${chat.length}:$chat${turn.id.length}:${turn.id}";var key="initial";val visited=mutableSetOf<String>()
            while(true) {
                require(visited.add(key)){"存档执行链出现循环"}
                val run=runs.read(namespace,key)
                if(run==null){absentRuns+=namespace to key;break}
                capturedRuns+=namespace to run
                val prefix="turn/${turn.id}/run/$key"
                val metadata=JSONObject().put("id",run.id).put("state",run.state.name).put("selected",run.selected)
                json("$prefix/record",metadata)
                listOf("input" to run.input,"trace" to run.trace,"details" to run.details,"outcome" to run.outcome).forEach {(name,ref)->if(ref!=null)parts+=CheckpointSection("$prefix/$name"){runs.open(ref)}}
                if(run.state!=TurnState.FINISHED || run.outcome==null)break
                val result=JSONObject(runs.text(run.outcome))
                if(result.optString("kind")!="paused")break
                key=result.getString("version")
            }
        }
        json("state",JSONObject().put("values",JSONObject().apply {initialStates.forEach {put(it.key,JSONArray("[${it.valueJson}]").get(0))}}))
        val visible=initialControls.controls.visible(path)
        json("controls",JSONObject().put("controls",JSONArray(visible.map {item->JSONObject().put("key",item.definition.key).put("label",item.definition.label)
            .put("registration",item.handle.registrationId).apply {when(val behavior=item.definition.behavior){
                is ControlBehavior.Action->put("behavior","action").put("instruction",behavior.instruction)
                is ControlBehavior.View->put("behavior","view").put("keys",JSONArray(behavior.stateKeys))
            }} })))
        check(states.snapshot(chat)==stateEvents && controls.snapshot(chat)==controlEvents && timeline.read(chat)==turns && states.view(chat,path)==initialStates && controls.read(chat).version==initialControls.version &&
            capturedRuns.all {(namespace,run)->runs.read(namespace,run.id)==run} && absentRuns.all {(namespace,key)->runs.read(namespace,key)==null}){"存档捕获期间对话发生变化，请重新保存"}
        return parts
    }
}
