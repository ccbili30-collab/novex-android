package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConversationInputDraft(val version:String?,val text:String)

/** 未发送文字独立于消息历史；版本约束防止旧页面覆盖另一页面的新输入。 */
class ConversationInputDrafts(root:Path) {
    private val root=root.toAbsolutePath().normalize()
    private val mutex:Any
    private val contents=StagedContentFiles(this.root.resolve("contents"))
    init {Files.createDirectories(this.root);mutex=locks.computeIfAbsent(this.root.toRealPath()){Any()}}
    fun read(chat:String):ConversationInputDraft=locked {readUnlocked(chat)}
    fun save(chat:String,expectedVersion:String?,text:String):ConversationInputDraft=locked {
        val current=readUnlocked(chat)
        check(current.version==expectedVersion){"输入草稿已在其他页面更新，本次未覆盖"}
        if(current.text==text)return@locked current
        val content=contents.allocator()()
        contents.receive(listOf(ContentTransfer(ContentRef("input-draft"),content))){text.byteInputStream(Charsets.UTF_8)}
        val next=ConversationInputDraft(UUID.randomUUID().toString(),text)
        publish(chat,requireNotNull(next.version),content)
        next
    }
    /** 独立恢复目标接收原文流并分配新版本；不沿用旧页面的写入版本。 */
    internal fun restoreSnapshot(chat:String,source:()->java.io.InputStream):String=locked {
        require(chat.isNotBlank());check(!Files.exists(path(chat))){"目标已有输入草稿"}
        val ref=contents.allocator()()
        contents.receive(listOf(ContentTransfer(ContentRef("input-draft"),ref))){source()}
        val version=UUID.randomUUID().toString();publish(chat,version,ref);version
    }
    private fun publish(chat:String,version:String,content:ContentRef) {
        val json=JSONObject().put("schema",1).put("chat",chat).put("version",version).put("content",content.value)
        val pending=root.resolve("pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(pending,CREATE_NEW,WRITE).use {channel->
                val bytes=ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8))
                while(bytes.hasRemaining())channel.write(bytes)
                channel.force(true)
            }
            Files.move(pending,path(chat),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {Files.deleteIfExists(pending)}
    }
    private fun readUnlocked(chat:String):ConversationInputDraft {
        require(chat.isNotBlank()){"对话编号不能为空"}
        val path=path(chat);if(!Files.exists(path))return ConversationInputDraft(null,"")
        val json=JSONObject(Utf8Files.read(path));require(json.getInt("schema")==1 && json.getString("chat")==chat)
        val text=contents.open(ContentRef(json.getString("content"))).bufferedReader(Charsets.UTF_8).use {it.readText()}
        return ConversationInputDraft(json.getString("version"),text)
    }
    private fun path(chat:String)=root.resolve("draft-${MessageDigest.getInstance("SHA-256").digest(chat.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}}.json")
    private fun <T> locked(action:()->T):T=synchronized(mutex){FileChannel.open(root.resolve("write.lock"),CREATE,WRITE).use {it.lock().use {action()}}}
    companion object {private val locks=ConcurrentHashMap<Path,Any>()}
}
