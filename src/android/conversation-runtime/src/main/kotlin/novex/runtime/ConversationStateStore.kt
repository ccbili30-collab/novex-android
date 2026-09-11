package novex.runtime

import novex.storage.Utf8Files
import org.json.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal fun stateValueJson(value:Any):String {
    val encoded=JSONArray().put(value).toString()
    return encoded.substring(1,encoded.length-1)
}

data class StateUpdate(val key:String,val valueJson:String) {
    fun display():String=JSONArray("[$valueJson]").get(0).toString()
}
data class StateEvent(val id:String,val message:String,val updates:List<StateUpdate>)

/** 状态是对话记录，不是卡片设定或经软件证实的事实。按当前消息路径投影。 */
class ConversationStateStore(root:Path) {
    private val root=root.toAbsolutePath().normalize();private val mutex:Any
    init {Files.createDirectories(this.root);mutex=locks.computeIfAbsent(this.root.toRealPath()){Any()}}
    fun record(chat:String,event:StateEvent):StateEvent=locked {
        validate(event)
        val events=load(chat)
        events.find {it.id==event.id}?.let {require(it==event){"状态操作编号已用于其他内容"};return@locked it}
        writeEvents(chat,events+event)
        event
    }
    internal fun snapshot(chat:String):String=locked {JSONObject().put("schema",1).put("chat",chat).put("events",JSONArray(load(chat).map(::encode))).toString()}
    internal fun restoreSnapshot(chat:String,snapshot:String)=locked {
        check(!Files.exists(path(chat))){"目标已有状态记录"}
        writeEvents(chat,decode(chat,JSONObject(snapshot)))
    }
    private fun writeEvents(chat:String,events:List<StateEvent>) {
        val value=JSONObject().put("schema",1).put("chat",chat).put("events",JSONArray(events.map(::encode)))
        val pending=root.resolve("pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(pending,CREATE_NEW,WRITE).use {channel->val bytes=ByteBuffer.wrap(value.toString().toByteArray(Charsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true)}
            Files.move(pending,path(chat),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{Files.deleteIfExists(pending)}
    }
    fun event(chat:String,id:String):StateEvent?=locked {load(chat).find {it.id==id}}
    fun view(chat:String,activePath:List<String>,keys:List<String> = emptyList()):List<StateUpdate> = locked {
        require(activePath.distinct().size==activePath.size && activePath.all {it.isNotBlank()})
        val rank=activePath.withIndex().associate {it.value to it.index};val values=linkedMapOf<String,StateUpdate>()
        load(chat).filter {it.message in rank}.sortedBy {rank.getValue(it.message)}.forEach {event->event.updates.forEach {values[it.key]=it}}
        if(keys.isEmpty())values.values.toList() else keys.distinct().mapNotNull {values[it]}
    }
    private fun load(chat:String):List<StateEvent> {
        val path=path(chat);if(!Files.exists(path))return emptyList()
        return decode(chat,JSONObject(Utf8Files.read(path)))
    }
    private fun decode(chat:String,json:JSONObject):List<StateEvent> {
        require(json.getInt("schema")==1 && json.getString("chat")==chat)
        val entries=json.getJSONArray("events")
        return (0 until entries.length()).map {index->val event=entries.getJSONObject(index);val updates=event.getJSONArray("updates")
            StateEvent(event.getString("id"),event.getString("message"),(0 until updates.length()).map {i->val item=updates.getJSONObject(i);StateUpdate(item.getString("key"),stateValueJson(item.get("value")))})
        }.also {events->require(events.map {it.id}.distinct().size==events.size);events.forEach(::validate)}
    }
    private fun encode(event:StateEvent)=JSONObject().put("id",event.id).put("message",event.message).put("updates",JSONArray(event.updates.map {JSONObject().put("key",it.key).put("value",JSONArray("[${it.valueJson}]").get(0))}))
    private fun validate(event:StateEvent) {
        require(event.id.isNotBlank() && event.message.isNotBlank() && event.updates.isNotEmpty())
        require(event.updates.map {it.key}.distinct().size==event.updates.size)
        event.updates.forEach {update->
            require(update.key.isNotBlank());val array=JSONArray("[${update.valueJson}]");require(array.length()==1)
            val value=array.get(0);require(value is String || value is Number || value is Boolean){"状态值只接受文字、数值或真假值"}
            require(update.valueJson==stateValueJson(value)){"状态值编码不是规范形式"}
        }
    }
    private fun path(chat:String):Path {require(chat.isNotBlank());val hash=MessageDigest.getInstance("SHA-256").digest(chat.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)};return root.resolve("state-$hash.json")}
    private fun <T> locked(action:()->T):T=synchronized(mutex){FileChannel.open(root.resolve("write.lock"),CREATE,WRITE).use {it.lock().use {action()}}}
    companion object {private val locks=ConcurrentHashMap<Path,Any>()}
}
