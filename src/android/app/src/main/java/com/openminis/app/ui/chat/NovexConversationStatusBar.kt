package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.NovexSelectionAction
import com.openminis.app.ui.novex.NovexSelectionSheet
import com.openminis.app.ui.novex.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class NovexConversationStatus(
    val answer: String = "Nova（诺瓦）",
    val game: String? = null,
    val sources: Int = 0,
    val mode: NovexExecutionMode = NovexExecutionMode.DEFAULT,
    val editable: Boolean = true,
) {
    companion object {
        fun read(configuration: NovexConversationConfigurationSnapshot): NovexConversationStatus {
            com.openminis.app.cards.CardBinding.decode(configuration.cardBindingJson)?.let {binding->
                return NovexConversationStatus(if(binding.primary!=null)"已采用卡片" else "Nova（诺瓦）",null,
                    (listOfNotNull(binding.primary)+binding.backgrounds).distinct().size,configuration.executionMode,configuration.unreadableConfiguration==null)
            }
            val sources = NovexEffectiveFrozenContext.sources(configuration)
            val answer = when (val identity = configuration.answerIdentity) {
                AnswerIdentity.Nova -> "Nova（诺瓦）"
                is AnswerIdentity.PersonaPreset -> identity.label
                is AnswerIdentity.CharacterVersion -> sources.firstOrNull { it.actorVersionId == identity.versionId }
                    ?.candidates?.firstOrNull { it.kind == ContextSourceKind.ANSWER_IDENTITY }?.label ?: "已采用的角色"
            }
            return NovexConversationStatus(answer, configuration.activeInteractiveFiction?.title,
                sources.filter { source -> source.candidates.any { it.content.isNotBlank() } }.distinctBy { it.target }.size,
                configuration.executionMode, configuration.unreadableConfiguration == null)
        }
    }
}

@Composable
internal fun NovexConversationStatusBar(
    status: NovexConversationStatus,
    enabled: Boolean,
    onSettings: () -> Unit,
    onPermission: suspend (NovexExecutionMode) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onSettings, modifier = Modifier.weight(1f)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("回答：${status.answer}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (status.game != null || status.sources > 0) Text(
                        listOfNotNull(status.game?.let { "文游：$it" },
                            "可用资料 ${status.sources} 项".takeIf { status.sources > 0 }).joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(enabled = enabled && status.editable && !saving, onClick = { choosing = true }) {
                Text("工具：${status.mode.label}")
            }
        }
        error?.let { Text(it) }
    }
    if (choosing) NovexSelectionSheet(
        title = "工具权限",
        actions = NovexExecutionMode.entries.map { mode ->
            NovexSelectionAction(mode.label, description = mode.description, selected = status.mode == mode) {
                saving = true
                scope.launch {
                    try { onPermission(mode); error = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "权限尚未保存" }
                    finally { saving = false }
                }
            }
        },
        onDismissRequest = { choosing = false },
    )
}
