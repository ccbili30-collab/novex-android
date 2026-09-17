package novex.runtime

import novex.model.PendingTool
import novex.model.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/** 模型参数只解析为候选操作；解析成功不等于批准或执行。 */
/** 模型给新建块惯用的伪编号——一律按"新建"处理而不是报错（对话包实测高频翻车点）。 */
private val NEW_BLOCK_HINTS=setOf("new","create","new_block","newblock","new text block","auto","null","none","todo")

object CardToolProtocol {
    fun definitions(policy:CardToolPolicy):List<ToolDefinition> {
        if(policy.permission==ToolPermission.READ_ONLY)return emptyList()
        fun tool(name:String,description:String,extra:Map<String,String>,optional:Set<String> = emptySet()):ToolDefinition {
            val properties=JSONObject()
            (mapOf("root_id" to "根作品编号","target_id" to "实际目标卡编号","draft_version" to "读取时的草稿版本")+extra).forEach { (key,help) ->
                properties.put(key,JSONObject().put("type",if(key in setOf("start","end"))"integer" else "string").put("description",help))
            }
            if("tags" in extra)properties.put("tags",JSONObject().put("type","array").put("items",JSONObject().put("type","string")))
            if("rule" in extra)properties.put("rule",JSONObject().put("type","object").put("description","kind 为 automatic（自动选择）、always（始终）、manual（手动）、keywords（条件）。条件还需 words 字符串数组、case_sensitive 和 require_all 布尔值；其他规则只传 kind。"))
            // [T-schema-optional-fields] 2026-09-17 会话 b314941f：schema 把
            // "空字符串=合法语义"的定位字段（block_id/before_id/after_id 等）
            // 一律标 required，外层发送前预检据此把模型按文档传的 "" 当"参数
            // 缺失"拒发——write_module_text 新建块三次全被拒、模型弃用换路
            //（46 分钟建一张 6KB 卡的直接原因之一）。required 必须与解析层
            // defaulted 集合及字段说明同契约：optional 字段缺省/空串都合法，
            // 由解析层给默认值。
            val schema=JSONObject().put("type","object").put("properties",properties)
                .put("required",JSONArray((properties.keys().asSequence().toSet()-optional).sorted())).put("additionalProperties",false)
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
            ToolDefinition("create_card_bulk","一次成型整张卡（宏通道，建卡首选）：kind + name + modules 嵌套模块树。每个节点 name 必填；text 是该模块的正文（自动成为一个文字块）；children 为嵌套子模块（最多四层）；数组顺序即排序。先整包校验再一次性写入：任何节点不合法都不会写入半张卡，错误信息带 JSON 路径，改一处重发即可。成功返回全部新模块/块编号（created_module_ids/created_block_ids）与 next_draft_version，后续微调用精确工具直接引用。建整卡或大批量内容必须用它，不要逐个 add_module。",
                JSONObject().put("type","object")
                    .put("properties",JSONObject()
                        .put("kind",JSONObject().put("type","string").put("enum",JSONArray(listOf("WORLD","CHARACTER"))))
                        .put("name",JSONObject().put("type","string").put("description","卡片名称"))
                        .put("modules",JSONObject().put("type","array").put("description","模块树，数组顺序即排序").put("items",CardBulk.moduleSchema())))
                    .put("required",JSONArray(listOf("kind","name","modules"))).put("additionalProperties",false).toString()),
            ToolDefinition("add_module_bulk","一次向已有卡插入整棵模块子树（宏通道）：module 为与 create_card_bulk 相同的节点结构（name/text/children）；before_id 为空插到根模块末尾，否则插到该根级模块之前。整包校验失败不写入。成功返回新模块/块编号。批量补充内容必须用它，不要逐个 add_module。",
                JSONObject().put("type","object")
                    .put("properties",JSONObject()
                        .put("root_id",JSONObject().put("type","string").put("description","根作品编号"))
                        .put("target_id",JSONObject().put("type","string").put("description","实际目标卡编号"))
                        .put("draft_version",JSONObject().put("type","string").put("description","读取时的草稿版本，或上次保存返回的 next_draft_version"))
                        .put("before_id",JSONObject().put("type","string").put("description","定位根级模块编号，插到它之前；空字符串表示末尾"))
                        .put("module",CardBulk.moduleSchema()))
                    .put("required",JSONArray(listOf("root_id","target_id","draft_version","module"))).put("additionalProperties",false).toString()),
            readTool("read_card_image","明确读取目标卡图片并放入下一次模型请求。先读取结构取得资源和版本；目录或图注不代表图像内容。",mapOf("draft_version" to "最新版本","resource_id" to "本卡图片资源编号")),
            tool("save_conversation_image","将当前对话持有图片复制到允许管理的卡片并保存。image_id 从对话图片目录取得；module_id 为空则仅存入素材，after_id 为空则放模块末尾。不依赖原附件继续存在。",mapOf("image_id" to "对话图片编号","module_id" to "模块编号或空字符串","after_id" to "定位块编号或空字符串"),optional=setOf("module_id","after_id")),
            tool("remove_module","移除指定模块及其全部子模块、内容块并保存；保留卡片图片资源及历史版本，不删除其他模块。先读取最新结构，明确目标后操作。",mapOf("module_id" to "要移除的模块编号")),
            tool("remove_content_block","移除模块内一个文字或图片展示块并保存。图片资源、封面与头像保留；不删除整模块。",mapOf("module_id" to "所属模块编号","block_id" to "要移除的块编号")),
            tool("set_module_options","修改模块标签和默认携带规则并保存，不改正文。本对话的显式停用仍优先；标签不是权限。",mapOf("module_id" to "目标模块编号","tags" to "完整的新标签列表","rule" to "新的默认携带规则")),
            readTool("read_card","读取目标卡最新模块结构、编辑版本和修改来源，不读取全部正文，也不新建草稿。修改前先读取。",emptyMap()),
            readTool("read_text_block","读取一个文字块或图片图注的一页。位置按万国码码点计算；has_more 为真时还未读完，继续使用 next_offset。图片本体未发送。",mapOf("draft_version" to "结构返回的编辑版本原样传入","module_id" to "模块编号","block_id" to "内容块编号","offset" to "首段从零开始，后续用返回位置","count" to "本页字符数，不超过结构返回的 page_limit")),
            tool("set_card_image","将目标卡已持有的图片设为封面或头像。先读取最新结构与图片资源目录；不会上传、生成或删除图片。资源目录不意味着已经看过图像。",mapOf("resource_id" to "当前目标卡图片资源编号","purpose" to "cover 表示封面，avatar 表示头像")),
            tool("insert_owned_image","把本卡已有图片插入目标模块，生成新的图片展示块，复用资源不复制或删除图片文件。先读取最新结构与自有图片目录；资源目录不代表已看图。保存后重读结构取得新块编号。",mapOf("module_id" to "目标模块编号","resource_id" to "当前目标卡自有图片资源编号","after_id" to "同模块定位块编号，在它之后插入；空字符串表示模块末尾"),optional=setOf("after_id")),
            tool("replace_block_image","将现有图片块改用本卡另一张已有图片，保留块编号、位置、图注和旧资源。不修改封面或头像，先读取最新结构和资源目录。",mapOf("module_id" to "模块编号","block_id" to "已有图片块编号","resource_id" to "当前目标卡要使用的图片资源编号")),
            tool("move_module","将目标卡中的一个模块移到另一个模块之前，或移到末尾。保留模块内容、编号、标签与携带规则；先读取最新结构；保存结果的 next_draft_version 可直接用于下一次修改。",mapOf("module_id" to "要移动的模块编号","before_id" to "同父级定位模块编号，空字符串表示同级末尾；改变父级用 place_module"),optional=setOf("before_id")),
            tool("move_content_block","在同一模块内调整文字块或图片块顺序，不重写正文或图片。图片块的结构类型为 image_caption，但移动的是整块图片展示，不仅是图注。先读取最新结构；保存结果的 next_draft_version 可直接用于下一次操作。",mapOf("module_id" to "所属模块编号","block_id" to "要移动的文字或图片块编号","before_id" to "同模块定位块编号，空字符串表示末尾"),optional=setOf("before_id")),
            tool("rename_card","修改管理范围内目标卡的名称。只有返回 saved 才已正式保存；批准模式可能返回待确认。",mapOf("name" to "新名称")),
            tool("add_module","在目标卡根部新增空模块并保存。创建分组内部的可编辑条目请用 add_child_module 并指定父模块，不能把所有条目只写成分组正文中的标题。保存结果返回新模块编号 created_module_ids 和 next_draft_version，直接引用继续，无需重读结构。空模块尚无正文，不代表内容创作完成。",mapOf("name" to "模块名称")),
            tool("write_module_text","新建或完整替换一个文字块并保存。新建时 block_id 传空字符串（缺省或误填 new 等占位词也按新建处理）；该操作不创建子模块。保存结果返回新建块编号 created_block_ids 和 next_draft_version——下一步直接引用它们继续操作，无需重读结构。不要把读到的一页当成全文覆盖；局部修改使用 replace_text_range。失败不得宣称完成。",mapOf("module_id" to "目标模块编号","block_id" to "已有文字块编号；新建文字块时为空字符串或省略","name" to "模块名称","text" to "完整的新文字块正文"),optional=setOf("block_id")),
            tool("replace_text_range","局部替换已有文字块并保存，保留范围外的正文。先读取目标文字页，原样使用其编辑版本及 content_ref。范围按万国码码点计算，左闭右开；整页替换使用 offset 和 end_offset，插入时 start 等于 end，删除时 text 为空。图片说明不适用。",mapOf("module_id" to "模块编号","block_id" to "已有文字块编号","content_ref" to "读取文字页返回的正文引用，原样传入","start" to "替换起点，包含此位置","end" to "替换终点，不包含此位置","text" to "该范围的新文字，不包含保留的前后文"))
        )
    }
    fun parse(chatId:String,call:PendingTool):CardToolRequest {
        if(call.name=="create_card_bulk") {
            val value=JSONObject(call.arguments)
            val kind=value.optString("kind").trim().uppercase()
            val name=value.optString("name").trim()
            require(kind=="WORLD"||kind=="CHARACTER"){"kind 必须是 WORLD（世界）或 CHARACTER（角色）"}
            require(name.isNotBlank()){"name 不能为空"}
            val tree=CardBulk.parseTree(value,"modules")
            val id=java.util.UUID.nameUUIDFromBytes((chatId.length.toString()+":"+chatId+call.id).toByteArray(Charsets.UTF_8)).toString()
            return CardToolRequest(chatId,call.id,ManagementTarget(id),"new",CardToolEdit.CreateBulk(novex.content.CardKind.valueOf(kind),name,tree))
        }
        if(call.name=="add_module_bulk") {
            val value=JSONObject(call.arguments)
            listOf("root_id","target_id","draft_version").forEach { require(value.optString(it).isNotBlank()){"$it 不能为空"} }
            // [T-bulk-string-tolerance] 会话 b314941f：模型/中转常把对象整体
            // 编码成字符串（"module":"{...}"），optJSONObject 直接判缺失、模
            // 型只能猜格式重试一轮。容忍字符串编码 + 报错给出正确形状示例，
            // 把"猜一轮"变"照抄即对"。
            val module=value.optJSONObject("module") ?: (value.opt("module") as? String)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()
            } ?: throw IllegalArgumentException("module 缺失或不是 JSON 对象（不要整体加引号）。正确形状：\"module\":{\"name\":\"模块名\",\"text\":\"正文\",\"children\":[]}")
            val wrapper=JSONObject().put("modules",java.util.Collections.singletonList(module).let{JSONArray(it)})
            val tree=CardBulk.parseTree(wrapper,"modules")
            return CardToolRequest(chatId,call.id,ManagementTarget(value.getString("root_id"),value.getString("target_id")),value.getString("draft_version"),
                CardToolEdit.AddModuleBulk(value.optString("before_id").takeIf{it.isNotBlank()},tree))
        }
        if(call.name=="create_card") {
            // [T-lenient-parse] 2026-09-16 工具协议批：多余键忽略（此前模型顺手
            // 加说明字段就被拒）；kind 大小写归一；缺必需键才报错。
            val value=JSONObject(call.arguments)
            val kind=value.optString("kind").trim().uppercase()
            val name=value.optString("name").trim()
            require(kind=="WORLD"||kind=="CHARACTER"){"kind 必须是 WORLD（世界）或 CHARACTER（角色）"}
            require(name.isNotBlank()){"name 不能为空"}
            val id=java.util.UUID.nameUUIDFromBytes((chatId.length.toString()+":"+chatId+call.id).toByteArray(Charsets.UTF_8)).toString()
            return CardToolRequest(chatId,call.id,ManagementTarget(id),"new",CardToolEdit.Create(novex.content.CardKind.valueOf(kind),name))
        }
        if(call.name in CardEditingTools.names)return CardEditingTools.parse(chatId,call)
        val value=JSONObject(call.arguments)
        val common=setOf("root_id","target_id","draft_version")
        val extra=when(call.name){"save_conversation_image"->setOf("image_id","module_id","after_id");"remove_module"->setOf("module_id");"remove_content_block"->setOf("module_id","block_id");"set_module_options"->setOf("module_id","tags","rule");"rename_card","add_module"->setOf("name");"set_card_image"->setOf("resource_id","purpose");"insert_owned_image"->setOf("module_id","resource_id","after_id");"replace_block_image"->setOf("module_id","block_id","resource_id");"move_module"->setOf("module_id","before_id");"move_content_block"->setOf("module_id","block_id","before_id");"write_module_text"->setOf("module_id","block_id","name","text");"replace_text_range"->setOf("module_id","block_id","content_ref","start","end","text");else->error("工具未提供")}
        // [T-lenient-parse] 键校验放宽：缺必需键报错并点名缺失项；多余键忽略；
        // 定位类可选键（block_id/before_id/after_id）缺省视为空串（新建/末尾）。
        val defaulted=setOf("block_id","before_id","after_id")
        val missing=(common+extra-defaulted)-value.keys().asSequence().toSet()
        require(missing.isEmpty()){"工具字段缺失：${missing.joinToString(", ")}"}
        (common+extra-setOf("start","end","tags","rule")-defaulted).forEach { require(value.get(it) is String){"工具字段必须为文字"} }
        common.forEach { require(value.getString(it).isNotBlank()){ "目标和版本不能为空" } }
        if("module_id" in extra && call.name!="save_conversation_image")require(value.getString("module_id").isNotBlank())
        val edit=when(call.name) {
            "save_conversation_image"->{require(value.getString("image_id").isNotBlank());val module=value.optString("module_id").takeIf {it.isNotBlank()};val after=value.optString("after_id").takeIf {it.isNotBlank()};require(module!=null || after==null);CardToolEdit.ConversationImage(value.getString("image_id"),module,after)}
            "remove_module"->CardToolEdit.RemoveModule(value.getString("module_id"))
            "remove_content_block"->{require(value.getString("block_id").isNotBlank());CardToolEdit.RemoveBlock(value.getString("module_id"),value.getString("block_id"))}
            "set_module_options"->{val tags=runCatching{ModuleOptionsProtocol.strings(value.getJSONArray("tags"))}.getOrElse{emptyList()};CardToolEdit.Options(value.getString("module_id"),tags,ModuleOptionsProtocol.decodeLenient(value.opt("rule")))}
            "set_card_image"->{
                require(value.getString("resource_id").isNotBlank())
                require(value.getString("purpose") in setOf("cover","avatar")){"图片用途无效"}
                CardToolEdit.Appearance(value.getString("resource_id"),value.getString("purpose")=="cover")
            }
            "insert_owned_image"->{
                listOf("module_id","resource_id").forEach {require(value.getString(it).isNotBlank())}
                CardToolEdit.InsertOwnedImage(value.getString("module_id"),value.getString("resource_id"),value.optString("after_id").takeIf {it.isNotEmpty()})
            }
            "replace_block_image"->{
                listOf("module_id","block_id","resource_id").forEach {require(value.getString(it).isNotBlank())}
                CardToolEdit.ReplaceBlockImage(value.getString("module_id"),value.getString("block_id"),value.getString("resource_id"))
            }
            "move_module"->{
                require(value.getString("module_id").isNotBlank())
                CardToolEdit.MoveModule(value.getString("module_id"),value.optString("before_id").takeIf {it.isNotEmpty()})
            }
            "move_content_block"->{
                require(value.getString("module_id").isNotBlank() && value.getString("block_id").isNotBlank())
                CardToolEdit.MoveBlock(value.getString("module_id"),value.optString("block_id"),value.optString("before_id").takeIf {it.isNotEmpty()})
            }
            "rename_card"->CardToolEdit.Rename(value.getString("name"))
            "add_module"->CardToolEdit.AddModule(value.getString("name"))
            "replace_text_range"->{
                listOf("module_id","block_id","content_ref").forEach {require(value.getString(it).isNotBlank()){"文字目标不能为空"}}
                // [T-lenient-parse] 范围位置容忍字符串数字（模型常把整数写成文字）。
                fun position(key:String):Long=when(val raw=value.get(key)){is Long->raw;is Int->raw.toLong();is String->raw.trim().toLongOrNull();else->null}?:throw IllegalArgumentException("$key 必须是整数")
                val start=position("start");val end=position("end")
                require(start>=0 && end>=start){"文字修改范围无效"}
                CardToolEdit.ReplaceTextRange(value.getString("module_id"),value.getString("block_id"),novex.content.ContentRef(value.getString("content_ref")),start,end,value.getString("text"))
            }
            else->{
                // [T-lenient-parse] write_module_text：block_id 容忍 null/缺省/模型
                // 猜的伪编号（new/create/auto 等）——一律按新建处理，不再爆红。
                val rawBlock=value.optString("block_id").trim()
                val blockId=rawBlock.takeIf { it.isNotEmpty() && it.lowercase() !in NEW_BLOCK_HINTS }
                CardToolEdit.WriteText(value.getString("module_id"),blockId,value.optString("name"),value.getString("text"))
            }
        }
        return CardToolRequest(chatId,call.id,ManagementTarget(value.getString("root_id"),value.getString("target_id")),value.getString("draft_version"),edit)
    }
    fun result(result:CardToolResult):String=when(result) {
        is CardToolResult.Read->JSONObject(result.content)
        is CardToolResult.CheckpointSaved->JSONObject().put("status","checkpoint_saved").put("conversation_id",result.conversationId).put("checkpoint_id",result.checkpointId)
        is CardToolResult.StateSaved->JSONObject().put("status","state_saved").put("conversation_id",result.conversationId).put("event_id",result.eventId)
        is CardToolResult.Registered->JSONObject().put("status","registered").put("conversation_id",result.conversationId).put("registration_id",result.registrationId)
        is CardToolResult.Saved->JSONObject().put("status","saved").put("root_id",result.rootId).put("target_id",result.targetId).put("revision",result.revision)
            // [T-saved-with-ids] 新建编号 + 可直链版本：下一步直接引用，免 read_card 回读。
            .put("created_module_ids",JSONArray(result.createdModules)).put("created_block_ids",JSONArray(result.createdBlocks)).put("next_draft_version","saved:${result.revision}")
        is CardToolResult.Failed->JSONObject().put("status","failed").put("reason",result.reason).put("preserved_draft",result.preservedDraft?:JSONObject.NULL)
        is CardToolResult.Stopped->JSONObject().put("status","stopped").put("preserved_draft",result.preservedDraft?:JSONObject.NULL)
        CardToolResult.Denied->JSONObject().put("status","denied")
        CardToolResult.AwaitingApproval->JSONObject().put("status","awaiting_approval")
        CardToolResult.Unconfirmed->JSONObject().put("status","unconfirmed")
    }.toString()
}
