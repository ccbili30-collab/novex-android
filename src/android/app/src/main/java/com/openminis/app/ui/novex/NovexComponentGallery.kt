package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/** Product components with local demo state only; never writes real user content. */
@Composable
internal fun NovexComponentGallery(onBack: () -> Unit) {
    var overlay by rememberSaveable { mutableStateOf("") }
    var checked by rememberSaveable { mutableStateOf(true) }
    var name by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf("0")) }
    var validation by rememberSaveable { mutableStateOf(false) }
    NovexSettingsScaffold("组件预览", onBack) {
        Text("仅演示界面状态，不修改你的世界、角色或对话。字体与颜色跟随外观设置。",
            style = NovexType.Metadata, color = NovexColors.SecondaryText, modifier = Modifier.padding(16.dp))
        NovexSettingsSection(title = "列表与选择") {
            NovexSettingsVectorToggleRow("勾选开关", "点整行或勾选区域均可切换", checked = checked, onCheckedChange = { checked = it })
            NovexSettingsRow(R.drawable.ic_phosphor_palette, "搜索、分组和多选", "包括禁用选项及原因", onClick = { overlay = "selection" })
            NovexSettingsVectorToggleRow("暂不可用", "演示禁用状态", checked = false, enabled = false, onCheckedChange = {})
        }
        NovexSettingsSection(title = "表单与反馈", footer = saved.ifBlank { "打开表单后尝试留空保存，查看错误提示。" }) {
            NovexSettingsRow(R.drawable.ic_phosphor_pencil_simple, "表单弹层", "多字段、长文本、错误反馈", onClick = { validation = false; overlay = "form" })
            NovexSettingsRow(R.drawable.ic_phosphor_info, "普通提示", onClick = { overlay = "notice" })
            NovexSettingsRow(R.drawable.ic_phosphor_trash, "删除确认", onClick = { overlay = "delete" })
            NovexSettingsRow(R.drawable.ic_phosphor_note_pencil, "未保存退出", onClick = { overlay = "unsaved" })
        }
        NovexSettingsSection(title = "按钮与长内容") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { saved = "操作已响应" }, modifier = Modifier.fillMaxWidth()) { Text("主要操作") }
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("尚不可用") }
                Card {
                    Text("这是一个较长的模块标题，用于检查文字放大后是否自然换行",
                        style = NovexType.SectionTitle, modifier = Modifier.padding(12.dp))
                    NovexDivider()
                    Text("模块内容沿统一边界展示。内容增加时向下延伸，不用固定高度裁切正文。".repeat(4),
                        style = NovexType.Body, modifier = Modifier.padding(12.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    when (overlay) {
        "notice" -> NovexNoticeDialog("操作提示", "这是统一的提示样式。", { overlay = "" })
        "delete" -> NovexDestructiveConfirmationDialog("删除示例？", "这只是预览，不会删除真实内容。", false,
            { overlay = ""; saved = "示例删除已确认" }, { overlay = "" })
        "unsaved" -> NovexUnsavedChangesDialog(false, { overlay = ""; saved = "示例已保存" }, { overlay = "" }, { overlay = "form" })
        "form" -> AlertDialog(
            onDismissRequest = { overlay = "unsaved" },
            title = { Text("编辑示例") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true,
                        isError = validation && name.isBlank(), supportingText = {
                            if (validation && name.isBlank()) Text("请填写名称。你的输入仍保留。")
                        })
                    OutlinedTextField(notes, { notes = it }, label = { Text("说明（可留空）") }, minLines = 3, maxLines = 6)
                }
            },
            confirmButton = { TextButton(onClick = {
                validation = true
                if (name.isNotBlank()) { saved = "已保存示例：$name"; overlay = "" }
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { overlay = "unsaved" }) { Text("取消") } },
        )
        "selection" -> NovexSearchableSelectionSheet(
            title = "选择示例内容", searchPlaceholder = "搜索名称、说明或分组", dismissOnSelection = false,
            actions = (0..39).map { index ->
                val entry = NovexSelectionEntry(index.toString(), "示例 ${index + 1}", enabled = index != 3)
                NovexSelectionAction(entry.label, R.drawable.ic_phosphor_note_pencil,
                    description = "可以使用同一个选择器处理大量内容", group = if (index < 20) "世界" else "角色",
                    selected = entry.id in selected, enabled = entry.enabled, disabledReason = "内容尚未准备完成") {
                    selected = toggleNovexSelection(selected, entry)
                }
            }, onDismissRequest = { overlay = "" },
        )
    }
}
