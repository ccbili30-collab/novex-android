package com.openminis.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.MinisApp
import com.openminis.app.data.character.CharacterCatalogRepository
import com.openminis.app.data.character.CharacterCardStore
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleRepository
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ManagedMediaAssetStore
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetRepository
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.db.ChatSessionEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class WorldPageData(
    val world: WorldEntity,
    val modules: List<ContentModuleEntity>,
    val versions: List<CharacterVersionEntity>,
    val availableVersions: List<CharacterVersionEntity>,
    val media: Map<MediaAssetSlot, MediaAssetEntity>,
)

@Composable
fun CatalogWorldLibraryScreen(
    onBack: () -> Unit,
    onOpenWorld: (String) -> Unit,
    onCreateWorld: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as MinisApp
    val catalog = remember(app) { CharacterCatalogRepository(app.database.characterCatalogDao()) }
    val media = rememberMediaRepository(app)
    var worlds by remember { mutableStateOf<List<Pair<WorldEntity, String?>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        worlds = catalog.listWorlds().map { world ->
            val owner = ModuleOwner.world(world.id)
            val image = media.assetFor(owner, MediaAssetSlot.WORLD_COVER)
                ?: media.assetFor(owner, MediaAssetSlot.WORLD_BACKGROUND)
            world to (image?.managedPath ?: world.legacyBackgroundPath())
        }
        loaded = true
    }
    SettingsScaffold(
        title = "我的世界",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateWorld,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("创建世界") },
            )
        },
    ) {
        when {
            !loaded -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            worlds.isEmpty() -> WorldEmptyRow("还没有世界", "创建世界", onCreateWorld)
            else -> worlds.forEach { (world, imagePath) ->
                Card(
                    onClick = { onOpenWorld(world.id) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    imagePath.existingMediaFile()?.let { file ->
                        AsyncImage(
                            model = file,
                            contentDescription = "${world.name}封面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(132.dp),
                        )
                    }
                    Column(Modifier.padding(16.dp)) {
                        Text(world.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val tags = world.tags()
                        if (tags.isNotEmpty()) Text(
                            tags.joinToString(" · "),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            world.overview.ifBlank { "尚未填写世界观概述" },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
fun CatalogWorldDetailScreen(
    worldId: String,
    sessions: List<ChatSessionEntity>,
    onBack: () -> Unit,
    onEditWorld: () -> Unit,
    onEditPersona: (String?) -> Unit,
    onCreateCharacter: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onStartWorldNovax: (String?) -> Unit,
) {
    val context = LocalContext.current
    CharacterCardStore.initialize(context)
    val legacyWorlds by CharacterCardStore.worlds.collectAsState()
    val allPersonas by CharacterCardStore.personas.collectAsState()
    val legacyWorld = legacyWorlds.firstOrNull { it.id == worldId }
    val personas = allPersonas.filter { it.worldId == worldId }
    val app = context.applicationContext as MinisApp
    val catalog = remember(app) { CharacterCatalogRepository(app.database.characterCatalogDao()) }
    val moduleRepo = remember(app) { ContentModuleRepository(app.database.contentModuleDao()) }
    val mediaRepo = rememberMediaRepository(app)
    val owner = remember(worldId) { ModuleOwner.world(worldId) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<WorldPageData?>(null) }
    var missing by remember { mutableStateOf(false) }
    var addModule by remember { mutableStateOf(false) }
    var addCharacter by remember { mutableStateOf(false) }
    var renameModule by remember { mutableStateOf<ContentModuleEntity?>(null) }
    var renameText by remember { mutableStateOf("") }
    var editModule by remember { mutableStateOf<ContentModuleEntity?>(null) }
    var editText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(worldId, refresh) {
        val world = catalog.world(worldId)
        if (world == null) {
            missing = true
            data = null
        } else {
            missing = false
            data = WorldPageData(
                world = world,
                modules = moduleRepo.list(owner),
                versions = catalog.versionsForWorld(worldId),
                availableVersions = catalog.listVersions(),
                media = MediaAssetSlot.entries.mapNotNull { slot ->
                    mediaRepo.assetFor(owner, slot)?.let { slot to it }
                }.toMap(),
            )
        }
    }
    val current = data
    SettingsScaffold(
        title = current?.world?.name ?: "世界",
        onBack = onBack,
        actions = {
            if (current != null) IconButton(onClick = onEditWorld) {
                Icon(Icons.Default.Edit, contentDescription = "编辑世界")
            }
        },
    ) {
        when {
            missing -> Text("世界不存在或已删除", modifier = Modifier.padding(24.dp))
            current == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                WorldHero(current)
                SettingsSection(header = "世界观概述") {
                    Text(
                        current.world.overview.ifBlank { "尚未填写世界观概述" },
                        modifier = Modifier.padding(16.dp),
                        color = if (current.world.overview.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else MaterialTheme.colorScheme.onSurface,
                    )
                    if (current.world.tags().isNotEmpty()) Text(
                        current.world.tags().joinToString("  ·  "),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                SettingsSection(
                    header = "世界设定模块",
                    footer = "时间线、事件、地图、地区、势力、种族和自定义模块只在添加后出现。",
                ) {
                    current.modules.forEachIndexed { index, module ->
                        WorldModuleRow(
                            module = module,
                            canMoveUp = index > 0,
                            canMoveDown = index < current.modules.lastIndex,
                            onToggle = {
                                scope.launch {
                                    moduleRepo.setCollapsed(module.id, !module.collapsed)
                                    refresh++
                                }
                            },
                            onRename = { renameModule = module; renameText = module.name },
                            onEdit = { editModule = module; editText = module.editableText() },
                            onMove = { delta ->
                                scope.launch { moduleRepo.move(module.id, index + delta); refresh++ }
                            },
                            onDelete = {
                                scope.launch { moduleRepo.delete(module.id); refresh++ }
                            },
                        )
                    }
                    TextButton(onClick = { addModule = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加模块")
                    }
                }
                SettingsSection(
                    header = "角色卡",
                    footer = "世界关联具体本体或分身；角色版本仍归角色库所有。",
                ) {
                    current.versions.forEach { version ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenCharacter(version.characterId) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(version.profileName(), fontWeight = FontWeight.Medium)
                                Text(
                                    version.label,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    catalog.removeVersionFromWorld(worldId, version.id)
                                    refresh++
                                }
                            }) { Text("移除") }
                        }
                    }
                    TextButton(
                        onClick = {
                            if (current.availableVersions.isEmpty()) onCreateCharacter() else addCharacter = true
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("从角色库添加")
                    }
                }
                if (legacyWorld != null) {
                    SettingsSection(
                        header = "玩家身份",
                        footer = "旧世界的玩家身份入口会保留到新的世界对话流程接管为止。",
                    ) {
                        personas.forEach { persona ->
                            SettingsRow(
                                title = persona.name.ifBlank { "玩家" },
                                subtitle = persona.description.ifBlank {
                                    if (persona.isDefault) "默认身份" else "未填写身份说明"
                                },
                                onClick = { onEditPersona(persona.id) },
                            )
                        }
                        TextButton(
                            onClick = { onEditPersona(null) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(if (personas.isEmpty()) "添加玩家身份" else "新增玩家身份")
                        }
                    }
                    SettingsSection(header = "Novax 世界助手") {
                        SettingsRow(
                            title = "与 Novax 讨论这个世界",
                            subtitle = "沿用升级前的世界观和玩家身份，不扮演角色卡。",
                            onClick = {
                                onStartWorldNovax(
                                    personas.firstOrNull { it.isDefault }?.id ?: personas.firstOrNull()?.id,
                                )
                            },
                        )
                    }
                }
                val worldSessions = sessions.filter { session ->
                    session.worldId == worldId || session.worldSnapshotJson.worldIdFromSnapshot() == worldId
                }
                SettingsSection(header = "最近对话") {
                    if (worldSessions.isEmpty()) Text(
                        "还没有对话",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) else worldSessions.take(20).forEach { session ->
                        SettingsRow(
                            title = session.title?.ifBlank { null } ?: "新对话",
                            subtitle = session.lastMessage?.ifBlank { null } ?: "暂无消息",
                            onClick = { onOpenSession(session.id) },
                        )
                    }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (addModule) ModulePickerDialog(
        onDismiss = { addModule = false },
        onPick = { type ->
            addModule = false
            scope.launch {
                runCatching { moduleRepo.add(owner, type, worldModuleDisplayName(type)) }
                    .onSuccess { refresh++ }
                    .onFailure { error = it.message }
            }
        },
    )
    if (addCharacter && current != null) AlertDialog(
        onDismissRequest = { addCharacter = false },
        title = { Text("从角色库添加") },
        text = {
            Column {
                current.availableVersions.filterNot { candidate ->
                    current.versions.any { it.id == candidate.id }
                }.forEach { version ->
                    TextButton(
                        onClick = {
                            addCharacter = false
                            scope.launch {
                                catalog.addVersionToWorld(
                                    worldId,
                                    version.id,
                                    current.versions.size,
                                )
                                refresh++
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${version.profileName()} · ${version.label}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { addCharacter = false }) { Text("关闭") } },
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
                        scope.launch { moduleRepo.rename(module.id, renameText); refresh++ }
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
                    scope.launch {
                        moduleRepo.updateContent(module.id, encodeWorldModuleText(editText))
                        refresh++
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editModule = null }) { Text("取消") } },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("操作失败") },
            text = { Text(message ?: "未知错误") },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
        )
    }
}

@Composable
fun CatalogWorldEditorScreen(
    worldId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val catalog = remember(app) { CharacterCatalogRepository(app.database.characterCatalogDao()) }
    val mediaRepo = rememberMediaRepository(app)
    val mediaStore = rememberManagedMediaStore(context, mediaRepo)
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<WorldEntity?>(null) }
    var loaded by remember { mutableStateOf(worldId == null) }
    var name by rememberSaveable(worldId) { mutableStateOf("我的世界") }
    var tags by rememberSaveable(worldId) { mutableStateOf("") }
    var overview by rememberSaveable(worldId) { mutableStateOf("") }
    var media by remember { mutableStateOf<Map<MediaAssetSlot, MediaAssetEntity>>(emptyMap()) }
    var pendingSlot by remember { mutableStateOf<MediaAssetSlot?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val owner = worldId?.let(ModuleOwner::world)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri != null && slot != null && owner != null) scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取图片")
                }
                val asset = mediaStore.import(
                    bytes = bytes,
                    mimeType = context.contentResolver.getType(uri) ?: "image/*",
                )
                mediaRepo.attach(owner, slot, asset.id)
                media = media + (slot to asset)
            }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(worldId) {
        if (worldId != null) {
            val world = catalog.world(worldId)
            source = world
            if (world != null) {
                name = world.name
                tags = world.tags().joinToString("、")
                overview = world.overview
                val loadedMedia = MediaAssetSlot.entries.mapNotNull { slot ->
                    mediaRepo.assetFor(ModuleOwner.world(worldId), slot)?.let { slot to it }
                }.toMap()
                media = loadedMedia
            }
            loaded = true
        }
    }
    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            runCatching {
                val tagsJson = JSONArray(
                    tags.split(Regex("[、,，\\n]")).map(String::trim).filter(String::isNotEmpty),
                ).toString()
                if (source == null) {
                    catalog.createWorld(name, overview, tagsJson = tagsJson)
                } else {
                    catalog.saveWorld(source!!.copy(name = name, overview = overview, tagsJson = tagsJson))
                }
            }.onSuccess { onSaved(it.id) }
                .onFailure { error = it.message; saving = false }
        }
    }
    SettingsScaffold(
        title = if (worldId == null) "创建世界" else "编辑世界",
        onBack = onBack,
        actions = { TextButton(onClick = ::save, enabled = loaded && name.isNotBlank() && !saving) {
            Text(if (saving) "保存中" else "保存")
        } },
    ) {
        if (!loaded) Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        } else {
            SettingsSection(header = "基础资料") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("世界名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("标签（用顿号或逗号分隔）") },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                OutlinedTextField(
                    value = overview,
                    onValueChange = { overview = it },
                    label = { Text("世界观概述") },
                    minLines = 7,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            SettingsSection(
                header = "视觉资源",
                footer = if (worldId == null) "保存世界后即可添加图片。" else "封面、标志与全屏背景可以复用同一受管资源。",
            ) {
                listOf(
                    MediaAssetSlot.WORLD_COVER to "世界封面",
                    MediaAssetSlot.WORLD_LOGO to "世界标志",
                    MediaAssetSlot.WORLD_BACKGROUND to "全屏背景",
                ).forEach { (slot, label) ->
                    WorldImageEditorRow(
                        label = label,
                        path = media[slot]?.managedPath,
                        enabled = owner != null,
                        onPick = {
                            pendingSlot = slot
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onRemove = {
                            if (owner != null) scope.launch {
                                mediaRepo.detach(owner, slot)
                                media = media - slot
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("保存失败") },
            text = { Text(message ?: "未知错误") },
            confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun WorldHero(data: WorldPageData) {
    val background = data.media[MediaAssetSlot.WORLD_BACKGROUND]?.managedPath
        ?: data.media[MediaAssetSlot.WORLD_COVER]?.managedPath
        ?: data.world.legacyBackgroundPath()
    val logo = data.media[MediaAssetSlot.WORLD_LOGO]?.managedPath
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Box {
            background.existingMediaFile()?.let { file ->
                AsyncImage(
                    model = file,
                    contentDescription = "${data.world.name}背景",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            } ?: Spacer(Modifier.fillMaxWidth().height(120.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                logo.existingMediaFile()?.let { file ->
                    AsyncImage(
                        model = file,
                        contentDescription = "${data.world.name}标志",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                    )
                }
                Text(
                    data.world.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun WorldModuleRow(
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
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
            module.editableText().ifBlank { "尚未填写内容" },
            modifier = Modifier.padding(start = 40.dp, end = 16.dp, bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModulePickerDialog(onDismiss: () -> Unit, onPick: (ContentModuleType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加世界模块") },
        text = { Column { WORLD_PAGE_MODULE_TYPES.forEach { type ->
            TextButton(onClick = { onPick(type) }, modifier = Modifier.fillMaxWidth()) {
                Text(worldModuleDisplayName(type))
            }
        } } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun WorldImageEditorRow(
    label: String,
    path: String?,
    enabled: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        path.existingMediaFile()?.let { file ->
            AsyncImage(
                model = file,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        Column(Modifier.weight(1f).padding(start = if (path == null) 0.dp else 12.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                if (path == null) "未设置" else "已设置",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onPick, enabled = enabled) { Text(if (path == null) "选择" else "更换") }
        if (path != null) TextButton(onClick = onRemove) { Text("移除") }
    }
}

@Composable
private fun WorldEmptyRow(title: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun rememberMediaRepository(app: MinisApp): MediaAssetRepository {
    val context = LocalContext.current.applicationContext
    return remember(app) {
        val root = File(context.filesDir, "novex-media").canonicalFile
        MediaAssetRepository(app.database.mediaAssetDao()) { path ->
            val target = File(path).canonicalFile
            if (target.parentFile == root) target.delete() else false
        }
    }
}

@Composable
private fun rememberManagedMediaStore(
    context: Context,
    repository: MediaAssetRepository,
): ManagedMediaAssetStore = remember(repository) {
    ManagedMediaAssetStore(File(context.filesDir, "novex-media"), repository)
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

internal fun encodeWorldModuleText(text: String): String = JSONObject().put("text", text).toString()

internal fun decodeWorldModuleText(contentJson: String): String = runCatching {
    JSONObject(contentJson).optString("text")
}.getOrElse { contentJson }

private fun ContentModuleEntity.editableText(): String = decodeWorldModuleText(contentJson)

private fun WorldEntity.tags(): List<String> = runCatching {
    val array = JSONArray(tagsJson)
    buildList {
        for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
    }
}.getOrDefault(emptyList())

private fun WorldEntity.legacyBackgroundPath(): String? = legacySnapshotJson?.let { raw ->
    runCatching { JSONObject(raw).optString("backgroundPath").trim().ifBlank { null } }.getOrNull()
}

private fun CharacterVersionEntity.profileName(): String = runCatching {
    JSONObject(profileJson).optString("name").trim().ifBlank { label }
}.getOrDefault(label)

private fun String?.worldIdFromSnapshot(): String? = this?.let { raw ->
    runCatching { JSONObject(raw).optString("id").trim().ifBlank { null } }.getOrNull()
}

private fun String?.existingMediaFile(): File? = this?.let(::File)?.takeIf(File::exists)
