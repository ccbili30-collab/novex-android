package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ScrollState
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONArray
import org.json.JSONObject

internal data class NovexToolDetailField(
    val label: String,
    val value: String,
)

internal data class NovexStandardToolDetailPresentation(
    val title: String,
    val requestFields: List<NovexToolDetailField>,
    val resultFields: List<NovexToolDetailField>,
    val body: String?,
)

private val standardToolTitles = mapOf(
    "novex_write_card" to "创建并填写卡片",
    "novex_update_card" to "修改卡片资料",
    "novex_write_module" to "保存模块",
    "novex_move_module" to "调整模块顺序",
    "novex_link_cards" to "设置卡片引用",
    "document_inspect" to "检查文档",
    "document_read" to "读取文档",
    "workspace_inspect" to "检查工作区",
    "workspace_read" to "读取工作区",
    "workspace_search" to "查找仓库资料",
    "workspace_write" to "写入工作区",
    "workspace_edit" to "编辑工作区",
    "workspace_compute" to "处理工作区",
    "learning_prepare" to "准备整理资料",
    "save_checkpoint" to "保存进度",
    "select_answer_identity" to "选择回答身份",
    "start_interactive_fiction" to "启动文游",
    "inspect_story_images" to "查看剧情插图",
    "select_story_image" to "选择剧情插图",
    "inspect_worldbook_choices" to "查看世界书选择",
    "set_game_worldbooks" to "设置文游世界书",
    "set_current_worldbooks" to "调整本局世界书",
    "register_controls" to "更新快捷操作",
    "update_playthrough_state" to "更新本局状态",
    "novex_inspect_content" to "查看挂载内容",
    "novex_propose_content_changes" to "提出内容变更",
    "novex_apply_content_changes" to "执行内容变更",
    "novex_inspect_memory" to "查看长期记忆",
    "novex_propose_memory_changes" to "提出记忆变更",
    "novex_apply_memory_changes" to "执行记忆变更",
) + com.openminis.app.cards.IntegratedCardToolLabels.values

private val detailFieldLabels = mapOf(
    "kind" to "卡片类型", "name" to "名称", "card_id" to "卡片编号",
    "saved" to "是否保存", "verification" to "回读核验", "created_cards" to "已创建卡片", "updated_cards" to "已更新卡片", "mode" to "写入方式", "allow_duplicate_name" to "另建同名模块",
    "modules" to "模块", "position" to "顺序", "source_revision" to "来源修订",
    "document_ref" to "文档",
    "collection_ref" to "资料集合",
    "block_ids" to "内容块",
    "page_range" to "页码范围",
    "query" to "查询",
    "cursor" to "游标",
    "next_cursor" to "下一页",
    "area" to "区域",
    "path" to "工作区项目",
    "file_ref" to "文件",
    "operation" to "操作",
    "subject_kind" to "对象类型",
    "subject_id" to "对象",
    "module_id" to "模块",
    "proposal_id" to "变更提案",
    "confirmation_phrase" to "确认短语",
    "status" to "状态",
    "type" to "结果类型",
    "title" to "标题",
    "summary" to "摘要",
    "message" to "结果",
    "bytes" to "大小",
    "truncated" to "是否截断",
    "changes" to "变更",
    "controls" to "快捷操作",
)

private val detailFieldOrder = listOf(
    "kind", "name", "card_id", "saved", "verification", "created_cards", "updated_cards", "modules", "position", "source_revision",
    "document_ref", "collection_ref", "area", "path", "file_ref", "subject_kind", "subject_id",
    "module_id", "query", "page_range", "block_ids", "operation", "changes", "controls", "cursor",
    "status", "type", "title", "summary", "message", "proposal_id", "confirmation_phrase",
    "next_cursor", "bytes", "truncated",
)

internal fun buildNovexStandardToolDetailPresentation(
    toolName: String,
    argumentsJson: String,
    resultText: String,
): NovexStandardToolDetailPresentation? {
    val title = standardToolTitles[toolName] ?: return null
    val arguments = parseObject(argumentsJson)
    val result = parseObject(resultText)
    return NovexStandardToolDetailPresentation(
        title = title,
        requestFields = arguments?.toDetailFields().orEmpty(),
        resultFields = result?.toDetailFields().orEmpty(),
        body = if (result != null) result.readableBody() else resultText.trim().takeIf { it.isNotEmpty() },
    )
}

