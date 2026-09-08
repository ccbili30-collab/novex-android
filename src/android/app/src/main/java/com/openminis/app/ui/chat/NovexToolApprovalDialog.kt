package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Checkbox
import androidx.compose.material3.Text
import com.openminis.app.ui.novex.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.NovexToolOperation
import org.json.JSONObject

/** The checkbox approves this immutable operation only, never a tool category or future calls. */
@Composable
internal fun NovexToolApprovalDialog(operation: NovexToolOperation, onApprove: () -> Unit, onReject: () -> Unit) {
    var checked by remember(operation.id, operation.fingerprint) { mutableStateOf(false) }
    var details by remember(operation.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(operation.title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("是否执行这次操作？")
                if (operation.reviewDetails.isNotBlank()) Text(operation.reviewDetails)
                val args = remember(operation) { JSONObject(operation.arguments) }
                listOf("name" to "名称", "path" to "文件", "file_path" to "文件", "command" to "命令",
                    "text" to "写入内容", "url" to "地址", "query" to "查找内容").forEach { (key, label) ->
                    if (args.has(key)) Text("$label：${args.optString(key)}")
                }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起操作详情" else "查看完整操作") }
                if (details) Text(args.toString(2))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it; if (it) onApprove() })
                    Text("同意执行这一次")
                }
            }
        },
        confirmButton = { TextButton(onClick = onApprove) { Text("执行") } },
        dismissButton = { TextButton(onClick = onReject) { Text("拒绝") } },
    )
}
