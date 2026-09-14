package novex.content

/** 内容引用仅定位持久内容；不规定文件、数据库或包编码，不携带整块正文。 */
@JvmInline value class ContentRef(val value: String) {
    init { require(value.isNotBlank()) { "内容引用不能为空" } }
}

enum class CardKind { WORLD, CHARACTER }

sealed interface ContentBlock {
    val id: String
    data class Text(override val id: String, val content: ContentRef) : ContentBlock
    data class Image(override val id: String, val resourceId: String, val caption: ContentRef? = null) : ContentBlock
}

enum class ModuleLayout { VERTICAL, HORIZONTAL }

data class ContentModule(val id: String, val name: String, val blocks: List<ContentBlock>,
                         val tags: List<String> = emptyList(), val use: ModuleUse? = null,
                         val children: List<ContentModule> = emptyList(),
                         val layout: ModuleLayout = ModuleLayout.VERTICAL,
                         val characterIds: List<String> = emptyList())
data class CardResource(val id: String, val content: ContentRef, val mediaType: String)
enum class ReadingLayout { CONTINUOUS, PAGED }
data class CardAppearance(val avatarResourceId: String? = null, val coverResourceId: String? = null,val readingLayout:ReadingLayout=ReadingLayout.PAGED)

/**
 * 仅表达已确认的内容结构。没有对话、权限、模式或旧文游实体。
 * 未知扩展指向原始内容，不能在模型变换中静默丢弃。
 */
data class ContentDocument(
    val id: String,
    val kind: CardKind,
    val name: String,
    val modules: List<ContentModule> = emptyList(),
    val resources: List<CardResource> = emptyList(),
    val internalCharacters: List<ContentDocument> = emptyList(),
    val extensions: Map<String, ContentRef> = emptyMap(),
    val appearance: CardAppearance = CardAppearance(),
) {
    /** 不读取正文或资源字节；正文大小不属于结构校验门槛。 */
    fun validate(): ContentDocument {
        val seen = mutableSetOf<String>()
        fun identify(id: String) {
            require(id.isNotBlank()) { "对象编号不能为空" }
            require(seen.add(id)) { "同一内容树中编号重复：$id" }
        }
        fun visit(card: ContentDocument) {
            identify(card.id)
            require(card.kind == CardKind.WORLD || card.internalCharacters.isEmpty()) { "角色不能容纳内部角色" }
            val localResources = card.resources.map { it.id }.toSet()
            listOfNotNull(card.appearance.avatarResourceId, card.appearance.coverResourceId).forEach {
                require(it in localResources) { "头像和封面必须引用本卡资源" }
            }
            card.resources.forEach { resource ->
                identify(resource.id)
                require(resource.mediaType.isNotBlank()) { "资源类型不能为空" }
            }
            val localCharacters=card.internalCharacters.map {it.id}.toSet()
            val presentedCharacters=mutableSetOf<String>()
            card.modules.flattenModules().forEach { module ->
                require(module.characterIds.all {it in localCharacters && presentedCharacters.add(it)}) { "角色展示必须定位本世界的角色且不能重复放置" }
                identify(module.id)
                require(module.tags.all { it.isNotBlank() } && module.tags.distinct().size == module.tags.size) { "模块标签不能为空或重复" }
                module.blocks.forEach { block ->
                    identify(block.id)
                    if (block is ContentBlock.Image) {
                        require(block.resourceId in localResources) { "图片必须引用本卡资源：${block.id}" }
                    }
                }
            }
            card.internalCharacters.forEach {
                require(it.kind == CardKind.CHARACTER) { "世界内部只能包含角色" }
                visit(it)
            }
        }
        visit(this)
        return this
    }
}

/** 操作只产生候选内容，绝不把内存变换称为保存成功。 */
sealed interface ContentChange {
    data class AddModule(val module: ContentModule, val beforeId: String? = null, val parentId: String? = null) : ContentChange
    data class ReplaceModule(val module: ContentModule) : ContentChange
    data class RemoveModule(val moduleId: String) : ContentChange
    data class MoveModule(val moduleId: String, val beforeId: String?) : ContentChange
    /** null 父级表示卡片根部；定位编号必须属于该父级。 */
    data class PlaceModule(val moduleId: String, val parentId: String?, val beforeId: String?) : ContentChange
    data class SetModuleLayout(val moduleId: String, val layout: ModuleLayout) : ContentChange
    data class EditBlocks(val edits: List<BlockEdit>) : ContentChange
    data class PutResource(val resource: CardResource) : ContentChange
    data class RemoveResource(val resourceId: String) : ContentChange
    data class SetAppearance(val appearance: CardAppearance) : ContentChange
}

/** 人工入口和模型入口共用同一变换，不按编辑者复制业务规则。 */
object ContentChanges {
    fun apply(document: ContentDocument, change: ContentChange): ContentDocument {
        document.validate()
        when (change) {
            is ContentChange.EditBlocks -> return editBlocks(document, change.edits)
            is ContentChange.PutResource -> return document.copy(resources =
                document.resources.filterNot { it.id == change.resource.id } + change.resource).validate()
            is ContentChange.RemoveResource -> {
                require(document.resources.any { it.id == change.resourceId }) { "目标资源不存在" }
                return document.copy(resources = document.resources.filterNot { it.id == change.resourceId }).validate()
            }
            is ContentChange.SetAppearance -> return document.copy(appearance = change.appearance).validate()
            else -> Unit
        }
        val tree = document.modules
        val modules = when (change) {
            is ContentChange.AddModule -> tree.insertModule(change.parentId, change.module, change.beforeId)
            is ContentChange.ReplaceModule -> tree.updateModule(change.module.id) { change.module }
            is ContentChange.RemoveModule -> tree.removeModule(change.moduleId)
            is ContentChange.MoveModule -> tree.placeModule(change.moduleId, tree.parentOfModule(change.moduleId), change.beforeId)
            is ContentChange.PlaceModule -> tree.placeModule(change.moduleId, change.parentId, change.beforeId)
            is ContentChange.SetModuleLayout -> tree.updateModule(change.moduleId) { it.copy(layout = change.layout) }
            else -> error("内容操作未处理")
        }
        return document.copy(modules = modules).validate()
    }

    /** 世界中指定角色只修改该角色；最终仍检查整个内容树的编号及资源归属。 */
    fun applyTo(document: ContentDocument, targetId: String, change: ContentChange): ContentDocument {
        document.validate()
        if (document.id == targetId) return apply(document, change)
        require(document.internalCharacters.any { it.id == targetId }) { "目标卡片不在当前内容树中" }
        return document.copy(internalCharacters = document.internalCharacters.map {
            if (it.id == targetId) apply(it, change) else it
        }).validate()
    }
}
