package com.openminis.app.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionTextField
import novex.android.data.chat.SessionFolderRow
import novex.android.ui.ModalBottomSheet
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType

/**
 * 分组选择结果。「移出分组」是独立具名选择而非 null——归进空和没选是两回事。
 */
sealed interface GroupChoice {
    data class Existing(val folderId: String) : GroupChoice
    data class Create(val name: String, val description: String?) : GroupChoice

    /** 「不分组」——把会话从当前组摘出。 */
    data object RemoveFromGroup : GroupChoice
}

/**
 * 会话归组面板。单会话菜单和多选工具条共用同一张面板，两条入口不会漂移。
 *
 * @param sessionCount 归档的会话数，决定标题。
 * @param anyFiled 至少一个会话当前有组时才给「不分组」行——对本来就无组的
 *   会话它是无效控件，摆出来像坏了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerSheet(
    folders: List<SessionFolderRow>,
    memberCounts: Map<String, Int>,
    sessionCount: Int,
    anyFiled: Boolean,
    onChoose: (GroupChoice) -> Unit,
    onDismiss: () -> Unit,
    suggesting: Boolean = false,
    suggestFailed: Boolean = false,
    suggestion: SessionListViewModel.GroupSuggestion? = null,
    onSuggest: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }

    // Create 型建议只预填表单等用户点「创建」，绝不直接归档。
    val createSuggestion = suggestion as? SessionListViewModel.GroupSuggestion.Create
    LaunchedEffect(createSuggestion) {
        createSuggestion?.let {
            newName = it.name
            it.description?.let { d -> newDesc = d.take(SessionFolderRow.DESCRIPTION_MAX_CHARS) }
        }
    }

    val trimmedName = newName.trim()
    // 大小写/空白不敏感查重：「Work」和「work 」撞名时不再造一个双胞胎组，
    // 而是把同名组提示成捷径行。
    val duplicate = remember(trimmedName, folders) {
        trimmedName.takeIf { it.isNotEmpty() }
            ?.let { n -> folders.firstOrNull { it.name.trim().equals(n, ignoreCase = true) } }
    }

    val title = when {
        sessionCount > 1 && anyFiled -> stringResource(R.string.group_picker_title_change_n, sessionCount)
        sessionCount > 1 -> stringResource(R.string.group_picker_title_move_n, sessionCount)
        anyFiled -> stringResource(R.string.group_picker_title_change)
        else -> stringResource(R.string.group_picker_title_move)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                title,
                style = NovexType.PageTitle,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )

            NewGroupFields(
                name = newName,
                onNameChange = { newName = it },
                desc = newDesc,
                onDescChange = { newDesc = it.take(SessionFolderRow.DESCRIPTION_MAX_CHARS) },
            )

            duplicate?.let { dup ->
                HintRow(
                    icon = NovexIcons.Warning,
                    tint = NovexColors.Danger,
                    title = stringResource(R.string.group_duplicate_exists, dup.name),
                    subtitle = stringResource(R.string.group_duplicate_hint),
                    onClick = { onChoose(GroupChoice.Existing(dup.id)) },
                )
            }

            // Merge 型建议不经过表单（没有要建的东西），独立确认行一点即归档。
            (suggestion as? SessionListViewModel.GroupSuggestion.Merge)?.let { merge ->
                HintRow(
                    icon = NovexIcons.AutoAwesome,
                    tint = NovexColors.Primary,
                    title = stringResource(R.string.group_suggest_merge, merge.folderName),
                    onClick = { onChoose(GroupChoice.Existing(merge.folderId)) },
                )
            }

            CreateBar(
                suggesting = suggesting,
                suggestFailed = suggestFailed,
                onSuggest = onSuggest,
                createEnabled = trimmedName.isNotEmpty() && duplicate == null,
                onCreate = {
                    onChoose(
                        GroupChoice.Create(
                            name = trimmedName,
                            description = newDesc.trim().ifBlank { null },
                        ),
                    )
                },
            )

            if (folders.isNotEmpty() || anyFiled) {
                Text(
                    stringResource(R.string.group_section_header),
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (anyFiled) {
                        item(key = "no_group") {
                            val label = stringResource(R.string.group_none)
                            val hint = stringResource(R.string.group_none_subtitle)
                            GroupRow(
                                title = label,
                                subtitle = hint,
                                icon = NovexIcons.FolderOff,
                                onClick = { onChoose(GroupChoice.RemoveFromGroup) },
                                // 读屏时合并两行，分开播报会像两段无关碎片。
                                modifier = Modifier.semantics(mergeDescendants = true) {
                                    contentDescription = label
                                    stateDescription = hint
                                },
                            )
                        }
                    }
                    items(folders, key = { it.id }) { folder ->
                        val count = memberCounts[folder.id] ?: 0
                        GroupRow(
                            title = folder.name,
                            subtitle = folder.description?.takeIf { it.isNotBlank() }
                                ?: if (count > 0) stringResource(R.string.group_n_chats, count)
                                else stringResource(R.string.group_empty),
                            icon = NovexIcons.Folder,
                            onClick = { onChoose(GroupChoice.Existing(folder.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewGroupFields(
    name: String,
    onNameChange: (String) -> Unit,
    desc: String,
    onDescChange: (String) -> Unit,
) {
    Column {
        SectionTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = stringResource(R.string.group_new_name_hint),
            modifier = Modifier.padding(horizontal = 20.dp),
            containerColor = SectionDesign.screenBackgroundColor(),
        )
        Spacer(Modifier.height(8.dp))
        SectionTextField(
            value = desc,
            onValueChange = onDescChange,
            placeholder = stringResource(R.string.group_desc_hint),
            modifier = Modifier.padding(horizontal = 20.dp),
            containerColor = SectionDesign.screenBackgroundColor(),
        )
    }
}

/** 提示型行（重名捷径 / 合并建议）：图标+主文案+可选副文案，整行可点。 */
@Composable
private fun HintRow(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = NovexType.ItemTitle)
            subtitle?.let {
                Text(it, style = NovexType.Metadata, color = NovexColors.SecondaryText)
            }
        }
    }
}

/** 底部行：AI 建议按钮在前，「创建」在后，两个独立点击区。 */
@Composable
private fun CreateBar(
    suggesting: Boolean,
    suggestFailed: Boolean,
    onSuggest: (() -> Unit)?,
    createEnabled: Boolean,
    onCreate: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onSuggest != null) {
            Row(
                Modifier
                    .clickable(enabled = !suggesting) { onSuggest() }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (suggesting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        NovexIcons.AutoAwesome,
                        contentDescription = null,
                        tint = NovexColors.Primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        if (suggestFailed) R.string.group_suggest_failed else R.string.group_suggest,
                    ),
                    style = NovexType.ItemTitle,
                    color = NovexColors.Primary,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        MinisSmallButton(onClick = onCreate, enabled = createEnabled) {
            Text(stringResource(R.string.group_create))
        }
    }
}

@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = NovexColors.SecondaryText,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(16.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = NovexType.ItemTitle)
            Text(subtitle, style = NovexType.Metadata, color = NovexColors.SecondaryText)
        }
    }
}
