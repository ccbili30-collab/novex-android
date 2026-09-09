package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * Model-facing contracts for managed Novex content.
 *
 * Inspection is read-only, proposal is inert, and apply accepts only a stored
 * proposal id. The executor obtains confirmation from the latest real user turn;
 * there is intentionally no boolean or confirmation-text tool argument.
 */
object NovexManagementTools {
    const val READ_CONTEXT = "novex_read_context"
    const val INSPECT = "novex_inspect_content"
    const val PROPOSE = "novex_propose_content_changes"
    const val APPLY = "novex_apply_content_changes"

    fun modelDefinitions(): List<AgentToolDefinition> = definitions().map { tool ->
        if (tool.name != PROPOSE) tool else tool.copy(
            description = "高级批量变更计划。普通创建、模块写入、排序和索引请使用专用卡片工具。需要删除、人物版本关系或成果附加等高级操作时，先用 novex_inspect_content 的 include_advanced=true 查看参数，再提交计划。软件按本对话的执行权限直接执行或弹窗批准；不要求用户发送文字口令。",
            parameters = mapOf("changes" to AgentToolParam("string", "一到二十项高级变更的结构化数组文本；具体字段按查看工具返回的 advanced_change_guide 填写")),
        )
    }

    fun advancedGuide(): String = requireNotNull(definitions().single { it.name == PROPOSE }.parameters["changes"]).description

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = READ_CONTEXT,
            description = "查看当前对话实际采用的身份、背景与活动文游资料，或按来源编号读取、搜索。" +
                "只返回当前用途允许的内容；管理挂载不会自动进入此目录。私有扮演指令仅供当前回答角色使用，未采用的配套玩家身份不可读取。" +
                "活动文游采用已固定的资料，不能用管理区中的新版本替换本局内容。目录预览与搜索片段不代表通读全文。" +
                "成功返回前保存实际阅读范围；read_coverage（阅读覆盖）累计当前分支同一修订的正文读取，complete（完成）为真才表示这份修订已完整读过。历史覆盖不表示全文仍在当前上下文内，需要时重新读取。",
            parameters = mapOf(
                "operation" to AgentToolParam("string", "操作：inspect（查看目录，默认）、read（读取来源）、search（搜索当前可用资料）。",
                    enumValues = listOf("inspect", "read", "search")),
                "source_id" to AgentToolParam("string", "读取时必填，使用目录返回的来源编号；不能猜测或使用管理对象编号代替。"),
                "query" to AgentToolParam("string", "搜索时必填，一到三百个字符。"),
                "offset" to AgentToolParam("integer", "使用上次返回的 next_offset（下一偏移），默认零。目录与搜索按条数，读取按字符偏移。"),
                "limit" to AgentToolParam("integer", "目录每页最多一百项，搜索最多五十项，读取最多两万四千字符；省略时使用默认大小。"),
                "revision" to AgentToolParam("string", "读取后续片段时必须传上次返回的修订摘要；版本变化会拒绝继续拼接。"),
            ),
            propertyOrdering = listOf("operation", "source_id", "query", "offset", "limit", "revision"),
        ),
        AgentToolDefinition(
            name = INSPECT,
            description = "查看当前对话私有卡片和已加入管理区的卡片或模块。无参数列卡片目录；只传 subject_kind 可按类型筛选目录；指定卡片编号列模块目录，指定 module_id 读取正文。专用模块格式及高级操作按需传 include_advanced=true 查看。" +
                "私有空卡可读，创建时由 novex_write_card 自动承接，填满后可继续创建更多卡；未授权的外部对象不能读取。" +
                "同时返回 card_references（向外引用）、card_backlinks（使用来源）、reference_purposes（合法用途）；这些目录不会授予目标卡片的读写权限。",
            parameters = mapOf(
                "subject_kind" to AgentToolParam(
                    type = "string",
                    description = "卡片类型；单独填写时仅列出当前可管理的这一类卡片。",
                    enumValues = listOf("world", "character_version", "game", "artifact"),
                ),
                "subject_id" to AgentToolParam("string", "本对话私有或已加入管理区的对象编号，与 subject_kind 一起填写。"),
                "include_advanced" to AgentToolParam("boolean", "需要专用模块格式、人物阶段关系或高级批量操作时设为 true，返回内容示例和完整参数说明。"),
                "module_id" to AgentToolParam("string", "要读取正文的模块编号；使用目录返回的 module_id，不能用计划或卡片编号代替。"),
                "profile_section" to AgentToolParam("string", "角色总览默认只读 public（公开资料）；明确管理旧格式专属扮演资料时选择 role_instructions（专属扮演指令）。私有模块通过 private_modules（私有模块目录）的编号单独读取。明确分析保存的酒馆原件时选择 exchange_source（交换原件），按偏移和修订分段读取；背景用途不能访问原件。", enumValues = listOf("public", "role_instructions", "exchange_source")),
                "offset" to AgentToolParam("integer", "仅用于交换原件：起始字符偏移，首次为 0，后续使用返回的 next_offset（下一偏移）。"),
                "limit" to AgentToolParam("integer", "仅用于交换原件：本次字符上限，1 至 16000，默认 8000。"),
                "revision" to AgentToolParam("string", "仅用于交换原件：继续读取必须携带首次返回的 revision（修订摘要）；不同修订不能拼为通读。"),
            ),
            propertyOrdering = listOf("subject_kind", "subject_id", "module_id", "profile_section", "offset", "limit", "revision"),
        ),
        AgentToolDefinition(
            name = PROPOSE,
            description = "Validate a structured Novex change plan and persist its pending proposal without changing card content. Use only " +
                "for subjects mounted with edit access, or for a global create explicitly requested by the user. " +
                "The app retains an explicit creation request across follow-up turns such as 'continue' in the current task; " +
                "do not ask the user to repeat it. Cancellation or a changed target ends that request. " +
                "依据返回范围执行：当前明确创建请求可使用本对话私有空卡立即写入；已经授权编辑的本对话私有作品，" +
                "常规模块整理和引用调整也可立即执行。共享修改、删除、其他跨项目变更与额外全局创建仍需真实用户的确认短语。" +
                "不得伪造授权；只讨论、取消或没有创作意图时不能自行写入。",
            parameters = mapOf(
                "changes" to AgentToolParam(
                    type = "string",
                    description = "JSON array (maximum 20) of change objects. Use inspect first to obtain ids. " +
                        "Module operations: add_module requires {operation, subject_kind, subject_id, " +
                        "module_type, name, content_json}; module_type must use one of the stable module types returned by inspect for that subject kind. " +
                        "update_module requires {operation, module_id, name?, " +
                        "content_json?}; provide at least one field, omitted fields are preserved (content_json replaces the supplied document). " +
                        "move_module requires {operation, module_id, to_index}; delete_module " +
                        "requires {operation, module_id}. Reference operations add_reference and remove_reference " +
                        "require {operation, module_id, target_kind, target_id, position?}. " +
                        "带用途卡片引用使用 put_card_reference（新增或替换引用），参数为 " +
                        "{operation,subject_kind,subject_id,reference_id,target_kind,target_id,purpose,source_module_id?,target_module_id?,target_entry_id?,target_label?,position?}。" +
                        "只需来源卡片的编辑权限，不修改目标卡片。target_kind（目标类型）为 world（世界）、character_version（角色版本）、game（文游）；" +
                        "purpose（用途）为 background（背景）、answer_identity（回答身份）、player_identity（玩家身份）、rules（规则）、management（管理目标）。" +
                        "新增时提供唯一 reference_id（引用编号）；替换已有身份必须沿用原引用编号，不能添加第二个回答身份。回答身份只指向完整角色版本。" +
                        "remove_card_reference（移除卡片引用）使用 {operation,subject_kind,subject_id,reference_id}，保留独立目标。" +
                        "人物版本关系使用 put_version_relation（设置版本关系）：{operation,relation_id,source_version_id,target_version_id,relation_kind}；" +
                        "relation_kind（关系类型）为 earlier_stage（目标是更早人生阶段）、later_stage（目标是更晚人生阶段）或 parallel（平行分身）。" +
                        "只连接同一人物的不同版本；本体是默认版本，编辑修订不会产生新阶段。不会同步各版本的知识、记忆或切换回答身份。" +
                        "remove_version_relation（移除版本关系）使用 {operation,relation_id,source_version_id}，不删除版本。" +
                        "只要求来源版本的编辑权限，关联不授予目标正文访问权限。总览返回 version_relations（版本关系）和 version_relation_kinds（合法类型）；" +
                        "先按 source_version_id（来源版本编号）核对关系归属，替换沿用 relation_id（关系编号）。" +
                        "示例：{\"operation\":\"put_card_reference\",\"subject_kind\":\"game\",\"subject_id\":\"已返回的文游编号\",\"reference_id\":\"独立引用编号\",\"target_kind\":\"world\",\"target_id\":\"已返回的世界编号\",\"purpose\":\"background\"}。" +
                        "不得按重名猜测编号；这些操作不启动游戏、不切换当前对话身份，也不改写已采用的游玩快照。 Create operations: " +
                        "create_world requires {operation, name, overview?, modules?}; create_character requires {operation, " +
                        "name, profile_json, modules?}; create_character_version requires {operation, source_version_id, " +
                        "label, profile_json}; create_game requires {operation, name, summary?, launch_mode?, " +
                        "player_identity?, modules?}. Initial modules are an ordered array of {module_type,name,content_json}, " +
                        "using the same inspect catalog. Include the source chapters here to create a complete card atomically, " +
                        "not an empty shell; their ids and owner are assigned by the app. The 20-operation limit does not " +
                        "count nested modules (up to 1000 per new card). Add cross-card references after creation returns real ids. " +
                        "launch_mode is fixed_identity, user_created_identity, co_create_world, or " +
                        "free_sandbox (default); inspect also returns their labels. World links link_character_version and unlink_character_version require " +
                        "{operation, world_id, version_id, position?}. Artifact operations attach_artifact and " +
                        "detach_artifact require {operation, artifact_id, subject_kind, subject_id, module_id?, " +
                        "slot?}. subject_kind is world, character_version, or game. content_json and profile_json " +
                        "accept a JSON object or its serialized JSON string. Use each module's content_example returned by inspect: " +
                        "article {kind,text}, single_image {kind,description}, timeline {kind,nodes:[{time,title,description}]}, " +
                        "collection {kind,items:[{id,name,summary,description}]}. Keep full details in description, not only summary. " +
                        "Images use artifact attachment, never a guessed device path. Blank content may be {}.",
                ),
            ),
            required = listOf("changes"),
            propertyOrdering = listOf("changes"),
        ),
        AgentToolDefinition(
            name = APPLY,
            description = "Apply a previously validated Novex proposal atomically. The app, not tool arguments, " +
                "checks the latest real user message. 私有空卡创建和已授权的私有作品常规整理沿用真实用户请求，" +
                "其余变更需计划返回的确认短语。执行前再次检查作品归属与模块是否改变。" +
                "Never invent confirmation and never retry a rejected proposal without the real user.",
            parameters = mapOf(
                "proposal_id" to AgentToolParam("string", "Proposal id returned by novex_propose_content_changes."),
            ),
            required = listOf("proposal_id"),
            propertyOrdering = listOf("proposal_id"),
        ),
    )
}
