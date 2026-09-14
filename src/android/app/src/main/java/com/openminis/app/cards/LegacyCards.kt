package com.openminis.app.cards

import com.openminis.app.MinisApp
import com.openminis.app.data.character.*
import com.openminis.app.novex.domain.*
import novex.content.*
import novex.storage.*
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 只从原卡库读取，按固定编号写新副本；不修改原表或历史快照。 */
class LegacyCards(private val app:MinisApp) {
    private val cards=IntegratedCards(app).store
    private val workspace=app.novexWorkspace
    private val exporter=NovexCardExporter({java.io.File(it.managedPath).readBytes()},{workspace.moduleReferences(it)})
    companion object {private val lock=Mutex();private var report:List<String>?=null;fun worldId(id:String)="legacy-world:$id";fun roleId(id:String)="legacy-role:$id";fun gameId(id:String)="legacy-game:$id"}
    suspend fun migrate(force:Boolean=false):List<String> = lock.withLock {
        if(!force)report?.let {return@withLock it}
        val failures=mutableListOf<String>()
        suspend fun attempt(label:String,action:suspend ()->Unit) {
            try{action()}catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(failure:Exception){failures+="$label：${failure.message?:"副本未能准备"}"}
        }
        workspace.characters().forEach {summary->attempt(summary.character.character.name) {
            val snapshot=requireNotNull(workspace.character(summary.character.character.id))
            snapshot.character.allVersions.filterNot { cards.isDeleted(roleId(it.id)) }.forEach {version->attempt(version.label){role(snapshot,version)}}
        }}
        workspace.worlds().forEach {summary->attempt(summary.world.name) {
            val id=worldId(summary.world.id)
            if(cards.open(id)==null && !cards.isDeleted(id)) {
                val snapshot=requireNotNull(workspace.world(summary.world.id))
                val children=snapshot.versions.map {version->
                    val saved=cards.open(roleId(version.id))?:cards.deletedCard(roleId(version.id))?:role(requireNotNull(workspace.characterForVersion(version.id)),version)
                    val plan=ContentCopies.plan(saved.content,{UUID.randomUUID().toString()},cards.contents.allocator())
                    cards.contents.receive(plan.transfers){cards.contents.open(it)}
                    plan.candidate
                }
                val content=convert(id,CardKind.WORLD,snapshot.world.name,snapshot.world.overview,snapshot.modules,snapshot.media,snapshot.moduleImages,snapshot.moduleItemImages,
                    exporter.world(snapshot,linkedMapOf()).documentJson).copy(internalCharacters=children)
                cards.save(content,null,ChangeSource.IMPORT,"migration:$id")
            }
        }
        }
        // 文游资料保留成明确来源的世界副本，不猜测它与别的世界合并关系。
        workspace.interactiveFictions().forEach {summary->attempt(summary.project.name) {
            val id=gameId(summary.project.id)
            if(cards.open(id)==null && !cards.isDeleted(id)) {
                val snapshot=requireNotNull(workspace.interactiveFiction(summary.project.id))
                val content=convert(id,CardKind.WORLD,snapshot.project.name,snapshot.project.summary,snapshot.modules,snapshot.media,snapshot.moduleImages,snapshot.moduleItemImages,exporter.game(snapshot,linkedMapOf()).documentJson)
                cards.save(content,null,ChangeSource.IMPORT,"migration:$id")
            }
        }
        }
        failures.toList().also {report=it}
    }
    private suspend fun role(snapshot:NovexCharacterSnapshot,version:CharacterVersionEntity):SavedCard {
        val id=roleId(version.id);cards.open(id)?.let {return it}
        val name=snapshot.character.character.name+if(version.kind==CharacterVersionKind.ORIGINAL)"" else " · ${version.label}"
        val content=convert(id,CardKind.CHARACTER,name,version.profileJson,snapshot.modulesByVersion[version.id].orEmpty(),snapshot.mediaByVersion[version.id].orEmpty(),snapshot.moduleImages,snapshot.moduleItemImages,exporter.character(snapshot,workspace.versionRelations(version.id),setOf(version.id),linkedMapOf()).documentJson)
        return cards.save(content,null,ChangeSource.IMPORT,"migration:$id")
    }
    private fun text(value:String):ContentRef {
        val ref=cards.contents.allocator()();cards.contents.receive(listOf(ContentTransfer(ContentRef("legacy-input"),ref))){value.byteInputStream()};return ref
    }
    private fun convert(id:String,kind:CardKind,name:String,initial:String,modules:List<ContentModuleEntity>,media:Map<MediaAssetSlot,MediaAssetEntity>,images:Map<String,MediaAssetEntity>,itemImages:Map<String,Map<String,MediaAssetEntity>>,raw:String):ContentDocument {
        val resources=linkedMapOf<String,CardResource>()
        val extensions=linkedMapOf("legacy/raw" to text(raw))
        fun asset(value:MediaAssetEntity):String {
            return resources.getOrPut(value.id){val ref=cards.contents.allocator()();cards.contents.receive(listOf(ContentTransfer(ContentRef("legacy-image"),ref))){java.io.File(value.managedPath).inputStream()};CardResource("$id:resource:${value.id}",ref,value.mimeType)}.id
        }
        val result=mutableListOf<ContentModule>()
        if(initial.isNotBlank() && initial!="{}")result+=ContentModule("$id:original","",listOf(ContentBlock.Text("$id:original:text",text(initial))))
        modules.sortedBy {it.position}.forEach {module->
            val mid="$id:module:${module.id}"
            extensions["legacy/module/${module.id}"]=text(module.contentJson)
            val blocks=mutableListOf<ContentBlock>()
            val document=ContentModuleDocumentCodec.decode(module.type,module.contentJson)
            images[module.id]?.let {blocks+=ContentBlock.Image("$mid:image",asset(it))}
            if(document is ContentModuleDocument.Collection)document.items.forEachIndexed {index,item->
                blocks+=ContentBlock.Text("$mid:item:$index:text",text(listOf(item.name,item.summary,item.description).filter {it.isNotBlank()}.joinToString("\n")))
                itemImages[module.id]?.get(item.id)?.let {blocks+=ContentBlock.Image("$mid:item:$index:image",asset(it))}
            } else blocks+=ContentBlock.Text("$mid:text",text(document.toPlainText()))
            // 尚未识别的旧图片位置仍作为本卡素材保留。
            itemImages[module.id].orEmpty().values.forEach {asset(it)}
            result+=ContentModule(mid,module.name,blocks)
        }
        media.values.forEach {asset(it)}
        val avatar=media[MediaAssetSlot.CHARACTER_AVATAR]?:media[MediaAssetSlot.WORLD_LOGO]
        val cover=media[MediaAssetSlot.WORLD_COVER]?:media[MediaAssetSlot.INTERACTIVE_FICTION_COVER]
        extensions["legacy/media-map"]=text(JSONObject().apply {resources.forEach {(old,value)->put(old,value.id)}}.toString())
        return ContentDocument(id,kind,name,result,resources.values.toList(),extensions=extensions,appearance=CardAppearance(avatar?.let(::asset),cover?.let(::asset))).validate()
    }
}
