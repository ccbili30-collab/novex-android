package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.NovexGamePlayerChoices
import com.openminis.app.ui.novex.NovexContentDialog
import com.openminis.app.ui.novex.TextButton

@Composable
internal fun NovexGameEntryDialog(
    state: NovexGameEntryState,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    if (state == NovexGameEntryState.Ready) return
    NovexContentDialog(
        title = when (state) {
            is NovexGameEntryState.ChoosePlayer -> "选择本局玩家身份"
            is NovexGameEntryState.Failed -> "文游尚未启动"
            else -> "正在准备文游"
        },
        onDismiss = onBack,
        confirmButton = {
            if (state is NovexGameEntryState.Failed) TextButton(onClick = onRetry) { Text("重新尝试") }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("返回卡片") } },
    ) {
        when (state) {
            is NovexGameEntryState.ChoosePlayer -> Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NovexGamePlayerChoices.read(state.game).forEach { identity ->
                    TextButton(onClick = { onSelect(identity.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(identity.label)
                            if (identity.description.isNotBlank()) Text(identity.description)
                        }
                    }
                }
            }
            is NovexGameEntryState.Failed -> Text(state.message)
            else -> Text("正在读取卡片并准备本局资料。")
        }
    }
}
