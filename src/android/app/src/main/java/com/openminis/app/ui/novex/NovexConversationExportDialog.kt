package com.openminis.app.ui.novex

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.share.NovexConversationExportState
import com.openminis.app.share.NovexConversationBundleFileSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NovexConversationExportDialog(state: NovexConversationExportState, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    var shareError by remember(state.result) { mutableStateOf<String?>(null) }
    var pendingFile by remember { mutableStateOf<java.io.File?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember(state.result) { mutableStateOf<String?>(null) }
    var showDetails by remember(state.result) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = pendingFile
        pendingFile = null
        if (uri == null || source == null) {
            saving = false
        } else scope.launch {
            try {
                NovexConversationBundleFileSaver.save(context.contentResolver, source, uri)
                saveMessage = "对话包已保存到所选位置"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { shareError = failure.message ?: "未能完整保存，请重试" }
            finally { saving = false }
        }
    }
    NovexContentDialog("导出对话包 · 预览测试", onDismiss = onDismiss, confirmButton = {
        state.result?.let { result ->
            TextButton(enabled = !saving, onClick = {
                pendingFile = result.file
                saving = true
                saveMessage = null
                shareError = null
                try { saveFile.launch("对话包-${java.time.LocalDate.now()}.zip") }
                catch (failure: Exception) {
                    pendingFile = null; saving = false
                    shareError = failure.message ?: "无法打开文件保存界面"
                }
            }) { Text("保存到文件") }
            TextButton(enabled = !saving, onClick = {
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", result.file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "分享原话与已保存环境"))
            } catch(failure: Exception) { shareError = failure.message ?: "无法打开分享界面，导出包已保留" }
        }) { Text("分享这个包") } }
        if(state.error != null) TextButton(onClick = onRetry) { Text("重新导出") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(if(state.busy) "取消导出" else "关闭") } }) {
        if(state.busy) Text("正在保存原始消息、分支、卡片快照和本对话文件…")
        state.result?.let { result ->
            Text("已打包 ${result.messageCount} 条原始消息、${result.fileCount} 个文件，约 ${(result.file.length() + 1023) / 1024} 千字节。")
            Text("包含对话原话、已保存环境和附件。暂不支持把对话包导回软件。", color = NovexColors.SecondaryText)
            if (result.missing.isNotEmpty()) Text("有 ${result.missing.size} 项未能完整收录，请查看缺项。")
            TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "收起导出范围" else "导出范围与缺项") }
            if (showDetails) {
                Text("包含全部已保存分支与工具记录；采用快照和导出时的共享原卡分别保存。历史请求环境记录 ${result.traceCount} 份。")
                Text("历史上未记录的环境不能补造。包内清单说明范围与缺项；账户凭据不收录，用户原话不作替换。", color = NovexColors.SecondaryText)
                if (result.missing.isNotEmpty()) Text(result.missing.joinToString("\n"),
                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
            }
        }
        state.error?.let { Text(it) }
        shareError?.let { Text(it) }
        if (saving) Text("正在保存对话包…")
        saveMessage?.let { Text(it) }
    }
}
