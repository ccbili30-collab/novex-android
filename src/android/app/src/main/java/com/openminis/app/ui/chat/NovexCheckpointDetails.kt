package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun NovexCheckpointDetails(records: List<NovexCheckpointRecord>, onDismiss: () -> Unit) {
    var selectedRef by rememberSaveable { mutableStateOf<String?>(null) }
    var messageId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectingMessage by rememberSaveable { mutableStateOf(false) }
    val selected = records.firstOrNull { it.entry.workspaceRef.value == selectedRef }
    val checkpoint = selected?.checkpoint
    val event = checkpoint?.sourceEvents?.firstOrNull { it.messageId == messageId }
    fun date(value: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))
    when {
        selected == null -> NovexSearchableSelectionSheet("本分支存档", records.map { row ->
            NovexSelectionAction(row.checkpoint?.name ?: "存档暂不可读", description = "${date(row.entry.createdAtMillis)} · ${row.error ?: "已保存，摘要未核验"}") {
                selectedRef = row.entry.workspaceRef.value
            }
        }.ifEmpty { listOf(NovexSelectionAction("本分支尚无正式存档", enabled = false) {}) }, "搜索名称或时间", onDismissRequest = onDismiss, dismissOnSelection = false)
        event != null -> NovexContentDialog("保存时的原始消息", onDismiss = { messageId = null },
            confirmButton = { TextButton(onClick = { messageId = null }) { Text("返回依据列表") } }) {
            Text("消息 ${event.messageId} · ${when (event.role) { "user" -> "用户"; "assistant" -> "人工智能"; else -> "软件或工具" }}")
            Text("修订 ${event.revision} · ${date(event.createdAtMillis)}")
            Text(event.partsJson)
            event.error?.let { Text("当时存在错误：$it") }
            Text("以上保留消息原载荷；人物叙述、工具输出与操作请求不能混为已经发生的故事事实。")
        }
        selectingMessage && checkpoint != null -> NovexSearchableSelectionSheet("原始消息依据", checkpoint.sourceEvents.map { source ->
            NovexSelectionAction("${date(source.createdAtMillis)} · ${source.messageId}", description = source.partsJson.take(120)) { messageId = source.messageId }
        }.ifEmpty { listOf(NovexSelectionAction("没有保存原始消息依据", enabled = false) {}) }, "搜索消息编号或原话片段",
            onDismissRequest = { selectingMessage = false }, dismissOnSelection = false)
        else -> NovexContentDialog(checkpoint?.name ?: "存档暂不可读", onDismiss = { selectedRef = null },
            confirmButton = { TextButton(onClick = { selectedRef = null }) { Text("返回存档列表") } }) {
            Text("${date(selected.entry.createdAtMillis)} · ${selected.entry.workspaceRef.value}")
            if (checkpoint == null) Text(selected.error ?: "无法读取") else {
                Text("软件本局数值（保存时）")
                Text(checkpoint.playthroughValues.entries.joinToString("\n") { (key, value) -> "$key：${when (value) {
                    is PlaythroughValue.Text -> value.value
                    is PlaythroughValue.Number -> value.value
                    is PlaythroughValue.Flag -> value.value
                }}" }.ifBlank { "当时没有数值字段" })
                Text("模型摘要 · 未核验辅助整理")
                Text(checkpoint.summary)
                Text("模型补充状态 · 未核验辅助整理")
                Text(checkpoint.stateJson)
                NovexTextActionRow("查看 ${checkpoint.sourceEvents.size} 条原始消息依据", onClick = { selectingMessage = true })
                if (!checkpoint.sourceCaptureRecorded) Text("旧格式未保存原始消息依据；原载荷继续保留，不把摘要升级为已核验事实。")
                if (checkpoint.missingSourceMessageIds.isNotEmpty()) Text("未能保存的消息编号：${checkpoint.missingSourceMessageIds.joinToString()}")
                Text("续接请求读取本局当前分支的最近可用存档依据。查看存档不推进剧情，也不把数值回滚到这个时点；原文与摘要冲突时以原始来源和软件状态核对。")
            }
        }
    }
}
