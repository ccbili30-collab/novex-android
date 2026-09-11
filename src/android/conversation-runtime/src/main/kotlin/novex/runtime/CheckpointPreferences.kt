package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.*
import java.nio.file.Path

/** 存档中的非凭据设置。原输入版本仅作来源依据，恢复后的写入版本另行分配。 */
data class CheckpointPreferences(val disabled:Set<String>,val manual:Set<String>,val inputVersion:String?,val model:String?,val capacity:Long) {
    fun encode():String {
        validate()
        return JSONObject().put("schema",1).put("disabledModules",JSONArray(disabled.sorted())).put("manualModules",JSONArray(manual.sorted()))
            .put("inputVersion",inputVersion?:JSONObject.NULL).put("model",model?:JSONObject.NULL).put("contextCapacity",capacity).toString()
    }
    private fun validate() {
        require(capacity>1024 && (model==null || model.isNotBlank()) && (inputVersion==null || inputVersion.isNotBlank())){"存档模型参数或来源版本无效"}
        require((disabled+manual).all {it.isNotBlank()} && disabled.intersect(manual).isEmpty()){"存档资料选择冲突"}
    }
    companion object {
        fun decode(text:String):CheckpointPreferences {
            val value=JSONObject(text)
            require(value.keys().asSequence().toSet()==setOf("schema","disabledModules","manualModules","inputVersion","model","contextCapacity") && value.getInt("schema")==1)
            fun list(key:String):Set<String> {val array=value.getJSONArray(key);val ids=(0 until array.length()).map {require(array.get(it) is String);array.getString(it)};require(ids.distinct().size==ids.size);return ids.toSet()}
            fun optional(key:String):String?=if(value.isNull(key))null else {require(value.get(key) is String);value.getString(key)}
            val capacity=value.get("contextCapacity");require(capacity is Number)
            return CheckpointPreferences(list("disabledModules"),list("manualModules"),optional("inputVersion"),optional("model"),requireNotNull(capacity.toString().toLongOrNull()){"上下文额度必须为整数"}).also {it.validate()}
        }
    }
}
internal object CheckpointPreferenceRestoration {
    fun restore(store:ConversationCheckpoints,sources:PreparedCheckpointSources,directory:Path):Boolean {
        val chat=sources.chat;val id=sources.checkpointId;val keys=requireNotNull(store.read(chat,id)).sections.keys
        require(("settings" in keys)==("input-draft" in keys)){"存档设置与输入草稿不完整"}
        if("settings" !in keys)return false
        val value=store.open(chat,id,"settings").bufferedReader().use {CheckpointPreferences.decode(it.readText())}
        val cards=CardStore(sources.cardsDirectory)
        val target=ContentTargets.find(requireNotNull(cards.open(sources.session.primary.rootId)).content,sources.session.primary.targetId)
        val modules=(listOf(target)+if(target.kind==CardKind.WORLD)target.internalCharacters else emptyList()).flatMap {it.modules.flattenModules()}
        require(modules.map {it.id}.toSet().containsAll(value.disabled)){"存档中暂停携带的模块已缺失"}
        require(modules.filter {it.use==ModuleUse.Manual}.map {it.id}.toSet().containsAll(value.manual)){"存档中的手动资料与作品不一致"}
        val version=ConversationInputDrafts(directory.resolve("input-drafts")).restoreSnapshot(chat){store.open(chat,id,"input-draft")}
        val metadata=JSONObject().put("schema",1).put("settings",JSONObject(value.encode())).put("restoredInputVersion",version)
        Utf8Files.write(directory.resolve("preferences.json"),metadata.toString())
        java.nio.channels.FileChannel.open(directory.resolve("preferences.json"),java.nio.file.StandardOpenOption.WRITE).use {it.force(true)}
        return true
    }
}
