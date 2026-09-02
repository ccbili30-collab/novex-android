package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleRepository
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.ui.novex.NovexContentModuleSummary
import com.openminis.app.ui.novex.toNovexPresentation
import kotlinx.coroutines.launch

@Composable
internal fun SharedContentModuleEditor(
    owner: ModuleOwner,
    header: String,
    footer: String,
    onOpenModule: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as MinisApp
    val repository = remember(app) { ContentModuleRepository(app.database.contentModuleDao()) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var modules by remember { mutableStateOf<List<ContentModuleEntity>>(emptyList()) }
    var moduleImages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var addModule by remember { mutableStateOf(false) }
    var arranging by remember { mutableStateOf(false) }
    var deleteModule by remember { mutableStateOf<ContentModuleEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val moduleScope = remember(owner.type) {
        requireNotNull(ContentModuleCatalog.scopeFor(owner.type)) { "该对象不能拥有内容模块" }
    }
    val mediaRepository = rememberMediaRepository(app)
    LaunchedEffect(owner, refresh) {
        modules = repository.list(owner)
        moduleImages = modules.mapNotNull { module ->
            mediaRepository.assetFor(ModuleOwner.contentModule(module.id), MediaAssetSlot.MODULE_IMAGE)
                ?.managedPath?.let { module.id to it }
        }.toMap()
    }

    SettingsSection(header = header, footer = footer) {
        if (modules.isNotEmpty()) Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            TextButton(onClick = { arranging = !arranging }) { Text(if (arranging) "完成排列" else "排列与删除") }
        }
        modules.forEachIndexed { index, module ->
            SharedModuleSummaryRow(
                module = module,
                imagePath = moduleImages[module.id],
                canMoveUp = index > 0,
                canMoveDown = index < modules.lastIndex,
                arranging = arranging,
                onOpen = { onOpenModule(module.id) },
                onMove = { delta -> scope.launch { repository.move(module.id, index + delta); refresh++ } },
                onDelete = { deleteModule = module },
            )
            if (index < modules.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        }
        TextButton(onClick = { addModule = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("添加模块")
        }
    }
    if (addModule) AlertDialog(
        onDismissRequest = { addModule = false },
        title = { Text("添加模块") },
        text = { Column { ContentModuleCatalog.availableToAdd(
            scope = moduleScope,
            existingTypes = modules.map(ContentModuleEntity::type),
        ).forEach { definition ->
            TextButton(
                onClick = {
                    addModule = false
                    scope.launch {
                        runCatching {
                            repository.add(owner, definition.type, definition.displayName)
                        }
                            .onSuccess { created -> onOpenModule(created.id) }
                            .onFailure { error = it.message }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(definition.displayName) }
        } } },
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
                        mediaRepository.removeAll(ModuleOwner.contentModule(module.id))
                        repository.delete(module.id)
                        refresh++
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
private fun SharedModuleSummaryRow(
    module: ContentModuleEntity,
    imagePath: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    arranging: Boolean,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !arranging, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovexContentModuleSummary(
            presentation = module.toNovexPresentation(),
            imageModel = imagePath.existingMediaFile(),
            modifier = Modifier.weight(1f),
        )
        if (arranging) {
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除模块") }
        } else {
            Icon(Icons.Default.ChevronRight, contentDescription = "打开模块")
        }
    }
}
