package novex.runtime

import novex.storage.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

/** 用户明确整理中断回合。只核对本地记录，不重发网络，不重做未知编辑。 */
class ConversationRecovery(private val sending:Path,private val runs:TurnJournal,private val tools:TurnJournal,
                           private val cards:CardStore,private val pauses:PausedLoopStore,private val onCreated:((ManagementTarget)->Unit)?=null) {
    fun recover(chat:String,turn:String) {
        Files.createDirectories(sending)
        val key=MessageDigest.getInstance("SHA-256").digest(chat.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        FileChannel.open(sending.resolve("send-$key.lock"),CREATE,WRITE).use {channel->
            val held=try{channel.tryLock()}catch(_:OverlappingFileLockException){null}
            check(held!=null){"对话仍在执行，请先停止"}
            held.use {reconcile(chat,turn)}
        }
    }
    private fun reconcile(chat:String,turn:String) {
        val namespace="${chat.length}:$chat${turn.length}:$turn"
        var record=runs.read(namespace,"initial")
        if(record==null) {
            runs.enqueue(namespace,"initial",JSONObject().put("interruptedBeforePreparation",true).toString())
            record=requireNotNull(runs.read(namespace,"initial"))
        }
        val visited=mutableSetOf<String>()
        while(record!!.state==TurnState.FINISHED) {
            val current=requireNotNull(record)
            check(visited.add(current.id)){"回合恢复链异常"}
            val value=JSONObject(runs.text(requireNotNull(current.outcome)))
            if(value.getString("kind")!="paused")return
            record=runs.read(namespace,value.getString("version"))?:return
        }
        val current=requireNotNull(record)
        if(current.state==TurnState.QUEUED)runs.claim(namespace,current.id)
        val receipts=JSONArray()
        tools.entries(namespace).forEach {entry->
            tools.exclusiveExecution(namespace,entry.id) {
                val fresh=requireNotNull(tools.read(namespace,entry.id))
                if(fresh.state==TurnState.QUEUED)return@exclusiveExecution
                var result=fresh.outcome?.let {JSONObject(tools.text(it))}
                if(result==null) {
                    val input=JSONObject(tools.text(fresh.input))
                    val root=input.optString("root")
                    if(root.isNotBlank()) {
                        val edit=input.optJSONObject("edit")
                        val commit=if(edit?.optString("kind")=="create")"create:$root"
                            else fresh.trace?.let {JSONObject(tools.text(it)).optString("commit")}?.takeIf {it.isNotBlank()}
                        val saved=commit?.let {cards.history(root,it)}
                        result=if(saved!=null)JSONObject().put("kind","saved").put("root",root).put("target",input.getString("target")).put("revision",saved.revision)
                            else JSONObject().put("kind","failed").put("reason","执行中断；保留当前草稿，未重做操作").put("draft",CardDrafts(cards).read(root)?.version?:JSONObject.NULL)
                        tools.finish(namespace,entry.id,requireNotNull(result))
                    }
                }
                val value=result?:JSONObject().put("kind","unconfirmed")
                if(value.optString("kind")=="saved") {
                    val input=JSONObject(tools.text(fresh.input));val edit=input.optJSONObject("edit")
                    if(edit?.optString("kind")=="create")onCreated?.invoke(ManagementTarget(value.getString("root"),value.getString("target")))
                    if(edit?.optString("operation") in setOf("create_internal_character","copy_internal_character")) {
                        val saved=requireNotNull(cards.history(value.getString("root"),value.getString("revision")))
                        val parent=novex.content.ContentTargets.find(saved.content,value.getString("target"))
                        onCreated?.invoke(ManagementTarget(saved.content.id,parent.internalCharacters.last().id))
                    }
                }
                val receipt=JSONObject(CardToolProtocol.result(ToolResultCodec.decode(value)))
                receipts.put(JSONObject().put("call",entry.id).put("result",receipt))
            }?:error("仍有工具在执行，请稍后继续整理")
        }
        val paused=pauses.load(chat,turn)
        val outcome=if(paused!=null && paused.version!=current.id) {
            JSONObject().put("kind","paused").put("version",paused.version)
        }else JSONObject().put("kind","interrupted").put("reason","中断请求的模型结果未知；未自动重发，已保留能确认的保存结果")
        runs.finish(namespace,current.id,outcome.put("outcomes",receipts))
    }
}
