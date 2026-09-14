package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CheckpointSection(val key:String,val open:()->InputStream)
data class SavedCheckpoint(val id:String,val chat:String,val name:String,val createdAt:Long,val sections:Map<String,ContentRef>)

/** 不可变原始记录存档。完整文件批次成功后才登记，名字相同也不覆盖旧存档。 */
class ConversationCheckpoints(root:Path) {
    private val root=root.toAbsolutePath().normalize();private val contents=StagedContentFiles(this.root.resolve("contents"));private val mutex:Any
    init {Files.createDirectories(this.root);mutex=locks.computeIfAbsent(this.root.toRealPath()){Any()}}
    fun save(chat:String,id:String,name:String,sections:List<CheckpointSection>):SavedCheckpoint=locked {
        require(chat.isNotBlank() && id.isNotBlank() && name.isNotBlank() && sections.isNotEmpty())
        require(sections.all {it.key.isNotBlank()} && sections.map {it.key}.distinct().size==sections.size)
        readUnlocked(chat,id)?.let {require(it.name==name && it.sections.keys==sections.map {s->s.key}.toSet()){"存档编号已用于其他内容"};return@locked it}
        val allocate=contents.allocator();val refs=sections.associate {it.key to allocate()}
        val sources=sections.associateBy {it.key}
        contents.receive(refs.map {(key,ref)->ContentTransfer(ContentRef(key),ref)}){source->sources.getValue(source.value).open()}
        val value=SavedCheckpoint(id,chat,name,System.currentTimeMillis(),refs)
        val json=JSONObject().put("schema",1).put("id",id).put("chat",chat).put("name",name).put("createdAt",value.createdAt)
            .put("sections",JSONObject(refs.mapValues {it.value.value}))
        val pending=root.resolve("pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(pending,CREATE_NEW,WRITE).use {channel->val bytes=ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true)}
            Files.move(pending,path(chat,id),StandardCopyOption.ATOMIC_MOVE)
        }finally{Files.deleteIfExists(pending)}
        requireNotNull(readUnlocked(chat,id))
    }
    fun read(chat:String,id:String):SavedCheckpoint?=locked(true) {readUnlocked(chat,id)}
    fun list(chat:String):List<SavedCheckpoint> = locked(true) {
        Files.list(root).use {paths->paths.filter {it.fileName.toString().startsWith("checkpoint-") && it.fileName.toString().endsWith(".json")}
            .map {decode(it)}.filter {it.chat==chat}.collect(java.util.stream.Collectors.toList())}.sortedByDescending {it.createdAt}
    }
    fun open(chat:String,id:String,key:String):InputStream {
        val saved=requireNotNull(read(chat,id)){"存档不存在"}
        return contents.open(requireNotNull(saved.sections[key]){"存档中没有这份记录"})
    }
    private fun readUnlocked(chat:String,id:String):SavedCheckpoint?=path(chat,id).let {if(Files.exists(it))decode(it).also {value->require(value.chat==chat && value.id==id)} else null}
    private fun decode(path:Path):SavedCheckpoint {
        val json=JSONObject(Utf8Files.read(path));require(json.getInt("schema")==1)
        val sections=json.getJSONObject("sections")
        return SavedCheckpoint(json.getString("id"),json.getString("chat"),json.getString("name"),json.getLong("createdAt"),sections.keys().asSequence().associateWith {ContentRef(sections.getString(it))})
    }
    private fun path(chat:String,id:String):Path {require(chat.isNotBlank() && id.isNotBlank());val hash=MessageDigest.getInstance("SHA-256").digest("${chat.length}:$chat$id".toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)};return root.resolve("checkpoint-$hash.json")}
    private fun <T> locked(allowReadReentry:Boolean=false,action:()->T):T {
        // 来源流可能读取同库不可变存档；本线程已有进程／文件锁时不要重复申请文件锁。
        if(Thread.holdsLock(mutex)){check(allowReadReentry){"不支持在存档保存过程中再次发起保存"};return action()}
        return synchronized(mutex){FileChannel.open(root.resolve("write.lock"),CREATE,WRITE).use {it.lock().use {action()}}}
    }
    companion object {private val locks=ConcurrentHashMap<Path,Any>()}
}
