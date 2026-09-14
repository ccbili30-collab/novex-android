package novex.runtime

import novex.content.ContentTargets
import novex.storage.CardStore
import novex.storage.ExchangeLab
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

/** 封存当前作品的完整原生包；临时包只在本次保存期间存在，不让调用方持有失效文件。 */
class ConversationCheckpointWriter(
    private val checkpoints:ConversationCheckpoints,
    private val cards:CardStore,
    private val scratch:Path,
) {
    fun save(session:ConversationSession,policy:CardToolPolicy,id:String,name:String,
             sections:()->List<CheckpointSection>):SavedCheckpoint {
        require(id.isNotBlank() && name.isNotBlank())
        // 已发布的同编号结果是重试依据；不得重新捕获后续内容，或要求原卡仍存在。
        checkpoints.read(session.id,id)?.let {require(it.name==name){"存档编号已用于其他名称"};return it}
        val targets=(policy.targets+ManagementTarget(session.primary.rootId,session.primary.targetId))
            .sortedWith(compareBy({it.rootId},{it.targetId}))
        val roots=targets.map {it.rootId}.distinct().associateWith {root->requireNotNull(cards.open(root)){"存档所需作品不存在"}}
        targets.forEach {target->ContentTargets.find(roots.getValue(target.rootId).content,target.targetId)}
        require(ContentTargets.find(roots.getValue(session.primary.rootId).content,session.primary.targetId).kind==session.kind){"对话使用的对象类型已经变化"}
        Files.createDirectories(scratch)
        val temporary=Files.createTempDirectory(scratch,"checkpoint-")
        try {
            val parts=mutableListOf<CheckpointSection>()
            val manifest=JSONArray()
            roots.entries.forEachIndexed {index,(root,saved)->
                val key="cards/$index/package"
                val file=temporary.resolve("$index.zip")
                ExchangeLab.write(file,saved.content,cards.contents)
                parts+=CheckpointSection(key){Files.newInputStream(file)}
                manifest.put(JSONObject().put("root",root).put("revision",saved.revision).put("section",key))
            }
            val context=JSONObject().put("schema",1)
                .put("session",JSONObject().put("id",session.id).put("title",session.title).put("kind",session.kind.name)
                    .put("root",session.primary.rootId).put("target",session.primary.targetId))
                .put("management",JSONObject().put("permission",policy.permission.name).put("targets",JSONArray(policy.targets.sortedWith(compareBy({it.rootId},{it.targetId})).map {
                    JSONObject().put("root",it.rootId).put("target",it.targetId)
                })))
                .put("cards",manifest).toString().toByteArray(Charsets.UTF_8)
            parts+=CheckpointSection("context"){context.inputStream()}
            val extra=sections()
            require(extra.none {it.key=="context" || it.key.startsWith("cards/")}){"存档分区名称与作品包冲突"}
            check(roots.all {(root,saved)->cards.open(root)?.revision==saved.revision}){"存档捕获期间作品发生变化，请重新保存"}
            return checkpoints.save(session.id,id,name,parts+extra)
        } finally {
            Files.list(temporary).use {files->files.forEach {Files.deleteIfExists(it)}}
            Files.deleteIfExists(temporary)
        }
    }
}
