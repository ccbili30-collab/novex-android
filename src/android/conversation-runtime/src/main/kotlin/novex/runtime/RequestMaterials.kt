package novex.runtime

import novex.content.*
import novex.conversation.*
import novex.storage.CardStore
import java.io.InputStream

/** 明确选用的作品及对象；世界内部角色必须有自己的目标编号。 */
data class SourceSelection(val rootId: String, val targetId: String = rootId)
data class MaterialText(val cardId: String, val revision: String, val moduleId: String, val blockId: String,
                        val reference: ContentRef, val caption: Boolean,val sourceName:String,val sourceKind:CardKind,val moduleName:String)
data class ImagePlacement(val cardId: String, val moduleId: String, val blockId: String, val resourceId: String)
data class RequestMaterialDraft(val plan: MaterialPlan, val texts: List<MaterialText>, val imagesNotSent: List<ImagePlacement>,val sourceNames:Map<String,String>,val availableTexts:List<MaterialText> = texts,val availableImages:List<ImagePlacement> = imagesNotSent)

/** 从真实正式修订组装；返回引用与明确来源，不复制进聊天历史，也不提供修改权限。 */
class RequestMaterials(private val store: CardStore) {
    /** 仅列出明确授权目标的元数据，不读取模块正文或展开世界内其他角色。 */
    fun managementDirectory(targets:Set<ManagementTarget>):List<ManagedObject> {
        val snapshots=targets.map {it.rootId}.distinct().associateWith {requireNotNull(store.open(it)){"管理作品不存在：$it"}}
        return targets.sortedWith(compareBy({it.rootId},{it.targetId})).map {selected->
            val target=ContentTargets.find(snapshots.getValue(selected.rootId).content,selected.targetId)
            ManagedObject(selected.rootId,target.id,target.name,target.kind)
        }
    }
    fun prepare(selections: List<SourceSelection>, managedIds: Set<String>, messages: List<TriggerMessage>,
                window: TriggerWindow, overrides: Map<String,UseOverride> = emptyMap(),
                manualForThisRequest: Set<String> = emptySet()): RequestMaterialDraft {
        val snapshots=selections.map { it.rootId }.distinct().associateWith { id ->
            requireNotNull(store.open(id)) { "采用作品不存在：$id" }
        }
        val names=mutableMapOf<String,ContentDocument>()
        val sources=selections.map { selection ->
            val saved=snapshots.getValue(selection.rootId)
            val target=ContentTargets.find(saved.content,selection.targetId)
            names[target.id]=target
            AdoptedSource(target.id,saved.revision,target.modules)
        }
        val plan=ModuleAdoption.plan(MaterialScope(sources,managedIds),messages,window,overrides,manualForThisRequest)
        val texts=mutableListOf<MaterialText>();val images=mutableListOf<ImagePlacement>()
        plan.decisions.forEach { decision -> decision.module.blocks.forEach { block ->
            when(block) {
                is ContentBlock.Text -> texts += MaterialText(decision.cardId,decision.revision,decision.module.id,block.id,block.content,false,names.getValue(decision.cardId).name,names.getValue(decision.cardId).kind,decision.module.name)
                is ContentBlock.Image -> {
                    block.caption?.let { texts += MaterialText(decision.cardId,decision.revision,decision.module.id,block.id,it,true,names.getValue(decision.cardId).name,names.getValue(decision.cardId).kind,decision.module.name) }
                    images += ImagePlacement(decision.cardId,decision.module.id,block.id,block.resourceId)
                }
            }
        } }
        val selected=plan.selected.map {it.module.id}.toSet()
        return RequestMaterialDraft(plan,texts.filter {it.moduleId in selected},images.filter {it.moduleId in selected},names.mapValues {it.value.name},texts,images)
    }

    fun selectAutomatic(draft:RequestMaterialDraft,ids:Set<String>):RequestMaterialDraft {
        val candidates=draft.plan.decisions.filter {it.reason==AdoptionReason.UNCONFIGURED}.map {it.module.id}.toSet()
        require(candidates.containsAll(ids)){"模型选择了候选范围之外的模块"}
        val plan=MaterialPlan(draft.plan.decisions.map {decision->
            if(decision.reason!=AdoptionReason.UNCONFIGURED)decision
            else decision.copy(selected=decision.module.id in ids,reason=if(decision.module.id in ids)AdoptionReason.AI_SELECTED else AdoptionReason.AI_NOT_SELECTED)
        })
        val selected=plan.selected.map {it.module.id}.toSet()
        return draft.copy(plan=plan,texts=draft.availableTexts.filter {it.moduleId in selected},imagesNotSent=draft.availableImages.filter {it.moduleId in selected})
    }
    /** 摘录只为选料定位，不替代选中模块的完整正文；不读取整块后再截断。 */
    fun selectionExcerpt(draft:RequestMaterialDraft,moduleId:String):String {
        val text=draft.availableTexts.firstOrNull {it.moduleId==moduleId}?:return ""
        return novex.storage.TextPages(store.contents).read(text.reference,0,600).text
    }

    /** 同一份不可变内容引用用于计量及后续请求读取；完整读取会校验存储字节。 */
    fun open(text: MaterialText): InputStream = store.contents.open(text.reference)
}
data class ManagedObject(val rootId:String,val targetId:String,val name:String,val kind:CardKind)
