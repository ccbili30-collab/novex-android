package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.NovexCardRevision
import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexContentKind
import com.openminis.app.novex.domain.NovexRevisionDifference
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun NovexCharacterRevisionSection(versionId: String) = NovexCardRevisionSection(NovexContentAddress.characterVersion(versionId))

@Composable
internal fun NovexCardRevisionSection(subject: NovexContentAddress) {
    val workspace = rememberNovexWorkspace()
    var revisions by remember(subject) { mutableStateOf<List<NovexCardRevision>>(emptyList()) }
    var choose by remember(subject) { mutableStateOf(false) }
    var selected by remember(subject) { mutableStateOf<NovexCardRevision?>(null) }
    var error by remember(subject) { mutableStateOf<String?>(null) }
    LaunchedEffect(subject, choose) {
        try { revisions = if(subject.kind == NovexContentKind.CHARACTER_VERSION)
            workspace.characterRevisions(subject.id).map { NovexCardRevision(subject, it.sequence, it.savedAt, it.contentJson) }
            else workspace.cardRevisions(subject)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取编辑记录失败" }
    }
    NovexContentSection("编辑记录", subtitle = "本卡片的保存记录；不会产生新的人生阶段或平行版本") {
        NovexSummaryRow("当前版本", if (revisions.isEmpty()) "尚无编辑记录" else "已保存 ${revisions.size} 次修订",
            onClick = { choose = true })
    }
    if (choose) NovexSearchableSelectionSheet("编辑记录", revisions.asReversed().map { revision ->
        NovexSelectionAction("修订 ${revision.sequence}", description = DateFormat.getDateTimeInstance().format(Date(revision.savedAt))) {
            choose = false; selected = revision
        }
    }, "搜索修订或时间", onDismissRequest = { choose = false })
    selected?.let { revision ->
        val previous = revisions.lastOrNull { it.sequence < revision.sequence }
        var showDifference by remember(subject, revision.sequence) { mutableStateOf(previous != null) }
        val difference = remember(previous, revision) {
            previous?.let { NovexRevisionDifference.compare(it.contentJson, revision.contentJson).joinToString("\n\n") { change ->
                "${change.path}\n原值：${change.before ?: "（不存在）"}\n现值：${change.after ?: "（不存在）"}"
            }.ifBlank { "内容相同" } }
        }
        NovexContentDialog("修订 ${revision.sequence}", onDismiss = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } }) {
            Text("保存时的资料、模块和引用；图片仅记录内容摘要，不提供已删除图片的恢复。", color = NovexColors.SecondaryText)
            if (previous != null) TextButton(onClick = { showDifference = !showDifference }) {
                Text(if(showDifference) "查看完整保存内容" else "与修订 ${previous.sequence} 比较")
            }
            SelectionContainer {
                Text(if(showDifference && difference != null) difference else JSONObject(revision.contentJson).toString(2), modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            }
        }
    }
    error?.let { NovexNoticeDialog("编辑记录", it) { error = null } }
}
