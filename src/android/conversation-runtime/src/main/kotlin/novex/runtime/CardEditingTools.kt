package novex.runtime

import novex.content.ReadingLayout
import novex.content.ModuleLayout
import novex.model.PendingTool
import novex.model.ToolDefinition
import novex.storage.*
import org.json.JSONArray
import org.json.JSONObject

/** 编辑支线的协议适配；执行仍调用共同编辑器，不另存一套作品。 */
internal object CardEditingTools {
    private data class Spec(val description:String,val fields:Map<String,String>)
    private val specs=linkedMapOf(
        "place_internal_character" to Spec("调整世界内部角色的展示位置。character_id 是本世界角色编号，module_id 为目标模块，空字符串恢复独立角色列表。不会修改角色正文、图片、采用或管理权限；移动容纳它的模块会保留这一展示关系。",mapOf("character_id" to "string","module_id" to "string")),
        "write_module_markdown" to Spec("完整替换一个模块的 Markdown（标记文本）正文并保存；保留子模块、布局、编号和标签。只在掌握全部正文时使用，长文局部修改仍用 replace_text_range。已有图片用 ![说明](card-image:资源编号#图片块编号) 独占一行，资源必须属于本卡；新图片块编号使用新的随机稳定编号，旧图片保留原块编号。",mapOf("module_id" to "string","name" to "string","text" to "string")),
        "place_module" to Spec("移动整个模块子树。parent_id 是新父级，空字符串为卡片根部；before_id 必须是目标父级内的同级模块，空字符串为末尾。放入父级首位时使用它的首个子模块编号。保留全部后代、正文、资源和子项排列，不改变采用或权限。禁止放入自己或后代。",mapOf("module_id" to "string","parent_id" to "string","before_id" to "string")),
        "add_child_module" to Spec("在指定父级末尾创建真实子模块并保存，可在人工编辑页独立打开、移动和修改。比如基础档案分组下分别新建身份资料、外貌和穿着，不用正文标题冒充子模块。parent_id 空字符串为卡片根部；layout 是新模块对子项的排列：VERTICAL（竖向）或 HORIZONTAL（横向）。没有正文时不能宣称创作完成。",mapOf("name" to "string","parent_id" to "string","layout" to "string")),
        "set_module_layout" to Spec("设置模块子项排列：VERTICAL（竖向）或 HORIZONTAL（横向）。不改变父级、正文、子项顺序、采用和管理权限。",mapOf("module_id" to "string","layout" to "string")),
        "rename_module" to Spec("只修改模块名称并保存，允许无标题；不改模块正文。",mapOf("module_id" to "string","name" to "string")),
        "write_image_caption" to Spec("修改图片块说明并保存，不替换图片。模块名称保持不变。",mapOf("module_id" to "string","block_id" to "string","text" to "string")),
        "clear_card_image" to Spec("清除头像或封面用途，不删除素材。purpose 取 avatar（头像）或 cover（封面）。",mapOf("purpose" to "string")),
        "set_reading_layout" to Spec("设置整卡顶层模块排列：CONTINUOUS（连续）将顶层全部竖向展开；PAGED（翻页）保留横向分组。分组内部竖向排列应使用 set_module_layout，不要为此改整卡。预览与编辑遵循同一布局，不复制内容。",mapOf("layout" to "string")),
        "remove_card_resource" to Spec("移除当前卡的素材归属。remove_uses 为 true 时同时移除该图片所有展示和头像封面用途；false 遇仍在使用时拒绝。保留历史版本的原始文件。",mapOf("resource_id" to "string","remove_uses" to "boolean")),
        "create_internal_character" to Spec("在允许管理的世界内新建独立角色并保存。返回世界，重读结构取得新角色编号；新角色成为当前对话可管理的新成果。",mapOf("name" to "string")),
        "copy_internal_character" to Spec("从允许读取的独立角色复制至允许管理的世界，模块、图片和扩展完整复制；副本独立。source_id 为来源角色根编号。",mapOf("source_id" to "string")),
        "remove_internal_character" to Spec("从当前世界移除指定内部角色，历史内容保留；采用该角色的对话会明确报告来源不存在。character_id 是内部角色编号。",mapOf("character_id" to "string"))
    )
    val names:Set<String> get()=specs.keys
    fun definitions():List<ToolDefinition> = specs.map {(name,spec)->
        val fields=mapOf("root_id" to "string","target_id" to "string","draft_version" to "string")+spec.fields
        ToolDefinition(name,spec.description,JSONObject().put("type","object").put("properties",JSONObject().apply {fields.forEach {(key,type)->put(key,JSONObject().put("type",type))}})
            .put("required",JSONArray(fields.keys.toList())).put("additionalProperties",false).toString())
    }
    fun parse(namespace:String,call:PendingTool):CardToolRequest {
        val spec=specs.getValue(call.name);val value=JSONObject(call.arguments)
        val common=setOf("root_id","target_id","draft_version")
        require(value.keys().asSequence().toSet()==common+spec.fields.keys){"编辑字段不完整或包含未知字段"}
        (common+spec.fields.filterValues {it=="string"}.keys).forEach {require(value.get(it) is String)}
        common.forEach {require(value.getString(it).isNotBlank())}
        spec.fields.filterValues {it=="boolean"}.keys.forEach {require(value.get(it) is Boolean)}
        val command:EditorCommand=when(call.name) {
            "place_internal_character"->EditorCommand.PlaceCharacter(value.getString("character_id"),value.getString("module_id").takeIf {it.isNotBlank()})
            "write_module_markdown"->EditorCommand.WriteMarkdownText(value.getString("module_id"),value.getString("name"),value.getString("text"))
            "place_module"->EditorCommand.PlaceModule(value.getString("module_id"),value.getString("parent_id").takeIf {it.isNotBlank()},value.getString("before_id").takeIf {it.isNotBlank()})
            "add_child_module"->EditorCommand.AddModule(value.getString("name"),value.getString("parent_id").takeIf {it.isNotBlank()},ModuleLayout.valueOf(value.getString("layout")))
            "set_module_layout"->EditorCommand.ModuleLayoutChange(value.getString("module_id"),ModuleLayout.valueOf(value.getString("layout")))
            "rename_module"->EditorCommand.RenameModule(value.getString("module_id"),value.getString("name"))
            "write_image_caption"->EditorCommand.SetCaption(value.getString("module_id"),value.getString("block_id"),value.getString("text"))
            "clear_card_image"->{val purpose=value.getString("purpose");require(purpose in setOf("avatar","cover"));EditorCommand.Appearance(null,purpose=="cover")}
            "set_reading_layout"->EditorCommand.Layout(ReadingLayout.valueOf(value.getString("layout")))
            "remove_card_resource"->EditorCommand.RemoveResource(value.getString("resource_id"),value.getBoolean("remove_uses"))
            "create_internal_character"->EditorCommand.AddCharacter(value.getString("name"))
            "copy_internal_character"->EditorCommand.CopyCharacter(value.getString("source_id"))
            "remove_internal_character"->EditorCommand.RemoveCharacter(value.getString("character_id"))
            else->error("编辑操作未提供")
        }
        // 原始字段序列规范化后作为幂等记录，不让 JSON 对象键序影响重试。
        val canonical=JSONObject().put("operation",call.name).put("arguments",JSONObject().apply {value.keys().asSequence().toList().sorted().forEach {put(it,value.get(it))}}).toString()
        return CardToolRequest(namespace,call.id,ManagementTarget(value.getString("root_id"),value.getString("target_id")),value.getString("draft_version"),CardToolEdit.Shared(command,canonical))
    }
}
