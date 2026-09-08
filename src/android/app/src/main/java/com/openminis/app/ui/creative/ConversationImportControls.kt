package com.openminis.app.ui.creative

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.ui.novex.*

@Composable
internal fun ConversationImportControls(conversationId: String) {
    val app = LocalContext.current.applicationContext as MinisApp
    val importer = app.conversationRepositoryImporter
    val status by remember(conversationId) { importer.status(conversationId) }.collectAsState()
    var showChoices by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { values ->
        if (values.isNotEmpty()) importer.start(conversationId, files = values)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { value ->
        if (value != null) importer.start(conversationId, folder = value)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = NovexDimensions.PageHorizontal)) {
        NovexSettingsCustomRow(title = if (status.running) "正在导入资料" else "导入资料",
            subtitle = if (status.running) "已新增 ${status.saved} 个 · ${status.current.substringAfterLast('/')}" else "存入本对话，人工智能按需读取",
            onClick = if (status.running) null else ({ showChoices = true }),
            trailing = if (status.running) ({ NovexTopTextAction(if (status.stopping) "正在停止" else "停止", onClick = { importer.stop(conversationId) }) }) else null,
            showDivider = false)
        if (!status.running && status.message.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(status.message, color = NovexColors.SecondaryText, style = NovexType.Metadata,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(bottom = 6.dp))
                if (status.issues.isNotEmpty()) NovexTopTextAction("查看提示", onClick = { showIssues = true })
            }
        }
    }
    if (showChoices) NovexSelectionSheet("导入资料", listOf(
        NovexSelectionAction("选择文件", R.drawable.ic_phosphor_arrow_up, description = "可一次选择多个文件") { showChoices = false; files.launch(arrayOf("*/*")) },
        NovexSelectionAction("选择文件夹", description = "保留文件夹层级，导入其中的文件") { showChoices = false; folder.launch(null) },
    ), onDismissRequest = { showChoices = false })
    if (showIssues) NovexSearchableSelectionSheet("导入提示", status.issues.map {
        NovexSelectionAction(it.substringBefore('：'), description = it.substringAfter('：')) { }
    }, searchPlaceholder = "查找提示", onDismissRequest = { showIssues = false })
}
