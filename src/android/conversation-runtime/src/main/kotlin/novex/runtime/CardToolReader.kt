package novex.runtime

import novex.content.*
import novex.storage.*
import novex.model.PendingTool
import org.json.JSONArray
import org.json.JSONObject

/** 模型明确读取管理资料；不把管理范围全文自动塞入背景，不创建编辑草稿。 */
class CardToolReader(private val store:CardStore,private val maximumPage:Int=8192) {
    init {require(maximumPage>0)}
    private data class Snapshot(val target:ContentDocument,val version:String,val source:ChangeSource,val hasDraft:Boolean)
    private fun snapshot(target:ManagementTarget):Snapshot {
        val draft=CardDrafts(store).read(target.rootId)
        if(draft!=null)return Snapshot(ContentTargets.find(draft.content,target.targetId),draft.version,draft.source,true)
        val saved=requireNotNull(store.open(target.rootId)){"管理对象不存在"}
        return Snapshot(ContentTargets.find(saved.content,target.targetId),"saved:${saved.revision}",saved.source,false)
    }
    fun read(call:PendingTool,policy:CardToolPolicy):JSONObject {
        require(policy.permission!=ToolPermission.READ_ONLY){"只读状态未提供工具接口"}
        val args=JSONObject(call.arguments)
        val base=setOf("root_id","target_id")
        val extra=when(call.name){"read_card"->emptySet();"read_card_image"->setOf("draft_version","resource_id");"read_text_block"->setOf("draft_version","module_id","block_id","offset","count");else->error("未提供的读取工具")}
        require(args.keys().asSequence().toSet()==base+extra){"读取字段缺失或包含未知字段"}
        (base+extra-setOf("offset","count")).forEach { require(args.get(it) is String) }
        val target=ManagementTarget(args.getString("root_id"),args.getString("target_id"));require(target in policy.targets){"对象不在当前管理范围"}
        val source=snapshot(target)
        val result=JSONObject().put("status","read").put("root_id",target.rootId).put("target_id",target.targetId)
            .put("page_limit",maximumPage).put("draft_version",source.version).put("has_draft",source.hasDraft).put("root_change_source",source.source.name).put("image_sent",false)
        if(call.name=="read_card")return result.put("name",source.target.name).put("kind",source.target.kind.name)
            .put("appearance",JSONObject().put("cover_resource_id",source.target.appearance.coverResourceId?:JSONObject.NULL).put("avatar_resource_id",source.target.appearance.avatarResourceId?:JSONObject.NULL))
            .put("reading_layout",source.target.appearance.readingLayout.name)
            .put("characters",JSONArray(source.target.internalCharacters.map {JSONObject().put("id",it.id).put("name",it.name)}))
            .put("resources",JSONArray(source.target.resources.filter {it.mediaType.startsWith("image/")}.map {JSONObject().put("id",it.id).put("media_type",it.mediaType)}))
            .put("modules",JSONArray(source.target.modules.flattenModules().map { module -> JSONObject().put("id",module.id).put("name",module.name).put("parent_id",source.target.modules.parentOfModule(module.id)?:JSONObject.NULL).put("children",JSONArray(module.children.map {it.id})).put("layout",module.layout.name).put("character_ids",JSONArray(module.characterIds)).put("tags",JSONArray(module.tags)).put("rule",ModuleOptionsProtocol.encode(module.use))
                .put("blocks",JSONArray(module.blocks.map { block->JSONObject().put("id",block.id).put("kind",if(block is ContentBlock.Text)"text" else "image_caption").apply {if(block is ContentBlock.Image)put("resource_id",block.resourceId)} })) }))
        if(call.name=="read_card_image") {
            require(args.getString("draft_version")==source.version){"图片版本已变化，请重新读取"}
            require(source.target.resources.any {it.id==args.getString("resource_id") && it.mediaType.startsWith("image/")}){"图片不属于目标卡"}
            return result.put("image_attached_for_next_request",true).put("resource_id",args.getString("resource_id"))
        }
        require(args.getString("draft_version")==source.version){"内容版本已变化，请重新读取结构"}
        val countValue=args.get("count");val offsetValue=args.get("offset")
        require(countValue is Int && offsetValue is Number && (offsetValue is Long || offsetValue is Int)){"分页参数必须为整数"}
        val count=args.getInt("count");val offset=args.getLong("offset")
        require(count in 1..maximumPage){"单次读取最多 $maximumPage 个字符，可继续读取下一页；卡片未被截断"}
        val module=requireNotNull(source.target.modules.flattenModules().find { it.id==args.getString("module_id") }){"目标模块不存在"}
        val block=requireNotNull(module.blocks.find { it.id==args.getString("block_id") }){"目标内容块不存在"}
        val ref=when(block){is ContentBlock.Text->block.content;is ContentBlock.Image->block.caption}
        val page=if(ref==null){require(offset==0L);TextPage("",0,null,true)} else TextPages(store.contents).read(ref,offset,count)
        return result.put("content_ref",ref?.value?:JSONObject.NULL).put("end_offset",page.start+page.text.codePointCount(0,page.text.length)).put("text",page.text).put("offset",page.start).put("next_offset",page.next?:JSONObject.NULL).put("has_more",page.next!=null)
            .put("entire_file_read",page.entireFileRead).put("image_sent",false)
    }
    fun image(call:PendingTool,policy:CardToolPolicy):novex.model.WireImage {
        read(call,policy)
        val args=JSONObject(call.arguments)
        val source=snapshot(ManagementTarget(args.getString("root_id"),args.getString("target_id")))
        require(source.version==args.getString("draft_version")){"图片版本已变化"}
        val resource=source.target.resources.single {it.id==args.getString("resource_id")}
        return novex.model.WireImage(resource.mediaType,store.contents.open(resource.content).use {java.util.Base64.getEncoder().encodeToString(it.readBytes())})
    }

}
