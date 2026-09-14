package novex.runtime

import novex.conversation.TokenMeasurement
import novex.model.WireMessage
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest

/** 会话发送协调：输入、历史、采用、工具运行与恢复使用同一对话/回合编号。 */
class ConversationSender(private val root:Path,private val sessions:ConversationSessions,
    private val timeline:ConversationTimeline,private val transcript:ConversationTranscript,
    private val host:PersistentToolDialogue,private val builder:DialogueRequestBuilder) {
    init {Files.createDirectories(root)}
    fun send(chat:String,id:String,input:String,settings:TurnSettings,maximumRequests:Int,
             policy:()->CardToolPolicy,measure:(String)->TokenMeasurement,stop:DialogueStop=DialogueStop(),onQueued:()->Unit={}):ConversationTurn=locked(chat) {
        val previous=transcript.read(chat)
        val existing=previous.find {it.id==id}
        if(existing!=null) {
            require(existing.input==input){"回合编号已对应其他输入"}
            if(existing.state!=ReplyState.QUEUED)return@locked existing
        }
        val earlier=if(existing==null)previous else previous.takeWhile {it.id!=id}
        require(earlier.none {it.state in setOf(ReplyState.QUEUED,ReplyState.RUNNING,ReplyState.PAUSED)}){"请先处理前一条未完成消息"}
        timeline.append(chat,id,input)
        onQueued()
        val primary=sessions.primary(chat)
        require(primary in settings.selections){"请求没有采用当前对话的主要作品"}
        val history=earlier.flatMap {turn->listOf(WireMessage("user",turn.input))+
            (turn.reply?.let {listOf(WireMessage("assistant",it))}?:emptyList())}
        host.startConversation(chat,id,input,history,settings,builder,maximumRequests,policy,measure,stop)
        transcript.read(chat).single {it.id==id}
    }
    fun resume(chat:String,id:String,version:String,maximumRequests:Int,policy:()->CardToolPolicy,
               measure:(String)->TokenMeasurement,stop:DialogueStop=DialogueStop()):ConversationTurn=locked(chat) {
        val current=transcript.read(chat).single {it.id==id}
        if(current.state!=ReplyState.PAUSED)return@locked current
        require(current.pauseVersion==version){"暂停点已经变化，请重新读取"}
        host.resume(chat,id,version,maximumRequests,policy,measure,stop)
        transcript.read(chat).single {it.id==id}
    }
    private fun <T> locked(chat:String,action:()->T):T {
        val name=MessageDigest.getInstance("SHA-256").digest(chat.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        return FileChannel.open(root.resolve("send-$name.lock"),CREATE,WRITE).use {channel->
            val lock=try{channel.tryLock()}catch(_:OverlappingFileLockException){null}
            check(lock!=null){"这段对话正在执行，请稍后查看结果"}
            lock.use {action()}
        }
    }
}
