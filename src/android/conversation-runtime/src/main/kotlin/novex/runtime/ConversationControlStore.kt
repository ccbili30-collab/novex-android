package novex.runtime

import novex.storage.Utf8Files

import novex.conversation.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SavedControls(val version:String?,val controls:ConversationControls)

/** 注册属于对话。独立事件保留重试凭据，替换菜单后重试旧事件不会倒退菜单。 */
class ConversationControlStore(root:Path) {
    private val root=root.toAbsolutePath().normalize()
    private val mutex:Any
    init {Files.createDirectories(this.root);mutex=locks.computeIfAbsent(this.root.toRealPath()){Any()} }
    fun read(chat:String):SavedControls=locked { load(chat).first }
    fun registered(chat:String,id:String):ControlRegistration?=locked {
        val ids=load(chat).second
        if(id in ids)decode(JSONObject(Utf8Files.read(event(chat,id))))else null
    }
    fun register(chat:String,expectedVersion:String?,registration:ControlRegistration):SavedControls=locked {
        ConversationControls.empty(chat).register(registration)
        val (saved,ids)=load(chat)
        val event=event(chat,registration.id);val json=encode(registration)
        if(registration.id in ids) {
            require(decode(JSONObject(Utf8Files.read(event)))==registration){"注册编号已对应其他内容"}
            return@locked saved
        }
        check(saved.version==expectedVersion){"快捷操作已变化，请读取最新菜单"}
        // 验证通过才写事件；孤立事件不代表已注册成功，重试仍需发布目录。
        saved.controls.register(registration)
        if(Files.exists(event))require(decode(JSONObject(Utf8Files.read(event)))==registration){"注册编号已对应其他内容"}
        else atomic(event,json)
        val version=UUID.randomUUID().toString()
        atomic(head(chat),JSONObject().put("schema",1).put("chat",chat).put("version",version).put("events",JSONArray(ids+registration.id)))
        load(chat).first
    }
    internal fun snapshot(chat:String):String=locked {
        val (saved,ids)=load(chat)
        JSONObject().put("schema",1).put("chat",chat).put("version",saved.version?:JSONObject.NULL)
            .put("events",JSONArray(ids.map {id->encode(decode(JSONObject(Utf8Files.read(event(chat,id)))))})).toString()
    }
    internal fun restoreSnapshot(chat:String,snapshot:String)=locked {
        check(!Files.exists(head(chat))){"目标已有快捷操作"}
        val json=JSONObject(snapshot);require(json.getInt("schema")==1 && json.getString("chat")==chat)
        val array=json.getJSONArray("events");val registrations=(0 until array.length()).map {decode(array.getJSONObject(it))}
        require(registrations.map {it.id}.distinct().size==registrations.size){"注册记录重复"}
        var controls=ConversationControls.empty(chat)
        registrations.forEach {controls=controls.register(it)}
        if(registrations.isEmpty()){require(json.isNull("version"));return@locked}
        val version=json.getString("version");require(version.isNotBlank())
        registrations.forEach {registration->
            val file=event(chat,registration.id);check(!Files.exists(file)){"准备区存在未登记的快捷操作"};atomic(file,encode(registration))
        }
        atomic(head(chat),JSONObject().put("schema",1).put("chat",chat).put("version",version).put("events",JSONArray(registrations.map {it.id})))
    }
    fun invoke(handle:ControlHandle,activePath:List<String>):ControlInvocation=read(handle.conversationId).controls.invoke(handle,activePath)
    private fun load(chat:String):Pair<SavedControls,List<String>> {
        require(chat.isNotBlank())
        val head=head(chat)
        if(!Files.exists(head))return SavedControls(null,ConversationControls.empty(chat)) to emptyList()
        val json=JSONObject(Utf8Files.read(head));require(json.getInt("schema")==1 && json.getString("chat")==chat)
        val entries=json.getJSONArray("events");val ids=(0 until entries.length()).map(entries::getString)
        require(ids.distinct().size==ids.size)
        var controls=ConversationControls.empty(chat)
        ids.forEach { id->val registration=decode(JSONObject(Utf8Files.read(event(chat,id))));require(registration.id==id);controls=controls.register(registration) }
        return SavedControls(json.getString("version"),controls) to ids
    }
    private fun encode(value:ControlRegistration)=JSONObject().put("id",value.id).put("message",value.messageId?:JSONObject.NULL)
        .put("controls",JSONArray(value.definitions.map { definition->JSONObject().put("key",definition.key).put("label",definition.label).put("enabled",definition.enabled).apply {
            when(val behavior=definition.behavior) {
                is ControlBehavior.Action->put("behavior","action").put("instruction",behavior.instruction)
                is ControlBehavior.View->put("behavior","view").put("stateKeys",JSONArray(behavior.stateKeys))
            }
        } }))
    private fun decode(json:JSONObject):ControlRegistration {
        val entries=json.getJSONArray("controls")
        return ControlRegistration(json.getString("id"),if(json.isNull("message"))null else json.getString("message"),(0 until entries.length()).map { index->
            val item=entries.getJSONObject(index)
            val behavior=when(item.getString("behavior")) {
                "action"->ControlBehavior.Action(item.getString("instruction"))
                "view"->{val keys=item.getJSONArray("stateKeys");ControlBehavior.View((0 until keys.length()).map(keys::getString))}
                else->error("快捷操作类型不支持")
            }
            ControlDefinition(item.getString("key"),item.getString("label"),behavior,item.getBoolean("enabled"))
        })
    }
    private fun head(chat:String)=root.resolve("head-${hash(chat)}.json")
    private fun event(chat:String,id:String)=root.resolve("event-${hash("${chat.length}:$chat$id")}.json")
    private fun hash(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun atomic(path:Path,value:JSONObject) {
        val pending=root.resolve("pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(pending,CREATE_NEW,WRITE).use { channel->val buffer=java.nio.ByteBuffer.wrap(value.toString().toByteArray(Charsets.UTF_8));while(buffer.hasRemaining())channel.write(buffer);channel.force(true) }
            Files.move(pending,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {Files.deleteIfExists(pending)}
    }
    private fun <T> locked(action:()->T):T=synchronized(mutex){FileChannel.open(root.resolve("write.lock"),CREATE,WRITE).use { it.lock().use { action() } }}
    companion object {private val locks=ConcurrentHashMap<Path,Any>()}
}
