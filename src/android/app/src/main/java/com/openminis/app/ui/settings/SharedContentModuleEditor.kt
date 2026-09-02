package com.openminis.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleTextCodec
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.requireModule
import com.openminis.app.ui.novex.ContentModuleWorkspaceState
import com.openminis.app.ui.novex.NovexContentModuleSummary
import com.openminis.app.ui.novex.rememberNovexWorkspace
import com.openminis.app.ui.novex.toNovexPresentation
import kotlinx.coroutines.launch

/** One scalable module workspace shared by world and character-version editors. */
@Composable
internal fun SharedContentModuleEditor(
    owner: ModuleOwner,
    header: String,
    footer: String,
    onOpenModule: (String) -> Unit,
) {
    val novex = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var workspace by remember(owner) {
        mutableStateOf(ContentModuleWorkspaceState.fromSaved(emptyList()))
    }
    var moduleImages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var addModule by remember { mutableStateOf(false) }
    var deleteModule by remember { mutableStateOf<ContentModuleEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val moduleScope = remember(owner.type) {
        requireNotNull(ContentModuleCatalog.scopeFor(owner.type)) { "该对象不能拥有内容模块" }
    }
    LaunchedEffect(owner, refresh) {
        val saved = novex.modules(owner)
        workspace = ContentModuleWorkspaceState.fromSaved(saved.modules)
        moduleImages = saved.images.mapValues { it.value.managedPath }
    }

    SettingsSection(header = header, footer = footer) {
        if (workspace.modules.isNotEmpty()) {
            Text(
                "点击模块即可展开；多个模块可以同时编辑。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        }
        workspace.modules.forEachIndexed { index, module ->
            SharedModuleEditorRow(
                module = module,
                imagePath = moduleImages[module.id],
                expanded = module.id in workspace.expandedModuleIds,
                canMoveUp = index > 0,
                canMoveDown = index < workspace.modules.lastIndex,
                onToggle = { workspace = workspace.toggleExpanded(module.id) },
                onSave = { name, body ->
                    novex.apply(
                        NovexCommand.SaveModule(
                            moduleId = module.id,
                            name = name,
                            contentJson = ContentModuleTextCodec.encode(body),
                        ),
                    ).requireModule()
                },
                onSaved = { saved -> workspace = workspace.replace(saved) },
                onOpenDetails = { onOpenModule(module.id) },
                onMove = { delta ->
                    val target = index + delta
                    workspace = workspace.move(module.id, target)
                    scope.launch {
                        runCatching { novex.apply(NovexCommand.MoveModule(module.id, target)) }
                            .onFailure {
                                error = it.message
                                refresh++
                            }
                    }
                },
                onDelete = { deleteModule = module },
                onError = { error = it },
            )
            if (index < workspace.modules.lastIndex) {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            }
        }
        TextButton(
            onClick = { addModule = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Icon(painterResource(R.drawable.ic_phosphor_plus), contentDescription = null)
            Text("添加模块", modifier = Modifier.padding(start = 6.dp))
        }
    }

    if (addModule) AlertDialog(
        onDismissRequest = { addModule = false },
        title = { Text("添加模块") },
        text = {
            Column {
                ContentModuleCatalog.availableToAdd(
                    scope = moduleScope,
                    existingTypes = workspace.modules.map(ContentModuleEntity::type),
                ).forEach { definition ->
                    TextButton(
                        onClick = {
                            addModule = false
                            scope.launch {
                                runCatching {
                                    novex.apply(
                                        NovexCommand.AddModule(owner, definition.type, definition.displayName),
                                    ).requireModule()
                                }.onSuccess { created ->
                                    workspace = workspace.add(created)
                                }.onFailure { error = it.message }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(definition.displayName) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { addModule = false }) { Text("取消") } },
    )

    deleteModule?.let { module ->
        AlertDialog(
            onDismissRequest = { deleteModule = null },
            title = { Text("删除${module.name}？") },
            text = { Text("模块内容和它的代表图引用会被移除；仍被其他对象使用的图片文件不会误删。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteModule = null
                    scope.launch {
                        runCatching {
                            novex.apply(NovexCommand.DeleteModule(module.id))
                        }.onSuccess {
                            workspace = workspace.remove(module.id)
                            moduleImages = moduleImages - module.id
                        }.onFailure { error = it.message }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteModule = null }) { Text("取消") } },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("操作失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun SharedModuleEditorRow(
    module: ContentModuleEntity,
    imagePath: String?,
    expanded: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onSave: suspend (String, String) -> ContentModuleEntity,
    onSaved: (ContentModuleEntity) -> Unit,
    onOpenDetails: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onError: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_phosphor_dots_six_vertical),
                contentDescription = "${module.name}排序手柄",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            NovexContentModuleSummary(
                presentation = module.toNovexPresentation(),
                imageModel = imagePath.existingMediaFile(),
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Icon(
                painterResource(R.drawable.ic_phosphor_caret_right),
                contentDescription = if (expanded) "收起${module.name}" else "展开${module.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).rotate(if (expanded) 90f else 0f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            InlineModuleEditor(
                module = module,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onSave = onSave,
                onSaved = onSaved,
                onOpenDetails = onOpenDetails,
                onMove = onMove,
                onDelete = onDelete,
                onError = onError,
            )
        }
    }
}

@Composable
private fun InlineModuleEditor(
    module: ContentModuleEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSave: suspend (String, String) -> ContentModuleEntity,
    onSaved: (ContentModuleEntity) -> Unit,
    onOpenDetails: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(module.id, module.name) { mutableStateOf(module.name) }
    var body by remember(module.id, module.contentJson) {
        mutableStateOf(ContentModuleTextCodec.decode(module.contentJson))
    }
    var saving by remember(module.id) { mutableStateOf(false) }
    val changed = name.trim() != module.name || body != ContentModuleTextCodec.decode(module.contentJson)

    Column(Modifier.fillMaxWidth().padding(start = 38.dp, end = 12.dp, bottom = 14.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("模块名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("内容（可选）") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(painterResource(R.drawable.ic_phosphor_arrow_up), contentDescription = "上移${module.name}")
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(painterResource(R.drawable.ic_phosphor_arrow_down), contentDescription = "下移${module.name}")
                }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(R.drawable.ic_phosphor_trash), contentDescription = "删除${module.name}")
                }
            }
            OutlinedButton(onClick = onOpenDetails) {
                Text("图片与引用")
            }
        }
        Button(
            onClick = {
                saving = true
                scope.launch {
                    runCatching { onSave(name, body) }
                        .onSuccess {
                            onSaved(it)
                            saving = false
                        }
                        .onFailure {
                            saving = false
                            onError(it.message ?: "保存失败")
                        }
                }
            },
            enabled = name.isNotBlank() && changed && !saving,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(if (saving) "保存中" else if (changed) "保存模块" else "已保存")
        }
    }
}
