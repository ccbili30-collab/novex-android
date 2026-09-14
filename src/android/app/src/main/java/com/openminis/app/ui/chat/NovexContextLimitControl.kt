package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.novex.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.openminis.app.novex.domain.NovexConversationContextLimit
import com.openminis.app.ui.novex.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun NovexContextLimitControl(
    maximum: Int?,
    effective: Int?,
    enabled: Boolean,
    onSave: suspend (Int) -> Unit,
) {
    if (maximum == null || effective == null) {
        Text("选择模型后可调整本对话上下文容量")
        return
    }
    val minimum = NovexConversationContextLimit.minimum(maximum)
    var selected by remember(maximum, effective) { mutableIntStateOf(effective.coerceIn(minimum, maximum)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column {
        Text("本对话容量：${selected.toLocaleTokens()} 词元")
        if (minimum < maximum) Slider(
            value = selected.toFloat(),
            onValueChange = { selected = it.roundToInt().coerceIn(minimum, maximum); error = null },
            valueRange = minimum.toFloat()..maximum.toFloat(),
            enabled = enabled && !saving,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最低 ${minimum.toLocaleTokens()}", style = MaterialTheme.typography.bodySmall)
            Text("模型上限 ${maximum.toLocaleTokens()}", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(enabled = enabled && !saving && selected != effective, onClick = {
            saving = true
            scope.launch {
                try { onSave(selected); error = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "容量尚未保存" }
                finally { saving = false }
            }
        }) { Text(if (saving) "正在保存" else "应用到本对话") }
        if (!enabled) Text("本轮回答结束后可调整", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun Int.toLocaleTokens(): String = java.text.NumberFormat.getIntegerInstance().format(this)
