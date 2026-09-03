package com.openminis.app.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexImageChange
import com.openminis.app.novex.domain.NovexModuleDraft
import com.openminis.app.novex.domain.NovexWorldSnapshot
import com.openminis.app.novex.domain.requireNativeCard
import com.openminis.app.novex.domain.requireVersion
import com.openminis.app.novex.domain.requireWorld
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexContentModuleList
import com.openminis.app.ui.novex.NovexDetailScaffold
import com.openminis.app.ui.novex.NovexDraftPreviewScaffold
import com.openminis.app.ui.novex.NovexEditorScaffold
import com.openminis.app.ui.novex.NovexEditorFoldRow
import com.openminis.app.ui.novex.NovexEditorSection
import com.openminis.app.ui.novex.NovexOptionalImageRow
import com.openminis.app.ui.novex.NovexTopAction
import com.openminis.app.ui.novex.rememberNovexWorkspace
import com.openminis.app.ui.navigation.NovexEditorBackAction
import com.openminis.app.ui.navigation.novexEditorBackAction
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private typealias WorldPageData = NovexWorldSnapshot

internal data class WorldImageSlotSpec(
    val slot: MediaAssetSlot,
    val label: String,
    val required: Boolean = false,
)

internal fun worldImageSlots(): List<WorldImageSlotSpec> = listOf(
    WorldImageSlotSpec(MediaAssetSlot.WORLD_COVER, "世界封面"),
    WorldImageSlotSpec(MediaAssetSlot.WORLD_LOGO, "世界标志"),
    WorldImageSlotSpec(MediaAssetSlot.WORLD_BACKGROUND, "全屏背景"),
)

private data class PendingWorldImage(
    val uri: Uri,
    val bytes: ByteArray,
    val mimeType: String,
)

data class WorldPersonaSummary(
    val id: String,
    val name: String,
    val description: String,
    val isDefault: Boolean,
)

