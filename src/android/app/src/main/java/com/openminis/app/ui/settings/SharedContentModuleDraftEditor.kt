package com.openminis.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleCollectionItem
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleTimelineNode
import com.openminis.app.novex.domain.NovexModuleDraft
import com.openminis.app.ui.novex.ContentModuleDraftList
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexOutlineButton
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.novex.NovexTextField
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.novexModuleSummary
import com.openminis.app.data.character.toPlainText
import java.util.UUID

/** Controlled editor: every mutation stays in the page draft until its parent saves once. */
@Composable
internal fun SharedContentModuleDraftEditor(
    state: ContentModuleDraftList,
    persistedModuleIds: Set<String>,
    onChange: (ContentModuleDraftList) -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            "内容模块",
            color = NovexColors.Text,
            style = NovexType.SectionTitle,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        )
        Column(Modifier.fillMaxWidth().background(NovexColors.Surface)) {
            state.modules.forEachIndexed { index, module ->
                ModuleDraftRow(
                    module = module,
                    expanded = module.id in state.expandedModuleIds,
                    persisted = module.id in persistedModuleIds,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.modules.lastIndex,
                    onToggle = { onChange(state.toggle(module.id)) },
                    onUpdate = { name, document -> onChange(state.update(module.id, name, document)) },
                    onMove = { delta -> onChange(state.move(module.id, index + delta)) },
                    onDelete = { onChange(state.remove(module.id)) },
                    onOpenDetails = { onOpenDetails(module.id) },
                )
                if (index < state.modules.lastIndex) HorizontalDivider(
                    color = NovexColors.Divider,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            NovexTextActionRow(
                label = "添加模块",
                onClick = { showAdd = true },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text(
            "点击模块展开编辑；拖动排序将在保存后成为实际展示顺序。",
            color = NovexColors.SecondaryText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
    if (showAdd) AlertDialog(
        onDismissRequest = { showAdd = false },
        title = { Text("添加模块") },
        text = {
            Column {
                ContentModuleCatalog.availableToAdd(state.scope, state.modules.map { it.type }).forEach { definition ->
                    TextButton(
                        onClick = {
                            showAdd = false
                            onChange(state.add(definition.type))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(definition.displayName) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } },
    )
}

@Composable
private fun ModuleDraftRow(
    module: NovexModuleDraft,
    expanded: Boolean,
    persisted: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onUpdate: (String, ContentModuleDocument) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val document = ContentModuleDocumentCodec.decode(module.type, module.contentJson)
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_phosphor_dots_six_vertical),
                contentDescription = "${module.name}排序手柄",
                tint = NovexColors.SecondaryText,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(module.name, color = NovexColors.Text, fontWeight = FontWeight.SemiBold)
                Text(
                    novexModuleSummary(document.toPlainText()),
                    color = NovexColors.SecondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Icon(
                painterResource(R.drawable.ic_phosphor_more_vertical),
                contentDescription = if (expanded) "收起${module.name}" else "展开${module.name}",
                tint = NovexColors.SecondaryText,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 16.dp, bottom = 16.dp)) {
                NovexTextField(
                    label = "模块名称",
                    value = module.name,
                    onValueChange = { onUpdate(it, document) },
                )
                ModuleDocumentFields(
                    document = document,
                    onChange = { onUpdate(module.name, it) },
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row {
                        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                            Icon(painterResource(R.drawable.ic_phosphor_arrow_up), "上移${module.name}")
                        }
                        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                            Icon(painterResource(R.drawable.ic_phosphor_arrow_down), "下移${module.name}")
                        }
                        IconButton(onClick = onDelete) {
                            Icon(painterResource(R.drawable.ic_phosphor_trash), "删除${module.name}")
                        }
                    }
                    NovexOutlineButton(
                        label = if (persisted) "图片与引用" else "保存后添加图片",
                        onClick = onOpenDetails,
                        enabled = persisted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleDocumentFields(
    document: ContentModuleDocument,
    onChange: (ContentModuleDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        when (document) {
            is ContentModuleDocument.Article -> NovexTextField(
                label = "内容（可留空）",
                value = document.text,
                onValueChange = { onChange(document.copy(text = it)) },
                minLines = 5,
            )
            is ContentModuleDocument.SingleImage -> NovexTextField(
                label = "图片说明（可留空）",
                value = document.description,
                onValueChange = { onChange(document.copy(description = it)) },
                minLines = 3,
            )
            is ContentModuleDocument.Timeline -> TimelineFields(document, onChange)
            is ContentModuleDocument.Collection -> CollectionFields(document, onChange)
            is ContentModuleDocument.Unsupported -> Text(
                "当前版本暂不识别这个模块的内部结构；原始内容会保留，可重命名和排序。",
                color = NovexColors.SecondaryText,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TimelineFields(
    document: ContentModuleDocument.Timeline,
    onChange: (ContentModuleDocument) -> Unit,
) {
    document.nodes.forEachIndexed { index, node ->
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("节点 ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onChange(document.copy(nodes = document.nodes.filterIndexed { i, _ -> i != index }))
                }) { Text("删除") }
            }
            NovexTextField("时间", node.time, { value ->
                onChange(document.copy(nodes = document.nodes.replaceAt(index, node.copy(time = value))))
            })
            NovexTextField("标题", node.title, { value ->
                onChange(document.copy(nodes = document.nodes.replaceAt(index, node.copy(title = value))))
            }, modifier = Modifier.padding(top = 2.dp))
            NovexTextField("说明", node.description, { value ->
                onChange(document.copy(nodes = document.nodes.replaceAt(index, node.copy(description = value))))
            }, minLines = 2, modifier = Modifier.padding(top = 2.dp))
        }
    }
    TextButton(onClick = {
        onChange(document.copy(nodes = document.nodes + ContentModuleTimelineNode()))
    }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加时间节点") }
}

@Composable
private fun CollectionFields(
    document: ContentModuleDocument.Collection,
    onChange: (ContentModuleDocument) -> Unit,
) {
    document.items.forEachIndexed { index, item ->
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("条目 ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onChange(document.copy(items = document.items.filterIndexed { i, _ -> i != index }))
                }) { Text("删除") }
            }
            NovexTextField("名称", item.name, { value ->
                onChange(document.copy(items = document.items.replaceAt(index, item.copy(name = value))))
            })
            NovexTextField("摘要", item.summary, { value ->
                onChange(document.copy(items = document.items.replaceAt(index, item.copy(summary = value))))
            }, modifier = Modifier.padding(top = 2.dp))
            NovexTextField("详细说明", item.description, { value ->
                onChange(document.copy(items = document.items.replaceAt(index, item.copy(description = value))))
            }, minLines = 2, modifier = Modifier.padding(top = 2.dp))
        }
    }
    TextButton(onClick = {
        onChange(
            document.copy(
                items = document.items + ContentModuleCollectionItem(id = UUID.randomUUID().toString()),
            ),
        )
    }, modifier = Modifier.fillMaxWidth()) { Text("＋ 添加条目") }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = mapIndexed { current, item ->
    if (current == index) value else item
}
