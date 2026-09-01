package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleRepository
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun SharedContentModuleEditor(
    owner: ModuleOwner,
    header: String,
    footer: String,
    allowedTypes: List<ContentModuleType>,
    typeName: (ContentModuleType) -> String,
) {
    val app = LocalContext.current.applicationContext as MinisApp
    val repository = remember(app) { ContentModuleRepository(app.database.contentModuleDao()) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var modules by remember { mutableStateOf<List<ContentModuleEntity>>(emptyList()) }
    var addModule by remember { mutableStateOf(false) }
    var renameModule by remember { mutableStateOf<ContentModuleEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var editModule by remember { mutableStateOf<ContentModuleEntity?>(null) }
    var editText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(owner, refresh) { modules = repository.list(owner) }

    SettingsSection(header = header, footer = footer) {
        modules.forEachIndexed { index, module ->
            SharedModuleRow(
                module = module,
                canMoveUp = index > 0,
                canMoveDown = index < modules.lastIndex,
                onToggle = {
                    scope.launch { repository.setCollapsed(module.id, !module.collapsed); refresh++ }
                },
                onEdit = { editModule = module; editText = decodeWorldModuleText(module.contentJson) },
                onRename = { renameModule = module; renameText = module.name },
                onMove = { delta -> scope.launch { repository.move(module.id, index + delta); refresh++ } },
                onDelete = { scope.launch { repository.delete(module.id); refresh++ } },
            )
        }
        TextButton(onClick = { addModule = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("添加模块")
        }
    }
    if (addModule) AlertDialog(
        onDismissRequest = { addModule = false },
        title = { Text("添加模块") },
        text = { Column { allowedTypes.forEach { type ->
            TextButton(
                onClick = {
                    addModule = false
                    scope.launch {
                        runCatching { repository.add(owner, type, typeName(type)) }
                            .onSuccess { refresh++ }
                            .onFailure { error = it.message }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(typeName(type)) }
        } } },
        confirmButton = { TextButton(onClick = { addModule = false }) { Text("取消") } },
    )
    renameModule?.let { module ->
        AlertDialog(
            onDismissRequest = { renameModule = null },
            title = { Text("重命名模块") },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        renameModule = null
                        scope.launch { repository.rename(module.id, renameText); refresh++ }
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameModule = null }) { Text("取消") } },
        )
    }
    editModule?.let { module ->
        AlertDialog(
            onDismissRequest = { editModule = null },
            title = { Text("编辑${module.name}") },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    label = { Text("内容") },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editModule = null
                    scope.launch { repository.updateContent(module.id, encodeWorldModuleText(editText)); refresh++ }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editModule = null }) { Text("取消") } },
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
private fun SharedModuleRow(
    module: ContentModuleEntity,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (module.collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess, contentDescription = null)
            Text(module.name, modifier = Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Medium)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑内容") }
            TextButton(onClick = onRename) { Text("重命名") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除模块") }
        }
        if (!module.collapsed) Text(
            decodeWorldModuleText(module.contentJson).ifBlank { "尚未填写内容" },
            modifier = Modifier.padding(start = 40.dp, end = 16.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal val WORLD_PAGE_MODULE_TYPES = listOf(
    ContentModuleType.TIMELINE,
    ContentModuleType.ERA_EVENT,
    ContentModuleType.MAP,
    ContentModuleType.REGION,
    ContentModuleType.FACTION,
    ContentModuleType.RACE,
    ContentModuleType.CUSTOM,
)

internal val CHARACTER_PAGE_MODULE_TYPES = listOf(
    ContentModuleType.QUOTES,
    ContentModuleType.WORLD_EXPERIENCE,
    ContentModuleType.ATTRIBUTE_PANEL,
    ContentModuleType.EQUIPMENT,
    ContentModuleType.TALENT_SKILL,
    ContentModuleType.APPEARANCE_PERSONALITY,
    ContentModuleType.INTEREST,
    ContentModuleType.CUSTOM,
)

internal fun worldModuleDisplayName(type: ContentModuleType): String = when (type) {
    ContentModuleType.TIMELINE -> "时间线"
    ContentModuleType.ERA_EVENT -> "时代与事件"
    ContentModuleType.MAP -> "地图"
    ContentModuleType.REGION -> "地区设定"
    ContentModuleType.FACTION -> "势力设定"
    ContentModuleType.RACE -> "种族设定"
    ContentModuleType.CUSTOM -> "自定义模块"
    else -> type.name
}

internal fun characterModuleDisplayName(type: ContentModuleType): String = when (type) {
    ContentModuleType.QUOTES -> "多形态语录"
    ContentModuleType.WORLD_EXPERIENCE -> "世界经历"
    ContentModuleType.ATTRIBUTE_PANEL -> "属性面板"
    ContentModuleType.EQUIPMENT -> "随身装备"
    ContentModuleType.TALENT_SKILL -> "天赋技能"
    ContentModuleType.APPEARANCE_PERSONALITY -> "外貌性格"
    ContentModuleType.INTEREST -> "兴趣爱好"
    ContentModuleType.CUSTOM -> "自定义模块"
    else -> type.name
}

internal fun encodeWorldModuleText(text: String): String = JSONObject().put("text", text).toString()

internal fun decodeWorldModuleText(contentJson: String): String = runCatching {
    JSONObject(contentJson).optString("text")
}.getOrElse { contentJson }
