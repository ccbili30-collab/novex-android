package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.NovexCharacterRevision
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun NovexCharacterRevisionSection(versionId: String) {
    val workspace = rememberNovexWorkspace()
    var revisions by remember(versionId) { mutableStateOf<List<NovexCharacterRevision>>(emptyList()) }
    var choose by remember(versionId) { mutableStateOf(false) }
    var selected by remember(versionId) { mutableStateOf<NovexCharacterRevision?>(null) }
    var error by remember(versionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(versionId, choose) {
        try { revisions = workspace.characterRevisions(versionId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取编辑记录失败" }
    }
    NovexContentSection("编辑记录", subtitle = "同一人物版本的保存记录；不会产生新的人生阶段") {
        NovexSummaryRow("当前版本", if (revisions.isEmpty()) "尚无编辑记录" else "已保存 ${revisions.size} 次修订",
            onClick = { choose = true })
    }
    if (choose) NovexSearchableSelectionSheet("编辑记录", revisions.asReversed().map { revision ->
        NovexSelectionAction("修订 ${revision.sequence}", description = DateFormat.getDateTimeInstance().format(Date(revision.savedAt))) {
            choose = false; selected = revision
        }
    }, "搜索修订或时间", onDismissRequest = { choose = false })
    selected?.let { revision ->
        NovexContentDialog("修订 ${revision.sequence}", onDismiss = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) {
            Text("保存时的资料、模块和引用；图片仅记录内容摘要，不提供已删除图片的恢复。", color = NovexColors.SecondaryText)
            SelectionContainer {
                Text(JSONObject(revision.contentJson).toString(2), modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
    error?.let { NovexNoticeDialog("编辑记录", it) { error = null } }
}
