package com.openminis.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Person
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
import com.openminis.app.data.character.CharacterLibraryService
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleRepository
import com.openminis.app.data.character.ManagedMediaAssetStore
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetRepository
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexContentModuleBlock
import com.openminis.app.ui.novex.NovexDetailScaffold
import com.openminis.app.ui.novex.NovexTopAction
import com.openminis.app.ui.novex.toNovexPresentation
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class WorldPageData(
    val world: WorldEntity,
    val versions: List<CharacterVersionEntity>,
    val availableVersions: List<CharacterVersionEntity>,
    val versionWorlds: Map<String, List<WorldEntity>>,
    val media: Map<MediaAssetSlot, MediaAssetEntity>,
    val modules: List<ContentModuleEntity>,
    val moduleImages: Map<String, MediaAssetEntity>,
)

@Composable
fun CatalogWorldLibraryScreen(
    onBack: () -> Unit,
    onOpenWorld: (String) -> Unit,
    onCreateWorld: () -> Unit,
    onOpenCharacterLibrary: () -> Unit,
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
        actions = {
            IconButton(onClick = onOpenCharacterLibrary) {
                Icon(Icons.Default.Person, contentDescription = "打开角色库")
            }
        },
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
    onEditCharacterVersion: (String, String) -> Unit,
    onOpenSession: (String) -> Unit,
    onStartWorldNovax: (String?) -> Unit,
    onStartCharacterChat: (String, String) -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    CharacterCardStore.initialize(context)
    val legacyWorlds by CharacterCardStore.worlds.collectAsState()
    val allPersonas by CharacterCardStore.personas.collectAsState()
    val legacyWorld = legacyWorlds.firstOrNull { it.id == worldId }
    val personas = allPersonas.filter { it.worldId == worldId }
    val app = context.applicationContext as MinisApp
    val catalog = remember(app) { CharacterCatalogRepository(app.database.characterCatalogDao()) }
    val moduleRepository = remember(app) { ContentModuleRepository(app.database.contentModuleDao()) }
    val mediaRepo = rememberMediaRepository(app)
    val characterLibrary = remember(app) { CharacterLibraryService(catalog, moduleRepository, mediaRepo) }
    val owner = remember(worldId) { ModuleOwner.world(worldId) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<WorldPageData?>(null) }
    var missing by remember { mutableStateOf(false) }
    var addCharacter by remember { mutableStateOf(false) }
    var startCharacterChat by remember { mutableStateOf(false) }
    var selectedPersonaId by remember { mutableStateOf<String?>(null) }
    var selectedVersionId by remember { mutableStateOf<String?>(null) }
    var editVersion by remember { mutableStateOf<CharacterVersionEntity?>(null) }
    var creatorNotice by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(worldId, refresh) {
        val world = catalog.world(worldId)
        if (world == null) {
            missing = true
            data = null
        } else {
            missing = false
            val versions = catalog.versionsForWorld(worldId)
            val modules = moduleRepository.list(owner)
            data = WorldPageData(
                world = world,
                versions = versions,
                availableVersions = catalog.listVersions(),
                versionWorlds = versions.associate { it.id to catalog.worldsForVersion(it.id) },
                media = listOf(
                    MediaAssetSlot.WORLD_COVER,
                    MediaAssetSlot.WORLD_LOGO,
                    MediaAssetSlot.WORLD_BACKGROUND,
                ).mapNotNull { slot ->
                    mediaRepo.assetFor(owner, slot)?.let { slot to it }
                }.toMap(),
                modules = modules,
                moduleImages = modules.mapNotNull { module ->
                    mediaRepo.assetFor(ModuleOwner.contentModule(module.id), MediaAssetSlot.MODULE_IMAGE)
                        ?.let { module.id to it }
                }.toMap(),
            )
        }
    }
    val current = data
    NovexDetailScaffold(
        title = current?.world?.name ?: "世界",
        onBack = onBack,
        actions = {
            if (current != null) {
                NovexTopAction(
                    icon = com.openminis.app.R.drawable.ic_phosphor_sparkle,
                    contentDescription = "帮我创作",
                    label = "帮我创作",
                    onClick = { creatorNotice = true },
                )
                NovexTopAction(
                    icon = com.openminis.app.R.drawable.ic_phosphor_pencil_simple,
                    contentDescription = "编辑世界",
                    label = "编辑",
                    onClick = onEditWorld,
                )
            }
        },
    ) {
        when {
            missing -> Text("世界不存在或已删除", modifier = Modifier.padding(24.dp))
            current == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                WorldPrimaryContent(current, onOpenModule)
                SettingsSection(
                    header = "角色卡",
                    footer = "世界关联具体本体或分身；角色版本仍归角色库所有。",
                ) {
                    Button(
                        onClick = {
                            when {
                                personas.isEmpty() -> onEditPersona(null)
                                current.versions.isEmpty() -> addCharacter = true
                                else -> {
                                    selectedPersonaId = personas.firstOrNull { it.isDefault }?.id
                                        ?: personas.first().id
                                    selectedVersionId = current.versions.first().id
                                    startCharacterChat = true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    ) { Text("新建世界角色对话") }
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
                                editVersion = version
                            }) { Text("编辑") }
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
                SettingsSection(
                    header = "玩家身份",
                    footer = "一段世界角色对话选择一个玩家身份和一个本体或分身。",
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
                if (legacyWorld != null) {
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
    if (startCharacterChat && current != null) AlertDialog(
        onDismissRequest = { startCharacterChat = false },
        title = { Text("新建世界角色对话") },
        text = {
            Column {
                Text("玩家身份", fontWeight = FontWeight.Bold)
                personas.forEach { persona ->
                    TextButton(
                        onClick = { selectedPersonaId = persona.id },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (selectedPersonaId == persona.id) "✓  ${persona.name.ifBlank { "玩家" }}" else persona.name.ifBlank { "玩家" })
                    }
                }
                Text("角色版本", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                current.versions.forEach { version ->
                    TextButton(
                        onClick = { selectedVersionId = version.id },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val label = "${version.profileName()} · ${version.label}"
                        Text(if (selectedVersionId == version.id) "✓  $label" else label)
                    }
                }
                Text(
                    "当前只选择一个本体或分身，不会创建多角色共同对话。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedPersonaId != null && selectedVersionId != null,
                onClick = {
                    val personaId = selectedPersonaId ?: return@TextButton
                    val versionId = selectedVersionId ?: return@TextButton
                    startCharacterChat = false
                    onStartCharacterChat(versionId, personaId)
                },
            ) { Text("开始对话") }
        },
        dismissButton = { TextButton(onClick = { startCharacterChat = false }) { Text("取消") } },
    )
    editVersion?.let { version ->
        val affectedWorlds = current?.versionWorlds?.get(version.id).orEmpty()
        AlertDialog(
            onDismissRequest = { editVersion = null },
            title = { Text("如何修改${version.profileName()}？") },
            text = {
                Text(
                    "编辑共享版本会同步影响：" +
                        affectedWorlds.joinToString("、") { it.name }.ifBlank { "当前角色库版本" } +
                        "。另存为新分身只替换当前世界的关联。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editVersion = null
                    onEditCharacterVersion(version.characterId, version.id)
                }) { Text("编辑共享版本") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editVersion = null
                    scope.launch {
                        runCatching { characterLibrary.saveAsWorldVariant(version.id, worldId) }
                            .onSuccess { created ->
                                onEditCharacterVersion(created.characterId, created.id)
                            }
                            .onFailure { error = it.message }
                    }
                }) { Text("仅当前世界另存分身") }
            },
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
    if (creatorNotice) AlertDialog(
        onDismissRequest = { creatorNotice = false },
        title = { Text("帮我创作") },
        text = { Text("入口已保留，人工智能管理与写入本轮暂不开放，点击不会修改世界内容。") },
        confirmButton = { TextButton(onClick = { creatorNotice = false }) { Text("知道了") } },
    )
}

@Composable
fun CatalogWorldEditorScreen(
    worldId: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val catalog = remember(app) { CharacterCatalogRepository(app.database.characterCatalogDao()) }
    val moduleRepository = remember(app) { ContentModuleRepository(app.database.contentModuleDao()) }
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
    var previewData by remember { mutableStateOf<WorldPageData?>(null) }
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
                val loadedMedia = listOf(
                    MediaAssetSlot.WORLD_COVER,
                    MediaAssetSlot.WORLD_LOGO,
                    MediaAssetSlot.WORLD_BACKGROUND,
                ).mapNotNull { slot ->
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
    fun preview() {
        if (!loaded || name.isBlank()) return
        scope.launch {
            val now = System.currentTimeMillis()
            val draftWorld = (source ?: WorldEntity(
                id = worldId ?: "draft-world",
                name = name,
                createdAt = now,
                updatedAt = now,
            )).copy(
                name = name,
                overview = overview,
                tagsJson = JSONArray(
                    tags.split(Regex("[、,，\\n]")).map(String::trim).filter(String::isNotEmpty),
                ).toString(),
                updatedAt = now,
            )
            val draftOwner = owner
            val draftModules = if (draftOwner == null) emptyList() else moduleRepository.list(draftOwner)
            previewData = WorldPageData(
                world = draftWorld,
                versions = if (worldId == null) emptyList() else catalog.versionsForWorld(worldId),
                availableVersions = emptyList(),
                versionWorlds = emptyMap(),
                media = media,
                modules = draftModules,
                moduleImages = draftModules.mapNotNull { module ->
                    mediaRepo.assetFor(ModuleOwner.contentModule(module.id), MediaAssetSlot.MODULE_IMAGE)
                        ?.let { module.id to it }
                }.toMap(),
            )
        }
    }
    previewData?.let { draft ->
        NovexDetailScaffold(
            title = "世界草稿预览",
            onBack = { previewData = null },
            actions = {
                NovexTopAction(
                    icon = com.openminis.app.R.drawable.ic_phosphor_eye,
                    contentDescription = "返回编辑",
                    onClick = { previewData = null },
                )
            },
        ) {
            WorldPrimaryContent(draft, onOpenModule = null)
            Spacer(Modifier.height(32.dp))
        }
        return
    }
    SettingsScaffold(
        title = if (worldId == null) "创建世界" else "编辑世界",
        onBack = onBack,
        centerTitle = true,
        actions = {
            IconButton(onClick = ::preview, enabled = loaded && name.isNotBlank()) {
                Icon(
                    androidx.compose.ui.res.painterResource(com.openminis.app.R.drawable.ic_phosphor_eye),
                    contentDescription = "预览世界草稿",
                )
            }
            TextButton(onClick = ::save, enabled = loaded && name.isNotBlank() && !saving) {
                Text(if (saving) "保存中" else "保存")
            }
        },
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
            if (owner != null) {
                SharedContentModuleEditor(
                    owner = owner,
                    header = "内容模块",
                    footer = "模块可展开编辑并调整顺序；内置类型只允许一个，自定义模块不限数量。",
                    onOpenModule = onOpenModule,
                )
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
    val backgroundFile = background.existingMediaFile()
    val logoFile = logo.existingMediaFile()
    Box(Modifier.fillMaxWidth().height(210.dp)) {
        NovexArtwork(
            kind = NovexArtworkKind.WORLD,
            seed = data.world.id,
            imageModel = backgroundFile,
            contentDescription = "${data.world.name}背景",
            modifier = Modifier.fillMaxWidth().height(210.dp),
        )
        Box(
            Modifier.fillMaxWidth().height(210.dp).background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to androidx.compose.ui.graphics.Color.Transparent,
                    1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.62f),
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            logoFile?.let { file ->
                AsyncImage(
                    model = file,
                    contentDescription = "${data.world.name}标志",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                )
            }
            Text(
                data.world.name,
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = if (logoFile == null) 0.dp else 8.dp),
            )
            if (data.world.tags().isNotEmpty()) Text(
                data.world.tags().joinToString(" · "),
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun WorldPrimaryContent(
    data: WorldPageData,
    onOpenModule: ((String) -> Unit)?,
) {
    WorldHero(data)
    WorldOverviewBlock(data.world)
    data.modules.forEachIndexed { index, module ->
        if (index > 0) androidx.compose.material3.HorizontalDivider(
            color = NovexColors.Divider,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        NovexContentModuleBlock(
            presentation = module.toNovexPresentation(),
            imageModel = data.moduleImages[module.id]?.managedPath.existingMediaFile(),
            onClick = onOpenModule?.let { open -> { open(module.id) } },
        )
    }
}

@Composable
private fun WorldOverviewBlock(world: WorldEntity) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text("世界观概述", color = NovexColors.Text, fontWeight = FontWeight.SemiBold)
        Text(
            world.overview.ifBlank { "尚未填写世界观概述" },
            color = if (world.overview.isBlank()) NovexColors.SecondaryText else NovexColors.Text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
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
internal fun rememberMediaRepository(app: MinisApp): MediaAssetRepository {
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
internal fun rememberManagedMediaStore(
    context: Context,
    repository: MediaAssetRepository,
): ManagedMediaAssetStore = remember(repository) {
    ManagedMediaAssetStore(File(context.filesDir, "novex-media"), repository)
}

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

internal fun String?.existingMediaFile(): File? = this?.let(::File)?.takeIf(File::exists)
