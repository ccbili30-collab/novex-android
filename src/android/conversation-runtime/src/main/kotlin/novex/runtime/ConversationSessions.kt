package novex.runtime

import novex.content.CardKind
import novex.content.ContentTargets
import novex.storage.CardStore
import novex.storage.Utf8Files
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

/** 对话入口记录只保存来源编号，不复制卡片正文或推导管理权限。 */
data class ConversationSession(val id:String,val title:String,val primary:SourceSelection,val kind:CardKind)

class ConversationSessions(root:Path,private val cards:CardStore) {
    private val root=root.toAbsolutePath().normalize()
    private val mutex:Any
    init {Files.createDirectories(this.root);mutex=locks.computeIfAbsent(this.root.toRealPath()){Any()}}

    /** 调用者保留同一编号可恢复重复点击；同编号不允许指向另一张卡。 */
    fun create(id:String,title:String,primary:SourceSelection):ConversationSession=locked {
        require(id.isNotBlank() && title.isNotBlank()){"对话编号和名称不能为空"}
        readUnlocked(id)?.let {
            require(it.title==title && it.primary==primary){"对话编号已用于其他入口"}
            return@locked it
        }
        val saved=requireNotNull(cards.open(primary.rootId)){"作品尚未保存或已经不存在"}
        val target=ContentTargets.find(saved.content,primary.targetId)
        val session=ConversationSession(id,title,primary,target.kind)
        val json=JSONObject().put("schema",1).put("id",id).put("title",title)
            .put("root",primary.rootId).put("target",primary.targetId).put("kind",target.kind.name)
        val pending=root.resolve("pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(pending,CREATE_NEW,WRITE).use {channel->
                val bytes=ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8))
                while(bytes.hasRemaining())channel.write(bytes)
                channel.force(true)
            }
            Files.move(pending,path(id),StandardCopyOption.ATOMIC_MOVE)
        } finally {Files.deleteIfExists(pending)}
        session
    }
    fun read(id:String):ConversationSession?=locked {readUnlocked(id)}
    fun list():List<ConversationSession> = locked {
        Files.list(root).use {paths->paths.filter {it.fileName.toString().startsWith("session-") && it.fileName.toString().endsWith(".json")}
            .map {decode(it)}.collect(Collectors.toList())}.sortedWith(compareBy({it.title},{it.id}))
    }
    /** 每次使用都核对当前正式对象；丢失来源不得静默退化为空白对话。 */
    fun primary(id:String):SourceSelection=locked {
        val session=requireNotNull(readUnlocked(id)){"对话不存在"}
        val current=requireNotNull(cards.open(session.primary.rootId)){"对话使用的作品已经不存在"}
        val target=ContentTargets.find(current.content,session.primary.targetId)
        require(target.kind==session.kind){"对话使用的对象类型已经变化"}
        session.primary
    }
    /** 世界互动显式提供世界及内部角色供模块规则筛选；角色互动只提供自身。 */
    fun sources(id:String):List<SourceSelection> = locked {
        val session=requireNotNull(readUnlocked(id)){"对话不存在"}
        val current=requireNotNull(cards.open(session.primary.rootId)){"对话使用的作品已经不存在"}
        val target=ContentTargets.find(current.content,session.primary.targetId)
        require(target.kind==session.kind){"对话使用的对象类型已经变化"}
        listOf(session.primary)+if(target.kind==CardKind.WORLD)target.internalCharacters.map {SourceSelection(session.primary.rootId,it.id)} else emptyList()
    }
    private fun readUnlocked(id:String):ConversationSession?=path(id).let {if(Files.exists(it))decode(it).also {value->require(value.id==id){"对话记录编号不一致"}} else null}
    private fun decode(path:Path):ConversationSession {
        val json=JSONObject(Utf8Files.read(path));require(json.getInt("schema")==1){"对话记录版本尚不支持"}
        return ConversationSession(json.getString("id"),json.getString("title"),SourceSelection(json.getString("root"),json.getString("target")),CardKind.valueOf(json.getString("kind")))
    }
    private fun path(id:String)=root.resolve("session-${MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}}.json")
    private fun <T> locked(action:()->T):T=synchronized(mutex){FileChannel.open(root.resolve("write.lock"),CREATE,WRITE).use {channel->channel.lock().use {action()}}}
    companion object {private val locks=ConcurrentHashMap<Path,Any>()}
}
