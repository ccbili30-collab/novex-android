package novex.runtime

import novex.storage.Utf8Files

import novex.content.*
import novex.storage.StagedContentFiles
import org.json.JSONObject
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TurnState { QUEUED, RUNNING, FINISHED }
data class StoredTurn(val conversationId:String,val id:String,val state:TurnState,val input:ContentRef,
                      val trace:ContentRef?,val outcome:ContentRef?,val selected:Boolean=false,val details:ContentRef?=null)
/** 回合输入与结果独立落盘；运行中断后保持未确认，不自动再次请求。 */
class TurnJournal(root:Path) {
    private val root=root.toAbsolutePath().normalize()
    private val contents=StagedContentFiles(this.root.resolve("contents"))
    private val mutex:Any
    init { Files.createDirectories(this.root);mutex=locks.computeIfAbsent(this.root.toRealPath()){Any()} }
    /** 执行及中断核对共用操作锁；进程退出即释放，不把仍在运行的操作判成中断。 */
    fun <T> exclusiveExecution(chat:String,id:String,action:()->T):T? {
        val file=path(chat,id).resolveSibling(path(chat,id).fileName.toString()+".execution")
        return FileChannel.open(file,CREATE,WRITE).use {channel->
            val held=try{channel.tryLock()}catch(_:java.nio.channels.OverlappingFileLockException){null}
            if(held==null)null else held.use {action()}
        }
    }
    fun entries(chat:String):List<StoredTurn> = locked {
        Files.list(root).use {paths->paths.filter {it.fileName.toString().startsWith("turn-") && it.fileName.toString().endsWith(".json")}
            .map {JSONObject(Utf8Files.read(it))}.filter {it.getString("chat")==chat}
            .map {requireNotNull(load(chat,it.getString("id")))}.collect(java.util.stream.Collectors.toList())}
    }
    fun read(chat:String,id:String):StoredTurn?=locked { load(chat,id) }
    fun open(ref:ContentRef):java.io.InputStream=contents.open(ref)
    fun text(ref:ContentRef)=contents.open(ref).bufferedReader(Charsets.UTF_8).use { it.readText() }
    fun enqueue(chat:String,id:String,input:String):StoredTurn=locked {
        require(chat.isNotBlank() && id.isNotBlank())
        val previous=load(chat,id)
        if(previous!=null) { require(text(previous.input)==input) { "回合编号已对应其他输入" };return@locked previous }
        write(StoredTurn(chat,id,TurnState.QUEUED,put(input),null,null))
    }
    /** 只保存选择状态，不取得执行权。 */
    fun select(chat:String,id:String,selected:Boolean):StoredTurn=locked {
        val current=requireNotNull(load(chat,id));check(current.state==TurnState.QUEUED){"操作已开始或结束"}
        write(current.copy(selected=selected))
    }
    fun claim(chat:String,id:String):StoredTurn?=locked {
        val current=requireNotNull(load(chat,id))
        if(current.state!=TurnState.QUEUED)return@locked null
        write(current.copy(state=TurnState.RUNNING))
    }
    /** 用户拒绝等待中的操作：与领取执行互斥，一次原子发布结束状态。 */
    fun finishQueued(chat:String,id:String,input:String,outcome:JSONObject):StoredTurn=locked {
        val current=requireNotNull(load(chat,id))
        require(text(current.input)==input){"待处理操作已改变"}
        if(current.state!=TurnState.QUEUED)return@locked current
        write(current.copy(state=TurnState.FINISHED,selected=false,outcome=put(outcome.toString())))
    }
    fun prepared(chat:String,id:String,trace:JSONObject,details:JSONObject?=null):StoredTurn=locked {
        val current=requireNotNull(load(chat,id));check(current.state==TurnState.RUNNING && current.trace==null)
        write(current.copy(trace=put(trace.toString()),details=details?.let {put(it.toString())}))
    }
    fun finish(chat:String,id:String,outcome:JSONObject):StoredTurn=locked {
        val current=requireNotNull(load(chat,id));check(current.state==TurnState.RUNNING)
        write(current.copy(state=TurnState.FINISHED,outcome=put(outcome.toString())))
    }
    /** 在空目标中流式还原既有记录，不领取执行权、不发起请求，也不覆盖已有记录。 */
    internal fun restoreSnapshot(chat:String,id:String,state:TurnState,selected:Boolean,parts:Map<String,()->java.io.InputStream>):StoredTurn=locked {
        require(chat.isNotBlank() && id.isNotBlank() && "input" in parts && parts.keys.all {it in setOf("input","trace","details","outcome")})
        require((state==TurnState.FINISHED)==("outcome" in parts)){"执行状态与结果记录不一致"}
        require("details" !in parts || "trace" in parts){"请求明细缺少对应依据"}
        require(state!=TurnState.QUEUED || ("trace" !in parts && "details" !in parts)){"未执行记录不应含请求依据"}
        check(load(chat,id)==null){"恢复目标已经存在，不覆盖原记录"}
        val allocate=contents.allocator();val refs=parts.keys.associateWith {allocate()}
        contents.receive(refs.map {(key,ref)->ContentTransfer(ContentRef(key),ref)}) {source->parts.getValue(source.value).invoke()}
        write(StoredTurn(chat,id,state,refs.getValue("input"),refs["trace"],refs["outcome"],selected,refs["details"]))
    }

    private fun put(text:String):ContentRef {
        val ref=contents.allocator()()
        contents.receive(listOf(ContentTransfer(ContentRef("turn-input"),ref))){ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))}
        return ref
    }
    private fun path(chat:String,id:String):Path {
        val key=MessageDigest.getInstance("SHA-256").digest((chat.length.toString()+":"+chat+id).toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        return root.resolve("turn-$key.json")
    }
    private fun load(chat:String,id:String):StoredTurn? {
        val file=path(chat,id);if(!Files.exists(file))return null
        val json=JSONObject(Utf8Files.read(file));require(json.getInt("schema")==1)
        check(json.getString("chat")==chat && json.getString("id")==id)
        fun ref(key:String)=if(json.isNull(key))null else ContentRef(json.getString(key))
        return StoredTurn(chat,id,TurnState.valueOf(json.getString("state")),ref("input")!!,ref("trace"),ref("outcome"),json.optBoolean("selected",false),ref("details"))
    }
    private fun write(turn:StoredTurn):StoredTurn {
        val json=JSONObject().put("schema",1).put("chat",turn.conversationId).put("id",turn.id).put("state",turn.state.name)
            .put("details",turn.details?.value?:JSONObject.NULL).put("selected",turn.selected).put("input",turn.input.value).put("trace",turn.trace?.value?:JSONObject.NULL).put("outcome",turn.outcome?.value?:JSONObject.NULL)
        val pending=root.resolve("pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(pending,CREATE_NEW,WRITE).use { channel ->
                val buffer=java.nio.ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8))
                while(buffer.hasRemaining())channel.write(buffer)
                channel.force(true)
            }
            Files.move(pending,path(turn.conversationId,turn.id),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        } finally {Files.deleteIfExists(pending)}
        return turn
    }
    private fun <T> locked(action:()->T):T=synchronized(mutex){FileChannel.open(root.resolve("write.lock"),CREATE,WRITE).use { it.lock().use { action() } }}
    companion object { private val locks=ConcurrentHashMap<Path,Any>() }
}
