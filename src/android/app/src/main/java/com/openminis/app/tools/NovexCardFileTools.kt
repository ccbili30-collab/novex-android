package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object NovexCardFileTools {
    const val CREATE = "novex_write_card"
    const val UPDATE = "novex_update_card"
    const val WRITE_MODULE = "novex_write_module"
    const val MOVE_MODULE = "novex_move_module"
    const val LINK = "novex_link_cards"
    val names = setOf(CREATE, UPDATE, WRITE_MODULE, MOVE_MODULE, LINK)
    private fun string(label: String) = AgentToolParam("string", label)
    private val kind = AgentToolParam("string", "对象类型：world（世界）、character_version（具体角色版本）、game（文游）", enumValues = listOf("world", "character_version", "game"))
    private val source = AgentToolParam("object", "精确复制当前对话文档中的原文范围，不重新生成正文", properties = linkedMapOf(
        "document_ref" to string("检查文档返回的来源引用"), "source_revision" to string("检查文档返回的来源修订"),
        "first_block" to AgentToolParam("integer", "起始内容块序号，从 1 开始"),
        "last_block" to AgentToolParam("integer", "结束内容块序号，包含该块")),
        required = listOf("document_ref", "source_revision", "first_block", "last_block"))
    private val body = linkedMapOf(
        "name" to string("模块名称"), "type" to string("模块类型，普通章节省略即可，默认 custom（自定义文章）；专用类型由查看目录返回"),
        "text" to string("模块完整正文；用户给出要写入的具体文字时原样填写。只有用户要求改写、扩写或创作新内容时才自行撰写，不能擅自补充设定与因果。软件自动包装文章格式。"),
        "content" to AgentToolParam("object", "专用模块的结构化内容对象，使用高级目录中的内容示例；直接传对象，不转成字符串。普通文章直接用 text。世界书使用条件见 contextTrigger（使用条件）：version（版本）为1、enabled（启用）、constant（常驻）、keys（关键词数组）、scanDepth（最近可见消息数，默认2）、caseSensitive（区分大小写）。未设条件为普通资料，不猜关键词。"),
        "source" to source)
    fun definitions() = listOf(
        AgentToolDefinition(CREATE,
            "按标准模块创建并保存一张原生卡片。软件自动使用本对话对应的可写空卡；已有内容不会被覆盖，继续创建会增加卡片，数量不限于三张。" +
                "创建会保存、归库并回读，是否执行由对话权限统一决定，不要求文字口令。只创建用户要求的类型，文游不需要配套世界或角色。" +
                "用户提供资料要求存成卡，默认忠实保存：只提供 document_ref 和 source_revision，软件一次复制所有章节及篇首资料，无需逐个模块重写或摘要。先通读理解，复制不代表模型理解准确。" +
                "自行创作时提供 modules（模块数组），顺序就是展示顺序。两种来源方式不要同时提供。修改已有模块使用 novex_write_module。",
            linkedMapOf(
                "creation_key" to string("本轮每张预期卡片的稳定标识，例如 game-1；重试沿用，明确创建另一张（即使正文相同）使用新标识。由你填写，不询问用户。"),
                "kind" to AgentToolParam("string", "要创建的卡片类型", enumValues = listOf("world", "character", "game")),
                "name" to string("简短易读的卡片名称，不拼接内部编号、校验值或路径；详细背景写入简介和模块"), "summary" to string("可选简介"),
                "launch_mode" to AgentToolParam("string", "文游启动方式；默认 free_sandbox（自由沙盒）", enumValues = listOf("fixed_identity", "user_created_identity", "co_create_world", "free_sandbox")),
                "document_ref" to string("完整原文按章节入卡的文档引用，不必重新抄写全文"),
                "source_revision" to string("文档检查返回的来源修订"),
                "modules" to AgentToolParam("array", "自行组织的一到一千个模块", items = AgentToolParam("object", "模块", properties = body, required = listOf("name")))),
            required = listOf("creation_key", "kind", "name")),
        AgentToolDefinition(UPDATE,
            "修改已有卡片的名称、简介或文游启动方式。先从内容目录获取真实卡片编号；仅改提供的字段，正文模块、图片、引用和本局采用的快照保持不变。不要为改名重新建卡。",
            linkedMapOf("kind" to kind, "card_id" to string("要修改的卡片或角色版本编号"),
                "name" to string("可选的新名称"), "summary" to string("可选的新简介；空字符串表示清空简介"),
                "launch_mode" to AgentToolParam("string", "仅文游可用的启动方式",
                    enumValues = listOf("fixed_identity", "user_created_identity", "co_create_world", "free_sandbox"))),
            required = listOf("kind", "card_id")),
        AgentToolDefinition(WRITE_MODULE,
            "写入卡片模块，普通正文直接填写 text。已有模块提供 module_id；新增模块提供 kind、card_id 和 name。补充已有模块时必须使用原编号，不能省略编号另建同名模块。修改时未填写的字段保持不变。mode=append（追加）只传新增文本，由软件保留原文；默认 replace（替换）需要提交完整正文。" +
                "正文用 text、content 或 source 三选一。忠实执行指定修改：例如用户说新增门规模块：入内先敲门，正文就是“入内先敲门”；不要自行补写制度由来或其他新事实。用户明确要求扩写时再创作。软件按对话权限执行并保存；无需文字确认。不会切换回答身份或刷新本局设定。",
            linkedMapOf("module_id" to string("已有模块编号；提供后只修改此模块"), "kind" to kind,
                "card_id" to string("新增模块所属卡片编号"),
                "mode" to AgentToolParam("string", "已有文章的写入方式；默认完整替换，追加只传新文本", enumValues = listOf("replace", "append")),
                "allow_duplicate_name" to AgentToolParam("boolean", "仅在明确另建同名模块时为 true；补充原模块不要使用此选项")) + body,
            required = emptyList()),
        AgentToolDefinition(MOVE_MODULE, "只在同一张卡内调整一个模块的排列位置，其余正文和模块保持不变。不能用于合并两张卡；位置必须在该卡现有模块范围内。执行权限由对话设置统一决定。",
            linkedMapOf("module_id" to string("要移动的模块编号"), "position" to AgentToolParam("integer", "新的顺序序号，从 0 开始")), required = listOf("module_id", "position")),
        AgentToolDefinition(LINK,
            "给卡片建立带用途索引，或移除指定索引。只修改来源卡片，不授予目标权限、不开始扮演、不启动文游、不刷新当前设定。引用与创建数量不限制管理区的文游数量。",
            linkedMapOf("kind" to kind, "card_id" to string("来源卡片编号"), "reference_id" to string("新增关联时省略，软件生成稳定编号；修改或删除已有引用时使用返回的编号"),
                "target_kind" to kind, "target_id" to string("目标卡片编号"),
                "purpose" to AgentToolParam("string", "引用用途", enumValues = listOf("background", "answer_identity", "player_identity", "rules", "management")),
                "source_module_id" to string("可选来源模块编号"), "target_module_id" to string("可选目标模块编号"), "target_entry_id" to string("可选目标条目编号"),
                "remove" to AgentToolParam("boolean", "为 true 时只移除已有索引，不删除独立卡片")), required = listOf("kind", "card_id")),
    )
}
