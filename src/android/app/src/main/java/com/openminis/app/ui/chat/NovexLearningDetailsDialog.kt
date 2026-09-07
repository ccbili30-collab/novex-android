package com.openminis.app.ui.chat

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.*

/** Read-only saved snapshot. Leaving this view never starts or pauses a model request. */
@Composable
internal fun NovexLearningDetailsDialog(
    state: NovexLearningState,
    onDismiss: () -> Unit,
    onLatestResponse: () -> Unit,
    onContinue: (Boolean) -> Unit,
    onFiles: () -> Unit,
) {
    var notes by remember(state) { mutableStateOf<List<NovexLearningNote>?>(null) }
    var selected by remember(state) { mutableStateOf<NovexLearningNote?>(null) }
    val note = selected
    if (note != null) {
        NovexContentDialog(note.title, onDismiss = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("返回笔记列表") } }) {
            Text("这是模型整理结果，尚未完成事实核验；原文与整理覆盖不能相互替代。")
            SelectionContainer { Text(note.body) }
            Text("来源：${note.sourceDocumentRefs.size} 份文档，${note.sourceBlockIds.size} 个内容块。")
            note.sourceDocumentRefs.forEach { ref ->
                Text(ref.value + "\n解析修订：" + (note.sourceRevisions[ref] ?: "未记录，不能推定为当前解析"))
            }
        }
    } else if (notes != null) {
        NovexSearchableSelectionSheet("已保存笔记", notes.orEmpty().map { item ->
            NovexSelectionAction(item.title, description = when (item.level) {
                NovexLearningNoteLevel.COLLECTION -> "资料集总览"
                NovexLearningNoteLevel.FILE -> "文件整理"
                NovexLearningNoteLevel.SECTION -> "章节整理"
                NovexLearningNoteLevel.BLOCK -> "正文片段整理"
            }) { selected = item }
        }, "搜索笔记标题", onDismissRequest = { notes = null })
    } else {
        NovexContentDialog("资料与整理计划", onDismiss = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("返回任务") } }) {
            Text(state.collection.title)
            state.task?.let { Text(NovexLearningControlPolicy.progressMessage(it)) }
            Text("已保存快照：整理 ${state.reviewLedger.reviewedBlocks} / ${state.reviewLedger.totalReadableBlocks} 个可读块；" +
                "无法完整解析 ${state.reviewLedger.unreadableSourceRefs.size} 项。覆盖数字不代表事实核验通过。")
            state.lastFailure?.let { Text(it) }
            NovexSummaryRow("当前笔记", "${state.notes.size} 条，包含章节与总览", onClick = { notes = state.notes })
            NovexSummaryRow("历史成果", "${state.historicalNotes.size} 条，不计入本次整理覆盖", onClick = { notes = state.historicalNotes })
            NovexSummaryRow("原文与成果文件", "在本对话文件查看原附件、解析正文、笔记与主题索引", onClick = onFiles)
            NovexSummaryRow("最近一次模型返回", "查看已保存的原始返回与用量", onClick = onLatestResponse)
            val status = state.task?.status
            if (status in setOf(NovexLearningTaskStatus.PAUSED, NovexLearningTaskStatus.PAUSED_BUDGET_REACHED,
                    NovexLearningTaskStatus.PARTIAL_FAILURE)) {
                NovexSummaryRow("按当前模型续接", "保留已提交进度与累计用量，先核对新计划", onClick = { onContinue(false) })
            }
            if (status in setOf(NovexLearningTaskStatus.PAUSED, NovexLearningTaskStatus.PAUSED_BUDGET_REACHED,
                    NovexLearningTaskStatus.PARTIAL_FAILURE, NovexLearningTaskStatus.COMPLETE)) {
                NovexSummaryRow("重新核对来源", "保留旧成果，确认后仅重整变化或修订未知的资料", onClick = { onContinue(true) })
            } else Text("需要续接或重新核对来源时，先返回任务并暂停整理。")
            if (state.previousTasks.isNotEmpty()) {
                Text("历次采用的模型（用量为当时的累计值，不应相加）")
                state.previousTasks.forEach { task ->
                    Text("${task.preflight.modelProviderName} / ${task.preflight.modelId}：输入 ${task.usage.usedInputTokens}，输出 ${task.usage.usedOutputTokens} 词元")
                }
            }
            Text("来源范围")
            state.collection.sources.forEach { source ->
                Text(source.title + "\n" + (source.failureCode ?: when (source.status) {
                    NovexSourceStatus.READY -> "可读"
                    NovexSourceStatus.EXACT_DUPLICATE -> "重复来源，复用已导入文档"
                    else -> "存在未解析或不完整内容"
                }))
            }
        }
    }
}
