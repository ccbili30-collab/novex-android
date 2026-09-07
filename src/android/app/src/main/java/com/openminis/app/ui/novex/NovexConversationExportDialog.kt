package com.openminis.app.ui.novex

import android.content.Intent
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

@Composable
internal fun NovexConversationExportDialog(state: NovexConversationExportState, onDismiss: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    var shareError by remember(state.result) { mutableStateOf<String?>(null) }
    NovexContentDialog("导出对话包 · 预览测试", onDismiss = onDismiss, confirmButton = {
        state.result?.let { result -> TextButton(onClick = {
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
            Text("已打包 ${result.messageCount} 条原始消息、${result.fileCount} 个文件条目，约 ${result.file.length() / 1024} 千字节。")
            Text("历史应用装配记录：${result.traceCount} 份。原话包含全部已保存分支、工具内容与用户文件；采用快照和导出时的共享原卡分别保存。")
            Text("历史上未记录的环境不能补造。包内清单说明范围与缺项；本测试功能暂不提供一键导入恢复。账户凭据不收录，用户原话不作替换。", color = NovexColors.SecondaryText)
            if(result.missing.isNotEmpty()) Text("存在 ${result.missing.size} 项缺失或修订不一致：\n" + result.missing.joinToString("\n"),
                modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
        }
        state.error?.let { Text(it) }
        shareError?.let { Text(it) }
    }
}
