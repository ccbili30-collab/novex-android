package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 只准备存档来源；完整校验后一次发布独立目录，不修改创作库、当前会话或权限。 */
class CheckpointSourceRestoration(root:Path,private val checkpoints:ConversationCheckpoints) {
    private val root=root.toAbsolutePath().normalize()
    private val mutex:Any
    init {Files.createDirectories(this.root);mutex=locks.computeIfAbsent(this.root.toRealPath()){Any()}}
    fun prepare(chat:String,id:String):PreparedCheckpointSources=locked {
        requireNotNull(checkpoints.read(chat,id)){"存档不存在"}
        val target=root.resolve(key(chat,id))
        if(Files.exists(target))return@locked decode(target,chat,id)
        val stage=Files.createTempDirectory(root,"preparing-")
        try {
            val context=checkpoints.open(chat,id,"context").bufferedReader().use {JSONObject(it.readText())}
            require(context.getInt("schema")==1)
            val session=session(context);require(session.id==chat){"存档对话编号不一致"}
            val policy=policy(context)
            val entries=context.getJSONArray("cards")
            val roots=mutableSetOf<String>();val sections=mutableSetOf<String>()
            val cards=CardStore(stage.resolve("cards"))
            for(index in 0 until entries.length()) {
                val entry=entries.getJSONObject(index);val expected=entry.getString("root");val section=entry.getString("section")
                require(roots.add(expected) && sections.add(section) && Regex("cards/[0-9]+/package").matches(section)){"存档作品清单重复或无效"}
                val file=stage.resolve("package.zip")
                checkpoints.open(chat,id,section).use {Files.copy(it,file)}
                val candidate=ExchangeLab.restoreSnapshot(file,cards.contents)
                require(candidate.id==expected){"存档作品编号与清单不一致"}
                cards.save(candidate,null,ChangeSource.IMPORT,entry.getString("revision"))
                Files.delete(file)
            }
            val targets=policy.targets+ManagementTarget(session.primary.rootId,session.primary.targetId)
            require(roots==targets.map {it.rootId}.toSet()){"存档作品与使用／管理范围不一致"}
            targets.forEach {selection->ContentTargets.find(requireNotNull(cards.open(selection.rootId)).content,selection.targetId)}
            require(ContentTargets.find(requireNotNull(cards.open(session.primary.rootId)).content,session.primary.targetId).kind==session.kind)
            val record=JSONObject().put("schema",1).put("chat",chat).put("checkpoint",id).put("context",context)
            Utf8Files.write(stage.resolve("prepared.json"),record.toString())
            FileChannel.open(stage.resolve("prepared.json"),WRITE).use {it.force(true)}
            Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE)
            decode(target,chat,id)
        } finally {
            if(Files.exists(stage))Files.walk(stage).use {paths->paths.sorted(Comparator.reverseOrder()).forEach {Files.deleteIfExists(it)}}
        }
    }
    private fun decode(directory:Path,chat:String,id:String):PreparedCheckpointSources {
        val record=JSONObject(Utf8Files.read(directory.resolve("prepared.json")))
        require(record.getInt("schema")==1 && record.getString("chat")==chat && record.getString("checkpoint")==id)
        val context=record.getJSONObject("context");val cards=CardStore(directory.resolve("cards"));val entries=context.getJSONArray("cards")
        for(index in 0 until entries.length()) {
            val entry=entries.getJSONObject(index);val saved=requireNotNull(cards.open(entry.getString("root")))
            require(saved.revision==entry.getString("revision")){"存档准备区域已变化"}
            cards.verify(saved.content.id,saved.revision)
        }
        return PreparedCheckpointSources(chat,id,directory.resolve("cards"),session(context),policy(context))
    }
    private fun session(context:JSONObject):ConversationSession {
        val value=context.getJSONObject("session")
        return ConversationSession(value.getString("id"),value.getString("title"),SourceSelection(value.getString("root"),value.getString("target")),CardKind.valueOf(value.getString("kind")))
    }
    private fun policy(context:JSONObject):CardToolPolicy {
        val value=context.getJSONObject("management");val entries=value.getJSONArray("targets")
        val targets=(0 until entries.length()).map {val entry=entries.getJSONObject(it);ManagementTarget(entry.getString("root"),entry.getString("target"))}
        require(targets.distinct().size==targets.size)
        return CardToolPolicy(targets.toSet(),ToolPermission.valueOf(value.getString("permission")))
    }
    private fun key(chat:String,id:String):String {
        require(chat.isNotBlank() && id.isNotBlank())
        return "prepared-"+MessageDigest.getInstance("SHA-256").digest("${chat.length}:$chat$id".toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun <T> locked(action:()->T):T=synchronized(mutex){FileChannel.open(root.resolve("prepare.lock"),CREATE,WRITE).use {it.lock().use {action()}}}
    companion object {private val locks=ConcurrentHashMap<Path,Any>()}
}
data class PreparedCheckpointSources(val chat:String,val checkpointId:String,val cardsDirectory:Path,val session:ConversationSession,val policy:CardToolPolicy)
