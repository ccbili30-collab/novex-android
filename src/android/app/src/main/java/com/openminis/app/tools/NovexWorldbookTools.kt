package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object NovexWorldbookTools {
    const val INSPECT = "inspect_worldbook_choices"
    const val DEFAULTS = "set_game_worldbooks"
    const val CURRENT = "set_current_worldbooks"
    val names = setOf(INSPECT, DEFAULTS, CURRENT)
    fun definitions() = listOf(
        AgentToolDefinition(INSPECT, "查看文游默认世界书或本局已采用的选择。提供 project_id（文游编号）查看原卡；省略查看本局。返回选择修订和稳定引用编号，普通回答不要显示内部编号。只查看不会启动文游或授予编辑权限。",
            mapOf("project_id" to AgentToolParam("string", "可选，明确指定的已保存文游编号")), required = emptyList()),
        AgentToolDefinition(DEFAULTS, "完整保存指定文游的默认世界书选择，供以后启动使用。先查看选择取得修订，保留用户未要求移除的项目。可多选世界或模块／条目；不修改已开始的游玩、不复制原书、不切换身份。文游须在可管理内容内，工具审批沿用对话权限。",
            mapOf("project_id" to AgentToolParam("string", "来源文游编号"), "expected_revision" to AgentToolParam("string", "查看默认选择返回的修订，防止覆盖其他编辑"),
                "worldbooks" to AgentToolParam("array", "完整世界书选择；空数组明确清除默认选择", items = AgentToolParam("object", "一本书或其指定范围", properties = mapOf(
                    "world_id" to AgentToolParam("string", "世界库里的真实卡片编号"), "module_id" to AgentToolParam("string", "可选模块编号"),
                    "entry_id" to AgentToolParam("string", "可选条目编号，必须同时有模块编号"), "enabled" to AgentToolParam("boolean", "是否启用")), required = listOf("world_id", "enabled")))),
            required = listOf("project_id", "expected_revision", "worldbooks")),
        AgentToolDefinition(CURRENT, "只调整本局已经采用的世界书引用开关，保存后下次请求生效；不改原卡或其他对话。先查看本局取得修订和引用编号。关闭一条路径不影响另一条仍启用路径。不能用此工具把尚未采用的新原件偷偷塞入旧快照。",
            mapOf("expected_revision" to AgentToolParam("string", "查看本局选择返回的修订"), "changes" to AgentToolParam("array", "本次开关修改", items = AgentToolParam("object", "引用开关", properties = mapOf(
                "reference_id" to AgentToolParam("string", "已采用引用编号"), "enabled" to AgentToolParam("boolean", "是否启用")), required = listOf("reference_id", "enabled")))),
            required = listOf("expected_revision", "changes")),
    )
}
