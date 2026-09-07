package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object NovexCardFileTools {
    const val CREATE = "novex_write_card"
    const val WRITE_MODULE = "novex_write_module"
    const val MOVE_MODULE = "novex_move_module"
    const val LINK = "novex_link_cards"
    val names = setOf(CREATE, WRITE_MODULE, MOVE_MODULE, LINK)
    private fun string(label: String) = AgentToolParam("string", label)
    private val kind = AgentToolParam("string", "对象类型：world（世界）、character_version（具体角色版本）、game（文游）", enumValues = listOf("world", "character_version", "game"))
    private val source = AgentToolParam("object", "精确复制当前对话文档中的原文范围，不重新生成正文", properties = linkedMapOf(
        "document_ref" to string("检查文档返回的来源引用"), "source_revision" to string("检查文档返回的来源修订"),
        "first_block" to AgentToolParam("integer", "起始内容块序号，从 1 开始"),
        "last_block" to AgentToolParam("integer", "结束内容块序号，包含该块")),
        required = listOf("document_ref", "source_revision", "first_block", "last_block"))
    private val body = linkedMapOf(
        "name" to string("模块名称"), "type" to string("模块类型，普通章节省略即可，默认 custom（自定义文章）；专用类型由查看目录返回"),
        "text" to string("模块完整正文；软件自动包装文章格式"),
        "content" to AgentToolParam("string", "专用模块的 JSON（结构化数据）正文文本，使用目录中的内容示例；普通文章直接用 text，无需编码"),
        "source" to source)
    fun definitions() = listOf(
        AgentToolDefinition(CREATE,
            "按标准模块创建并保存一张原生卡片。软件自动使用本对话对应的可写空卡；已有内容不会被覆盖，继续创建会增加卡片，数量不限于三张。" +
                "普通私有创建在同一调用中保存并回读核验，受保护写入返回待确认计划。只创建用户要求的类型，文游不需要配套世界或角色。" +
                "原文按章节入卡时只提供 document_ref 和 source_revision，软件复制所有章节及篇首资料；先通读理解，复制不代表模型理解准确。" +
                "自行创作时提供 modules（模块数组），顺序就是展示顺序。两种来源方式不要同时提供。修改已有模块使用 novex_write_module。",
            linkedMapOf(
                "kind" to AgentToolParam("string", "要创建的卡片类型", enumValues = listOf("world", "character", "game")),
                "name" to string("卡片名称"), "summary" to string("可选简介"),
                "launch_mode" to AgentToolParam("string", "文游启动方式；默认 free_sandbox（自由沙盒）", enumValues = listOf("fixed_identity", "user_created_identity", "co_create_world", "free_sandbox")),
                "document_ref" to string("完整原文按章节入卡的文档引用，不必重新抄写全文"),
                "source_revision" to string("文档检查返回的来源修订"),
                "modules" to AgentToolParam("array", "自行组织的一到一千个模块", items = AgentToolParam("object", "模块", properties = body, required = listOf("name")))),
            required = listOf("kind", "name")),
        AgentToolDefinition(WRITE_MODULE,
            "写入卡片模块，普通正文直接填写 text。已有模块提供 module_id；新增模块提供 kind、card_id 和 name。修改时未填写的字段保持不变。" +
                "正文用 text、content 或 source 三选一。普通私有编辑按用户要求保存并核对；共享修改返回待确认计划。不会切换回答身份或刷新本局设定。",
            linkedMapOf("module_id" to string("已有模块编号；提供后只修改此模块"), "kind" to kind,
                "card_id" to string("新增模块所属卡片编号")) + body,
            required = emptyList()),
        AgentToolDefinition(MOVE_MODULE, "调整一个模块的排列位置，其余正文和模块保持不变。私有修改直接保存；受保护对象先返回确认计划。",
            linkedMapOf("module_id" to string("要移动的模块编号"), "position" to AgentToolParam("integer", "新的顺序序号，从 0 开始")), required = listOf("module_id", "position")),
        AgentToolDefinition(LINK,
            "给卡片建立带用途索引，或移除指定索引。只修改来源卡片，不授予目标权限、不开始扮演、不启动文游、不刷新当前设定。引用与创建数量不限制管理区的文游数量。",
            linkedMapOf("kind" to kind, "card_id" to string("来源卡片编号"), "reference_id" to string("新建时给一个稳定索引名；修改/删除沿用返回的编号"),
                "target_kind" to kind, "target_id" to string("目标卡片编号"),
                "purpose" to AgentToolParam("string", "引用用途", enumValues = listOf("background", "answer_identity", "player_identity", "rules", "management")),
                "source_module_id" to string("可选来源模块编号"), "target_module_id" to string("可选目标模块编号"), "target_entry_id" to string("可选目标条目编号"),
                "remove" to AgentToolParam("boolean", "为 true 时只移除已有索引，不删除独立卡片")), required = listOf("kind", "card_id", "reference_id")),
    )
}
