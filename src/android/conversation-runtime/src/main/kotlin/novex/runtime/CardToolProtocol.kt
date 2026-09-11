package novex.runtime

import novex.model.PendingTool
import novex.model.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/** 模型参数只解析为候选操作；解析成功不等于批准或执行。 */
object CardToolProtocol {
    fun definitions(policy:CardToolPolicy):List<ToolDefinition> {
        if(policy.permission==ToolPermission.READ_ONLY)return emptyList()
        fun tool(name:String,description:String,extra:Map<String,String>):ToolDefinition {
            val properties=JSONObject()
            (mapOf("root_id" to "根作品编号","target_id" to "实际目标卡编号","draft_version" to "读取时的草稿版本")+extra).forEach { (key,help) ->
                properties.put(key,JSONObject().put("type",if(key in setOf("start","end"))"integer" else "string").put("description",help))
            }
            if("tags" in extra)properties.put("tags",JSONObject().put("type","array").put("items",JSONObject().put("type","string")))
            if("rule" in extra)properties.put("rule",JSONObject().put("type","object").put("description","kind 为 automatic（自动选择）、always（始终）、manual（手动）、keywords（条件）。条件还需 words 字符串数组、case_sensitive 和 require_all 布尔值；其他规则只传 kind。"))
            val schema=JSONObject().put("type","object").put("properties",properties)
                .put("required",JSONArray(properties.keys().asSequence().toSet().sorted())).put("additionalProperties",false)
            return ToolDefinition(name,description,schema.toString())
        }
        fun readTool(name:String,description:String,extra:Map<String,String>):ToolDefinition {
            val properties=JSONObject()
            (mapOf("root_id" to "根作品编号","target_id" to "目标卡编号")+extra).forEach { (key,help)->
                properties.put(key,JSONObject().put("type",if(key in setOf("offset","count"))"integer" else "string").put("description",help))
            }
            return ToolDefinition(name,description,JSONObject().put("type","object").put("properties",properties)
                .put("required",JSONArray(properties.keys().asSequence().toSet().sorted())).put("additionalProperties",false).toString())
        }
        return CardEditingTools.definitions()+listOf(
            ToolDefinition("create_card","新建一张空世界或角色并保存。只接受 kind（WORLD 世界／CHARACTER 角色）和 name（名称）。新建不代表正文完成；成功后可管理该新作品，不改变当前互动来源。",JSONObject().put("type","object").put("properties",JSONObject().put("kind",JSONObject().put("type","string").put("enum",JSONArray(listOf("WORLD","CHARACTER")))).put("name",JSONObject().put("type","string"))).put("required",JSONArray(listOf("kind","name"))).put("additionalProperties",false).toString()),
            readTool("read_card_image","明确读取目标卡图片并放入下一次模型请求。先读取结构取得资源和版本；目录或图注不代表图像内容。",mapOf("draft_version" to "最新版本","resource_id" to "本卡图片资源编号")),
            tool("save_conversation_image","将当前对话持有图片复制到允许管理的卡片并保存。image_id 从对话图片目录取得；module_id 为空则仅存入素材，after_id 为空则放模块末尾。不依赖原附件继续存在。",mapOf("image_id" to "对话图片编号","module_id" to "模块编号或空字符串","after_id" to "定位块编号或空字符串")),
            tool("remove_module","移除指定模块及其全部子模块、内容块并保存；保留卡片图片资源及历史版本，不删除其他模块。先读取最新结构，明确目标后操作。",mapOf("module_id" to "要移除的模块编号")),
            tool("remove_content_block","移除模块内一个文字或图片展示块并保存。图片资源、封面与头像保留；不删除整模块。",mapOf("module_id" to "所属模块编号","block_id" to "要移除的块编号")),
            tool("set_module_options","修改模块标签和默认携带规则并保存，不改正文。本对话的显式停用仍优先；标签不是权限。",mapOf("module_id" to "目标模块编号","tags" to "完整的新标签列表","rule" to "新的默认携带规则")),
            readTool("read_card","读取目标卡最新模块结构、编辑版本和修改来源，不读取全部正文，也不新建草稿。修改前先读取。",emptyMap()),
            readTool("read_text_block","读取一个文字块或图片图注的一页。位置按万国码码点计算；has_more 为真时还未读完，继续使用 next_offset。图片本体未发送。",mapOf("draft_version" to "结构返回的编辑版本原样传入","module_id" to "模块编号","block_id" to "内容块编号","offset" to "首段从零开始，后续用返回位置","count" to "本页字符数，不超过结构返回的 page_limit")),
            tool("set_card_image","将目标卡已持有的图片设为封面或头像。先读取最新结构与图片资源目录；不会上传、生成或删除图片。资源目录不意味着已经看过图像。",mapOf("resource_id" to "当前目标卡图片资源编号","purpose" to "cover 表示封面，avatar 表示头像")),
            tool("insert_owned_image","把本卡已有图片插入目标模块，生成新的图片展示块，复用资源不复制或删除图片文件。先读取最新结构与自有图片目录；资源目录不代表已看图。保存后重读结构取得新块编号。",mapOf("module_id" to "目标模块编号","resource_id" to "当前目标卡自有图片资源编号","after_id" to "同模块定位块编号，在它之后插入；空字符串表示模块末尾")),
            tool("replace_block_image","将现有图片块改用本卡另一张已有图片，保留块编号、位置、图注和旧资源。不修改封面或头像，先读取最新结构和资源目录。",mapOf("module_id" to "模块编号","block_id" to "已有图片块编号","resource_id" to "当前目标卡要使用的图片资源编号")),
            tool("move_module","将目标卡中的一个模块移到另一个模块之前，或移到末尾。保留模块内容、编号、标签与携带规则；先读取最新结构，保存后再次读取版本再进行下一次修改。",mapOf("module_id" to "要移动的模块编号","before_id" to "同父级定位模块编号，空字符串表示同级末尾；改变父级用 place_module")),
            tool("move_content_block","在同一模块内调整文字块或图片块顺序，不重写正文或图片。图片块的结构类型为 image_caption，但移动的是整块图片展示，不仅是图注。先读取最新结构；保存后重新读取版本。",mapOf("module_id" to "所属模块编号","block_id" to "要移动的文字或图片块编号","before_id" to "同模块定位块编号，空字符串表示末尾")),
            tool("rename_card","修改管理范围内目标卡的名称。只有返回 saved 才已正式保存；批准模式可能返回待确认。",mapOf("name" to "新名称")),
            tool("add_module","在目标卡根部新增空模块并保存。创建分组内部的可编辑条目请用 add_child_module 并指定父模块，不能把所有条目只写成分组正文中的标题。空模块尚无正文，不代表内容创作完成。",mapOf("name" to "模块名称")),
            tool("write_module_text","新建或完整替换一个文字块并保存。新建时 block_id 必须传空字符串，不要省略或猜测 new/create 等编号；该操作不创建子模块。不要把读到的一页当成全文覆盖；局部修改使用 replace_text_range。先读取最新版本；失败不得宣称完成。",mapOf("module_id" to "目标模块编号","block_id" to "已有文字块编号；新建文字块时为空字符串","name" to "模块名称","text" to "完整的新文字块正文")),
            tool("replace_text_range","局部替换已有文字块并保存，保留范围外的正文。先读取目标文字页，原样使用其编辑版本及 content_ref。范围按万国码码点计算，左闭右开；整页替换使用 offset 和 end_offset，插入时 start 等于 end，删除时 text 为空。图片说明不适用。",mapOf("module_id" to "模块编号","block_id" to "已有文字块编号","content_ref" to "读取文字页返回的正文引用，原样传入","start" to "替换起点，包含此位置","end" to "替换终点，不包含此位置","text" to "该范围的新文字，不包含保留的前后文"))
        )
    }
    fun parse(chatId:String,call:PendingTool):CardToolRequest {
        if(call.name=="create_card") {
            val value=JSONObject(call.arguments);require(value.keys().asSequence().toSet()==setOf("kind","name"))
            require(value.get("kind") is String && value.get("name") is String && value.getString("name").isNotBlank())
            val id=java.util.UUID.nameUUIDFromBytes((chatId.length.toString()+":"+chatId+call.id).toByteArray(Charsets.UTF_8)).toString()
            return CardToolRequest(chatId,call.id,ManagementTarget(id),"new",CardToolEdit.Create(novex.content.CardKind.valueOf(value.getString("kind")),value.getString("name")))
        }
        if(call.name in CardEditingTools.names)return CardEditingTools.parse(chatId,call)
        val value=JSONObject(call.arguments)
        val common=setOf("root_id","target_id","draft_version")
        val extra=when(call.name){"save_conversation_image"->setOf("image_id","module_id","after_id");"remove_module"->setOf("module_id");"remove_content_block"->setOf("module_id","block_id");"set_module_options"->setOf("module_id","tags","rule");"rename_card","add_module"->setOf("name");"set_card_image"->setOf("resource_id","purpose");"insert_owned_image"->setOf("module_id","resource_id","after_id");"replace_block_image"->setOf("module_id","block_id","resource_id");"move_module"->setOf("module_id","before_id");"move_content_block"->setOf("module_id","block_id","before_id");"write_module_text"->setOf("module_id","block_id","name","text");"replace_text_range"->setOf("module_id","block_id","content_ref","start","end","text");else->error("工具未提供")}
        require(value.keys().asSequence().toSet()==common+extra){"工具字段缺失或包含未提供参数"}
        (common+extra-setOf("start","end","tags","rule")).forEach { require(value.get(it) is String){"工具字段必须为文字"} }
        common.forEach { require(value.getString(it).isNotBlank()){ "目标和版本不能为空" } }
        if("module_id" in extra && call.name!="save_conversation_image")require(value.getString("module_id").isNotBlank())
        val edit=when(call.name) {
            "save_conversation_image"->{require(value.getString("image_id").isNotBlank());val module=value.getString("module_id").takeIf {it.isNotBlank()};val after=value.getString("after_id").takeIf {it.isNotBlank()};require(module!=null || after==null);CardToolEdit.ConversationImage(value.getString("image_id"),module,after)}
            "remove_module"->CardToolEdit.RemoveModule(value.getString("module_id"))
            "remove_content_block"->{require(value.getString("block_id").isNotBlank());CardToolEdit.RemoveBlock(value.getString("module_id"),value.getString("block_id"))}
            "set_module_options"->CardToolEdit.Options(value.getString("module_id"),ModuleOptionsProtocol.strings(value.getJSONArray("tags")),ModuleOptionsProtocol.decode(value.getJSONObject("rule")))
            "set_card_image"->{
                require(value.getString("resource_id").isNotBlank())
                require(value.getString("purpose") in setOf("cover","avatar")){"图片用途无效"}
                CardToolEdit.Appearance(value.getString("resource_id"),value.getString("purpose")=="cover")
            }
            "insert_owned_image"->{
                listOf("module_id","resource_id").forEach {require(value.getString(it).isNotBlank())}
                CardToolEdit.InsertOwnedImage(value.getString("module_id"),value.getString("resource_id"),value.getString("after_id").takeIf {it.isNotEmpty()})
            }
            "replace_block_image"->{
                listOf("module_id","block_id","resource_id").forEach {require(value.getString(it).isNotBlank())}
                CardToolEdit.ReplaceBlockImage(value.getString("module_id"),value.getString("block_id"),value.getString("resource_id"))
            }
            "move_module"->{
                require(value.getString("module_id").isNotBlank())
                CardToolEdit.MoveModule(value.getString("module_id"),value.getString("before_id").takeIf {it.isNotEmpty()})
            }
            "move_content_block"->{
                require(value.getString("module_id").isNotBlank() && value.getString("block_id").isNotBlank())
                CardToolEdit.MoveBlock(value.getString("module_id"),value.getString("block_id"),value.getString("before_id").takeIf {it.isNotEmpty()})
            }
            "rename_card"->CardToolEdit.Rename(value.getString("name"))
            "add_module"->CardToolEdit.AddModule(value.getString("name"))
            "replace_text_range"->{
                listOf("module_id","block_id","content_ref").forEach {require(value.getString(it).isNotBlank()){"文字目标不能为空"}}
                listOf("start","end").forEach {require(value.get(it) is Int || value.get(it) is Long){"范围位置必须为整数"}}
                val start=value.getLong("start");val end=value.getLong("end")
                require(start>=0 && end>=start){"文字修改范围无效"}
                CardToolEdit.ReplaceTextRange(value.getString("module_id"),value.getString("block_id"),novex.content.ContentRef(value.getString("content_ref")),start,end,value.getString("text"))
            }
            else->{require(value.getString("module_id").isNotBlank());CardToolEdit.WriteText(value.getString("module_id"),value.getString("block_id").takeIf { it.isNotBlank() },value.getString("name"),value.getString("text"))}
        }
        return CardToolRequest(chatId,call.id,ManagementTarget(value.getString("root_id"),value.getString("target_id")),value.getString("draft_version"),edit)
    }
    fun result(result:CardToolResult):String=when(result) {
        is CardToolResult.Read->JSONObject(result.content)
        is CardToolResult.CheckpointSaved->JSONObject().put("status","checkpoint_saved").put("conversation_id",result.conversationId).put("checkpoint_id",result.checkpointId)
        is CardToolResult.StateSaved->JSONObject().put("status","state_saved").put("conversation_id",result.conversationId).put("event_id",result.eventId)
        is CardToolResult.Registered->JSONObject().put("status","registered").put("conversation_id",result.conversationId).put("registration_id",result.registrationId)
        is CardToolResult.Saved->JSONObject().put("status","saved").put("root_id",result.rootId).put("target_id",result.targetId).put("revision",result.revision)
        is CardToolResult.Failed->JSONObject().put("status","failed").put("reason",result.reason).put("preserved_draft",result.preservedDraft?:JSONObject.NULL)
        is CardToolResult.Stopped->JSONObject().put("status","stopped").put("preserved_draft",result.preservedDraft?:JSONObject.NULL)
        CardToolResult.Denied->JSONObject().put("status","denied")
        CardToolResult.AwaitingApproval->JSONObject().put("status","awaiting_approval")
        CardToolResult.Unconfirmed->JSONObject().put("status","unconfirmed")
    }.toString()
}
