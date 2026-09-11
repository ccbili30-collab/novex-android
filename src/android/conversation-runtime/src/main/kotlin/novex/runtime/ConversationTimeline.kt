package novex.runtime

import novex.storage.Utf8Files
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 会话只索引回合顺序，正文与结果继续使用共同回合记录。 */
class ConversationTimeline(root:Path,private val sessions:ConversationSessions,private val journal:TurnJournal) {
    private val root=root.toAbsolutePath().normalize()
    private val mutex:Any
    init {Files.createDirectories(this.root);mutex=locks.computeIfAbsent(this.root.toRealPath()){Any()}}
    fun append(chat:String,id:String,input:String):StoredTurn=append(chat,id,input){}
    internal fun append(chat:String,id:String,input:String,afterEnqueue:()->Unit):StoredTurn=locked {
        requireNotNull(sessions.read(chat)){"对话不存在"}
        require(input.isNotBlank()){"消息不能为空"}
        val ids=ids(chat)
        val turn=journal.enqueue(chat,id,input)
        if(id !in ids) {
            afterEnqueue()
            write(chat,ids+id)
        }
        turn
    }
    fun read(chat:String):List<StoredTurn> = locked {
        requireNotNull(sessions.read(chat)){"对话不存在"}
        ids(chat).map {id->requireNotNull(journal.read(chat,id)){"会话回合记录缺失"}}
    }
    /** 正文已在隔离区全部还原后登记顺序；已有顺序不能被覆盖。 */
    internal fun restoreOrder(chat:String,turnIds:List<String>)=locked {
        requireNotNull(sessions.read(chat)){"对话不存在"}
        require(turnIds.all {it.isNotBlank()} && turnIds.distinct().size==turnIds.size)
        check(!Files.exists(path(chat))){"恢复目标已有消息顺序"}
        turnIds.forEach {requireNotNull(journal.read(chat,it)){"恢复消息缺失"}}
        write(chat,turnIds)
    }
    fun openInput(turn:StoredTurn)=journal.open(turn.input)
    fun input(turn:StoredTurn)=journal.text(turn.input)
    private fun ids(chat:String):List<String> {
        val file=path(chat);if(!Files.exists(file))return emptyList()
        val json=JSONObject(Utf8Files.read(file))
        require(json.getInt("schema")==1 && json.getString("chat")==chat){"会话顺序记录不一致"}
        val items=json.getJSONArray("turns")
        return (0 until items.length()).map(items::getString).also {require(it.distinct().size==it.size){"会话回合重复"}}
    }
    private fun write(chat:String,ids:List<String>) {
        val value=JSONObject().put("schema",1).put("chat",chat).put("turns",JSONArray(ids))
        val pending=root.resolve("pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(pending,CREATE_NEW,WRITE).use {channel->val bytes=ByteBuffer.wrap(value.toString().toByteArray(Charsets.UTF_8));while(bytes.hasRemaining())channel.write(bytes);channel.force(true)}
            Files.move(pending,path(chat),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {Files.deleteIfExists(pending)}
    }
    private fun path(chat:String)=root.resolve("timeline-${MessageDigest.getInstance("SHA-256").digest(chat.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}}.json")
    private fun <T> locked(action:()->T):T=synchronized(mutex){FileChannel.open(root.resolve("write.lock"),CREATE,WRITE).use {channel->channel.lock().use {action()}}}
    companion object {private val locks=ConcurrentHashMap<Path,Any>()}
}
