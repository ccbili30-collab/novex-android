package novex.runtime

import novex.storage.CardStore
import novex.storage.Utf8Files
import org.json.JSONObject
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.WRITE

/** 准备历史记录，不启动任何请求。完成前整个目录不对当前会话发布。 */
class CheckpointHistoryRestoration(private val root:Path,private val checkpoints:ConversationCheckpoints) {
    fun prepare(sources:PreparedCheckpointSources):PreparedCheckpointHistory {
        val chat=sources.chat;val id=sources.checkpointId
        require(sources.session.id==chat)
        val saved=requireNotNull(checkpoints.read(chat,id)){"存档不存在"}
        Files.createDirectories(root)
        val stage=Files.createTempDirectory(root,"preparing-history-")
        val target=root.resolve("history-${java.util.UUID.randomUUID()}")
        try {
            val cards=CardStore(sources.cardsDirectory)
            val sessions=ConversationSessions(stage.resolve("sessions"),cards)
            val session=sources.session
            sessions.create(chat,session.title,session.primary)
            val messages=TurnJournal(stage.resolve("messages"));val runs=TurnJournal(stage.resolve("runs"))
            val timeline=ConversationTimeline(stage.resolve("timeline"),sessions,messages)
            fun json(key:String)=checkpoints.open(chat,id,key).bufferedReader().use {JSONObject(it.readText())}
            val order=json("timeline");require(order.getString("chat")==chat)
            val values=order.getJSONArray("turns")
            val ids=(0 until values.length()).map {values.getString(it)}
            require(ids.all {it.isNotBlank()} && ids.distinct().size==ids.size){"存档消息顺序无效"}
            val unfinished=mutableListOf<String>()
            for(turn in ids) {
                messages.restoreSnapshot(chat,turn,TurnState.QUEUED,false,mapOf("input" to {checkpoints.open(chat,id,"turn/$turn/input")}))
                val namespace="${chat.length}:$chat${turn.length}:$turn";var key="initial";val visited=mutableSetOf<String>()
                while(true) {
                    require(visited.add(key)){"存档执行链出现循环"}
                    val prefix="turn/$turn/run/$key"
                    if("$prefix/record" !in saved.sections){unfinished+=turn;break}
                    val record=json("$prefix/record");require(record.getString("id")==key){"存档执行节点编号不一致"}
                    val state=TurnState.valueOf(record.getString("state"))
                    val parts=listOf("input","trace","details","outcome").filter {"$prefix/$it" in saved.sections}
                        .associateWith {name->{checkpoints.open(chat,id,"$prefix/$name")}}
                    runs.restoreSnapshot(namespace,key,state,record.getBoolean("selected"),parts)
                    if(state!=TurnState.FINISHED){unfinished+=turn;break}
                    val result=json("$prefix/outcome")
                    if(result.getString("kind")!="paused")break
                    key=result.getString("version");require(key.isNotBlank())
                }
            }
            timeline.restoreOrder(chat,ids)
            val eventsRestored=CheckpointEventRestoration.restore(checkpoints,chat,id,stage,ids)
            val preferencesRestored=CheckpointPreferenceRestoration.restore(checkpoints,sources,stage)
            val record=JSONObject().put("schema",1).put("chat",chat).put("checkpoint",id).put("turns",org.json.JSONArray(ids))
                .put("preferencesRestored",preferencesRestored).put("eventsRestored",eventsRestored).put("unfinished",org.json.JSONArray(unfinished)).put("cardsDirectory",sources.cardsDirectory.toAbsolutePath().toString())
            Utf8Files.write(stage.resolve("prepared.json"),record.toString())
            FileChannel.open(stage.resolve("prepared.json"),WRITE).use {it.force(true)}
            Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE)
            return PreparedCheckpointHistory(chat,id,target,ids,unfinished,eventsRestored,preferencesRestored)
        } finally {
            if(Files.exists(stage))Files.walk(stage).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}
        }
    }
}
data class PreparedCheckpointHistory(val chat:String,val checkpointId:String,val directory:Path,val turns:List<String>,val unfinishedTurns:List<String>,val eventsRestored:Boolean,val preferencesRestored:Boolean)