@Composable
fun CatalogWorldDetailScreen(
    worldId: String,
    sessions: List<ChatSessionEntity>,
    hasLegacyWorld: Boolean,
    personas: List<WorldPersonaSummary>,
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
    val novex = rememberNovexWorkspace()
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
        val snapshot = novex.world(worldId)
        if (snapshot == null) {
            missing = true
            data = null
        } else {
            missing = false
            data = snapshot
        }
    }
    val current = data
    fun beginWorldConversation() {
        val page = current ?: return
        when {
            personas.isEmpty() -> onEditPersona(null)
            page.versions.isEmpty() -> addCharacter = true
            else -> {
                selectedPersonaId = personas.firstOrNull { it.isDefault }?.id ?: personas.first().id
                selectedVersionId = page.versions.first().id
                startCharacterChat = true
            }
        }
    }
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
        bottomBar = {
            if (current != null) Button(
                onClick = ::beginWorldConversation,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text("开始对话") }
        },
    ) {
        when {
            missing -> Text("世界不存在或已删除", modifier = Modifier.padding(24.dp))
            current == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> {
                WorldPrimaryContent(current, onOpenModule)
                WorldCharacterStrip(
                    data = current,
                    onOpenCharacter = onOpenCharacter,
                    onEditVersion = { editVersion = it },
                    onAdd = {
                        if (current.availableVersions.isEmpty()) onCreateCharacter() else addCharacter = true
                    },
                )
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
                if (hasLegacyWorld) {
                    SettingsSection(header = "Nova 世界助手") {
                        SettingsRow(
                            title = "与 Nova 讨论这个世界",
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
                SettingsSection(header = "世界管理") {
                    TextButton(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    novex.apply(NovexCommand.ExportNativeWorld(worldId)).requireNativeCard()
                                }.onSuccess { shareNovexCardPackage(context, it) }
                                    .onFailure { error = it.message ?: "世界卡导出失败" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("导出 Novex 世界卡") }
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
                                novex.apply(
                                    NovexCommand.LinkCharacterVersion(
                                        worldId,
                                        version.id,
                                        current.versions.size,
                                    ),
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
        val affectedWorlds = current?.worldsByVersion?.get(version.id).orEmpty()
        AlertDialog(
            onDismissRequest = { editVersion = null },
            title = { Text("如何修改${version.profileName()}？") },
            text = {
                Column {
                    Text(
                        "编辑共享版本会同步影响：" +
                            affectedWorlds.joinToString("、") { it.name }.ifBlank { "当前角色库版本" } +
                            "。另存为新分身只替换当前世界的关联。",
                    )
                    TextButton(
                        onClick = {
                            editVersion = null
                            scope.launch {
                                runCatching {
                                    novex.apply(NovexCommand.UnlinkCharacterVersion(worldId, version.id))
                                }.onSuccess { refresh++ }
                                    .onFailure { error = it.message }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("从当前世界移除") }
                }
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
                        runCatching {
                            novex.apply(NovexCommand.SaveAsWorldVariant(version.id, worldId)).requireVersion()
                        }
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
    val novex = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(worldId == null) }
    var draft by remember(worldId) { mutableStateOf(WorldEditorDraftState.create()) }
    var media by remember { mutableStateOf<Map<MediaAssetSlot, MediaAssetEntity>>(emptyMap()) }
    var persistedModuleIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingImages by remember { mutableStateOf<Map<MediaAssetSlot, PendingWorldImage>>(emptyMap()) }
    var pendingSlot by remember { mutableStateOf<MediaAssetSlot?>(null) }
    var visualExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var previewData by remember { mutableStateOf<WorldPageData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = previewData != null) {
        when (novexEditorBackAction(previewVisible = previewData != null)) {
            NovexEditorBackAction.CLOSE_PREVIEW -> previewData = null
            NovexEditorBackAction.LEAVE_EDITOR -> onBack()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri != null && slot != null) scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取图片")
                }
                val image = PendingWorldImage(
                    uri = uri,
                    bytes = bytes,
                    mimeType = context.contentResolver.getType(uri) ?: "image/*",
                )
                pendingImages = pendingImages + (slot to image)
                draft = draft.replaceImage(slot, image.bytes, image.mimeType)
            }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(worldId) {
        if (worldId != null) {
            val snapshot = novex.world(worldId)
            if (snapshot != null) {
                draft = WorldEditorDraftState.from(snapshot.world, snapshot.modules)
                media = snapshot.media
                persistedModuleIds = snapshot.modules.mapTo(mutableSetOf()) { it.id }
            }
            loaded = true
        }
    }
    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            runCatching {
                novex.apply(draft.toSaveCommand()).requireWorld()
            }.onSuccess { onSaved(it.id) }
                .onFailure { error = it.message; saving = false }
        }
    }
    fun preview() {
        if (!loaded || draft.name.isBlank()) return
        scope.launch {
            val now = System.currentTimeMillis()
            val savedSnapshot = worldId?.let { novex.world(it) }
            val visibleMedia = media.filterKeys { slot -> draft.imageChanges[slot] !is NovexImageChange.Remove }
            previewData = WorldPageData(
                world = draft.previewWorld(now),
                versions = savedSnapshot?.versions.orEmpty(),
                availableVersions = emptyList(),
                worldsByVersion = emptyMap(),
                media = visibleMedia,
                modules = draft.modules.mapIndexed { index, module ->
                    module.toPreviewEntity(draft.worldId ?: "draft-world", index, now)
                },
                moduleImages = savedSnapshot?.moduleImages.orEmpty(),
                moduleItemImages = savedSnapshot?.moduleItemImages.orEmpty(),
            )
        }
    }
    previewData?.let { draft ->
        NovexDraftPreviewScaffold(
            title = "世界草稿预览",
            onBack = { previewData = null },
        ) {
            WorldPrimaryContent(
                data = draft,
                onOpenModule = null,
                mediaModels = pendingImages.mapValues { it.value.uri },
            )
            Spacer(Modifier.height(32.dp))
        }
        return
    }
    NovexEditorScaffold(
        title = if (worldId == null) "创建世界" else "编辑世界",
        onBack = onBack,
        loaded = loaded,
        canSave = draft.name.isNotBlank(),
        saving = saving,
        onPreview = ::preview,
        onSave = ::save,
    ) {
        NovexEditorSection(header = "基础资料") {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("世界名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OutlinedTextField(
                    value = draft.tagsText,
                    onValueChange = { draft = draft.copy(tagsText = it) },
                    label = { Text("标签（用顿号或逗号分隔）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OutlinedTextField(
                    value = draft.overview,
                    onValueChange = { draft = draft.copy(overview = it) },
                    label = { Text("世界观概述") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            NovexEditorSection(
                header = "视觉资源",
                footer = "全部图片均可留空，也可以随时独立添加、更换或移除。",
            ) {
                NovexEditorFoldRow("封面、标志与全屏背景", visualExpanded) {
                    visualExpanded = !visualExpanded
                }
                if (visualExpanded) {
                    worldImageSlots().forEach { imageSlot ->
                        val slot = imageSlot.slot
                        val pending = pendingImages[slot]
                        val imageModel = when (draft.imageChanges[slot]) {
                            is NovexImageChange.Remove -> null
                            is NovexImageChange.Replace -> pending?.uri
                            null -> media[slot]?.managedPath.existingMediaFile()
                        }
                        NovexOptionalImageRow(
                            label = imageSlot.label,
                            imageModel = imageModel,
                            onPick = {
                                pendingSlot = slot
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onRemove = {
                                pendingImages = pendingImages - slot
                                draft = draft.removeImage(slot)
                            },
                        )
                    }
                }
            }
            SharedContentModuleDraftEditor(
                state = draft.contentModules,
                persistedModuleIds = persistedModuleIds,
                onChange = { draft = draft.copy(contentModules = it) },
                onOpenDetails = onOpenModule,
            )
        Spacer(Modifier.height(32.dp))
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
private fun WorldCharacterStrip(
    data: WorldPageData,
    onOpenCharacter: (String) -> Unit,
    onEditVersion: (CharacterVersionEntity) -> Unit,
    onAdd: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(NovexColors.Surface).padding(vertical = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text("角色", color = NovexColors.Text, fontWeight = FontWeight.SemiBold)
            Text(
                "${data.versions.size} 个角色版本",
                color = NovexColors.SecondaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            items(data.versions, key = { it.id }) { version ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(72.dp),
                ) {
                    NovexArtwork(
                        kind = NovexArtworkKind.CHARACTER,
                        seed = version.id,
                        imageModel = data.versionAvatars[version.id]?.managedPath.existingMediaFile(),
                        contentDescription = "${version.profileName()}头像",
                        modifier = Modifier.size(58.dp).clip(CircleShape)
                            .clickable { onOpenCharacter(version.characterId) },
                    )
                    Text(
                        version.profileName(),
                        color = NovexColors.Text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        version.label,
                        color = NovexColors.SecondaryText,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { onEditVersion(version) }) { Text("编辑") }
                }
            }
            item(key = "add-character") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(84.dp).clickable(onClick = onAdd),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(58.dp).clip(CircleShape).background(NovexColors.PrimarySoft),
                    ) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(com.openminis.app.R.drawable.ic_phosphor_plus),
                            contentDescription = "从角色库添加",
                            tint = NovexColors.Primary,
                        )
                    }
                    Text(
                        "从角色库添加",
                        color = NovexColors.Primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorldHero(
    data: WorldPageData,
    mediaModels: Map<MediaAssetSlot, Any?> = emptyMap(),
) {
    val background = data.media[MediaAssetSlot.WORLD_COVER]?.managedPath
        ?: data.media[MediaAssetSlot.WORLD_BACKGROUND]?.managedPath
        ?: data.world.legacyBackgroundPath()
    val logo = data.media[MediaAssetSlot.WORLD_LOGO]?.managedPath
    val backgroundModel = mediaModels[MediaAssetSlot.WORLD_COVER]
        ?: mediaModels[MediaAssetSlot.WORLD_BACKGROUND]
        ?: background.existingMediaFile()
    val logoModel = mediaModels[MediaAssetSlot.WORLD_LOGO] ?: logo.existingMediaFile()
    Box(Modifier.fillMaxWidth().height(210.dp)) {
        NovexArtwork(
            kind = NovexArtworkKind.WORLD,
            seed = data.world.id,
            imageModel = backgroundModel,
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
            logoModel?.let { model ->
                AsyncImage(
                    model = model,
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
                modifier = Modifier.padding(top = if (logoModel == null) 0.dp else 8.dp),
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
    mediaModels: Map<MediaAssetSlot, Any?> = emptyMap(),
) {
    WorldHero(data, mediaModels)
    WorldOverviewBlock(data.world)
    if (data.modules.isNotEmpty()) androidx.compose.material3.HorizontalDivider(
        color = NovexColors.Divider,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    NovexContentModuleList(
        modules = data.modules,
        moduleImages = data.moduleImages.mapValues { it.value.managedPath.existingMediaFile() },
        moduleItemImages = data.moduleItemImages.mapValues { (_, images) ->
            images.mapValues { it.value.managedPath.existingMediaFile() }
        },
        onOpenModule = onOpenModule,
    )
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

private fun NovexModuleDraft.toPreviewEntity(
    ownerId: String,
    position: Int,
    now: Long,
): ContentModuleEntity = ContentModuleEntity(
    id = id,
    ownerType = ModuleOwnerType.WORLD,
    ownerId = ownerId,
    type = type,
    name = name,
    contentJson = contentJson,
    position = position,
    collapsed = collapsed,
    createdAt = now,
    updatedAt = now,
)

private fun String?.worldIdFromSnapshot(): String? = this?.let { raw ->
    runCatching { JSONObject(raw).optString("id").trim().ifBlank { null } }.getOrNull()
}

internal fun String?.existingMediaFile(): File? = this?.let(::File)?.takeIf(File::exists)