private fun parseObject(raw: String): JSONObject? = runCatching {
    raw.trim().takeIf { it.startsWith('{') && it.endsWith('}') }?.let(::JSONObject)
}.getOrNull()

private fun JSONObject.toDetailFields(): List<NovexToolDetailField> {
    val keys = keys().asSequence().toSet()
    return detailFieldOrder
        .filter { it in keys && it in detailFieldLabels && !it.isSensitiveField() }
        .mapNotNull { key ->
            val value = opt(key).toReadableValue(key)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NovexToolDetailField(detailFieldLabels.getValue(key), value)
        }
}

private fun JSONObject.readableBody(): String? = sequenceOf("content", "text", "markdown")
    .mapNotNull { key -> optString(key, "").trim().takeIf { it.isNotEmpty() } }
    .firstOrNull()

private fun String.isSensitiveField(): Boolean {
    val normalized = lowercase()
    return listOf("token", "password", "secret", "api_key", "authorization", "cookie")
        .any(normalized::contains)
}

private fun Any?.toReadableValue(key: String): String? = when (this) {
    null, JSONObject.NULL -> null
    is Boolean -> if (this) "是" else "否"
    is JSONArray -> when {
        length() == 0 -> "无"
        key == "changes" -> "${length()} 项"
        key == "controls" -> "${length()} 个"
        else -> (0 until length()).take(4).joinToString("、") { index ->
            opt(index).toCompactText()
        } + if (length() > 4) " 等 ${length()} 项" else ""
    }
    is JSONObject -> when (key) {
        "verification" -> if (optBoolean("verified")) "通过实际回读核验" else "尚未通过核验"
        "page_range" -> listOf(opt("start"), opt("end"))
            .filterNotNull()
            .joinToString("–") { it.toString() }
            .ifEmpty { "已设置" }
        else -> "已设置"
    }
    else -> this.toString().translateKnownValue(key).ellipsize(600)
}

private fun Any?.toCompactText(): String = when (this) {
    is JSONObject -> when {
        has("name") -> optString("name")
        has("kind") && has("id") -> optString("kind").translateKnownValue("kind") + " · " + optString("id")
        else -> optString("operation", optString("title", "一项内容"))
    }
    JSONObject.NULL, null -> "空"
    else -> toString().ellipsize(120)
}

private fun String.translateKnownValue(key: String): String = when (key to lowercase()) {
    "status" to "ok", "status" to "success", "status" to "completed" -> "成功"
    "status" to "confirmation_required", "status" to "waiting_confirmation" -> "等待确认"
    "status" to "saved_verified" -> "已保存并回读核验"
    "status" to "saved_needs_review" -> "已保存，仍需核验"
    "status" to "failed", "status" to "error" -> "失败"
    "kind" to "world", "subject_kind" to "world" -> "世界"
    "kind" to "character", "subject_kind" to "character" -> "角色"
    "kind" to "character_version", "subject_kind" to "character_version" -> "角色版本"
    "kind" to "game", "subject_kind" to "game" -> "文游"
    else -> this
}

private fun String.ellipsize(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength - 1) + "…"

@Composable
internal fun NovexStandardToolDetailContent(
    presentation: NovexStandardToolDetailPresentation,
    scrollState: ScrollState = rememberScrollState(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatColors.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = presentation.title,
            color = ChatColors.primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        ToolDetailSection("请求", presentation.requestFields)
        ToolDetailSection("结果", presentation.resultFields)
        presentation.body?.takeIf { it.isNotBlank() }?.let { body ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("内容", color = ChatColors.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = body,
                    color = ChatColors.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }
        }
    }
}

@Composable
private fun ToolDetailSection(
    title: String,
    fields: List<NovexToolDetailField>,
) {
    if (fields.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChatColors.secondaryBg, RoundedCornerShape(12.dp)),
    ) {
        Text(
            text = title,
            color = ChatColors.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        fields.forEachIndexed { index, field ->
            if (index > 0) HorizontalDivider(color = ChatColors.separator, thickness = 0.5.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = field.label,
                    color = ChatColors.secondaryText,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(0.34f),
                )
                Text(
                    text = field.value,
                    color = ChatColors.primaryText,
                    fontSize = 13.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.66f),
                )
            }
        }
    }
}
