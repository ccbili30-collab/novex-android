package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import com.openminis.app.R
import com.openminis.app.novex.domain.AnswerIdentity
import com.openminis.app.novex.domain.ManagedAccess
import com.openminis.app.novex.domain.NovexContentKind
import com.openminis.app.ui.novex.NovexSearchableSelectionSheet
import com.openminis.app.ui.novex.NovexSelectionAction
import com.openminis.app.ui.novex.NovexSelectionSheet

internal enum class ConversationPicker { ANSWER, ROLE, BACKGROUND, GAME, GAME_REFERENCE, MANAGED, BOTH }

internal fun pickerActions(
    picker: ConversationPicker,
    options: List<ConversationContentOption>,
    draft: NovexConversationEditorDraftState,
    onChooseRole: () -> Unit,
    onRoleSelected: (String) -> Unit,
    onGameSelected: (String) -> Unit,
    update: (NovexConversationEditorDraftState) -> Unit,
): List<NovexSelectionAction> = when (picker) {
    ConversationPicker.ANSWER -> listOf(
        NovexSelectionAction("Nova（诺瓦）", R.drawable.ic_phosphor_sparkle) {
            update(draft.setAnswerIdentity(AnswerIdentity.Nova))
        },
        NovexSelectionAction("自定义", R.drawable.ic_phosphor_note_pencil) {
            update(draft.setAnswerIdentity((draft.configuration.answerIdentity as? AnswerIdentity.PersonaPreset)
                ?: AnswerIdentity.PersonaPreset("custom:${java.util.UUID.randomUUID()}", "自定义")))
        },
        NovexSelectionAction("选择角色", R.drawable.ic_phosphor_puzzle_piece, onClick = onChooseRole),
    )
    ConversationPicker.ROLE -> options.filter { it.address.kind == NovexContentKind.CHARACTER_VERSION }.map { option ->
        NovexSelectionAction(option.label, selected = draft.configuration.answerIdentity == AnswerIdentity.CharacterVersion(option.address.id)) {
            onRoleSelected(option.address.id)
        }
    }
    ConversationPicker.BACKGROUND -> options
        .filter { it.address.kind == NovexContentKind.WORLD || it.address.kind == NovexContentKind.CHARACTER_VERSION }
        .filterNot { option -> draft.configuration.backgroundSettings.any { it.subject == option.address } }
        .map { option ->
            NovexSelectionAction("${option.kindLabel} · ${option.label}", description = "用于背景；不改变回答身份，不授予编辑权限") {
                update(draft.addBackground(option.address))
            }
        }
    ConversationPicker.GAME -> options.filter { it.address.kind == NovexContentKind.INTERACTIVE_FICTION }.map { option ->
        NovexSelectionAction(option.label, R.drawable.ic_phosphor_puzzle_piece) {
            onGameSelected(option.address.id)
        }
    }
    ConversationPicker.GAME_REFERENCE -> options.filter { it.address.kind == NovexContentKind.INTERACTIVE_FICTION }
        .filterNot { option -> draft.configuration.managedSubjects.any { it.subject == option.address } }.map { option ->
            NovexSelectionAction(option.label, description = "只读挂载，可按需查看；不启动、不改回答身份、不授予编辑权限") {
                update(draft.mount(option.address, ManagedAccess.READ_ONLY))
            }
        }
    ConversationPicker.MANAGED -> options
        .filterNot { option -> draft.configuration.managedSubjects.any { it.subject == option.address } }
        .map { option ->
            NovexSelectionAction("${option.kindLabel} · ${option.label}", description = "允许管理原件；不自动采用正文、不切换身份、不启动文游，可挂载多张") {
                update(draft.mount(option.address, ManagedAccess.EDIT))
            }
        }
    ConversationPicker.BOTH -> options
        .filter { it.address.kind == NovexContentKind.WORLD || it.address.kind == NovexContentKind.CHARACTER_VERSION }
        .filterNot { option -> draft.configuration.backgroundSettings.any { it.subject == option.address } &&
            draft.configuration.managedSubjects.any { it.subject == option.address && it.access == ManagedAccess.EDIT } }
        .map { option ->
            NovexSelectionAction("${option.kindLabel} · ${option.label}", description = "采用背景并允许管理原件；后续编辑不自动刷新采用内容") {
                update(draft.useAndManage(option.address))
            }
        }
}

internal fun ConversationPicker.pickerTitle(): String = when (this) {
    ConversationPicker.ANSWER -> "选择回答身份"
    ConversationPicker.ROLE -> "选择扮演的角色"
    ConversationPicker.BACKGROUND -> "添加背景设定"
    ConversationPicker.GAME -> "选择活动文游"
    ConversationPicker.GAME_REFERENCE -> "选择文游资料"
    ConversationPicker.MANAGED -> "选择管理内容"
    ConversationPicker.BOTH -> "同时使用与管理世界或角色"
}


@Composable
internal fun ConversationSelectionSheet(picker: ConversationPicker, title: String,
    actions: List<NovexSelectionAction>, onDismissRequest: () -> Unit) {
    if (picker == ConversationPicker.ANSWER) NovexSelectionSheet(title, actions, onDismissRequest)
    else NovexSearchableSelectionSheet(title, actions, "按卡片名称或角色版本查找", onDismissRequest)
}
