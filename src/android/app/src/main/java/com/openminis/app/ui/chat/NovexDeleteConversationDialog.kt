package com.openminis.app.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.MinisApp
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NovexDeleteConversationDialog(conversationId: String, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val application = LocalContext.current.applicationContext as MinisApp
    val scope = rememberCoroutineScope()
    var deleting by remember(conversationId) { mutableStateOf(false) }
    var error by remember(conversationId) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("删除对话") },
        text = { Text(error ?: if (deleting) "正在停止任务并保存成果…" else
            "删除这段对话及其消息。正在运行和等待批准的操作会停止；已保存的卡片和创作文件保留在仓库。") },
        confirmButton = {
            TextButton(enabled = !deleting, onClick = {
                deleting = true
                error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { application.conversationDeletion.delete(conversationId) }
                        onDeleted()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        error = "删除未完成，对话仍可重试。${failure.message.orEmpty()}"
                    } finally { deleting = false }
                }
            }) { Text(if (deleting) "正在删除…" else "删除对话") }
        },
        dismissButton = { TextButton(enabled = !deleting, onClick = onDismiss) { Text("取消") } },
    )
}
