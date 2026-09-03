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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.openminis.app.ui.novex.NovexContentSection
import com.openminis.app.ui.novex.NovexContentDialog
import com.openminis.app.ui.novex.NovexDetailScaffold
import com.openminis.app.ui.novex.NovexDraftPreviewScaffold
import com.openminis.app.ui.novex.NovexEditorScaffold
import com.openminis.app.ui.novex.NovexEditorFoldRow
import com.openminis.app.ui.novex.NovexEditorSection
import com.openminis.app.ui.novex.NovexOptionalImageRow
import com.openminis.app.ui.novex.NovexInlineField
import com.openminis.app.ui.novex.NovexNoticeDialog
import com.openminis.app.ui.novex.NovexOutlineButton
import com.openminis.app.ui.novex.NovexPrimaryButton
import com.openminis.app.ui.novex.NovexSummaryRow
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.novex.NovexTextField
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
            if (current != null) NovexPrimaryButton(
                label = "开始对话",
                onClick = ::beginWorldConversation,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
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
                NovexContentSection(
                    title = "玩家身份",
                    subtitle = "对话时选择",
                ) {
                    personas.forEach { persona ->
                        NovexSummaryRow(
                            title = persona.name.ifBlank { "玩家" },
                            summary = persona.description.ifBlank {
                                if (persona.isDefault) "默认身份" else "未填写身份说明"
                            },
                            onClick = { onEditPersona(persona.id) },
                        )
                    }
                    NovexTextActionRow(
                        label = if (personas.isEmpty()) "添加玩家身份" else "新增玩家身份",
                        onClick = { onEditPersona(null) },
                    )
                }
                if (hasLegacyWorld) {
                    NovexContentSection(title = "Nova 世界助手") {
                        NovexSummaryRow(
                            title = "与 Nova 讨论这个世界",
                            summary = "沿用升级前的世界观和玩家身份，不扮演角色卡。",
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
                NovexContentSection(title = "最近对话") {
                    if (worldSessions.isEmpty()) Text(
                        "还没有对话",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) else worldSessions.take(20).forEach { session ->
                        NovexSummaryRow(
                            title = session.title?.ifBlank { null } ?: "新对话",
                            summary = session.lastMessage?.ifBlank { null } ?: "暂无消息",
                            onClick = { onOpenSession(session.id) },
                        )
                    }
                }
                NovexContentSection(title = "世界管理") {
                    NovexTextActionRow(
                        label = "导出 Novex 世界卡",
                        icon = com.openminis.app.R.drawable.ic_phosphor_arrow_up,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    novex.apply(NovexCommand.ExportNativeWorld(worldId)).requireNativeCard()
                                }.onSuccess { shareNovexCardPackage(context, it) }
                                    .onFailure { error = it.message ?: "世界卡导出失败" }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (addCharacter && current != null) NovexContentDialog(
        title = "从角色库添加",
        onDismiss = { addCharacter = false },
        confirmButton = {
            NovexOutlineButton(label = "关闭", onClick = { addCharacter = false })
        },
    ) {
        current.availableVersions.filterNot { candidate ->
            current.versions.any { it.id == candidate.id }
        }.forEach { version ->
            NovexTextActionRow(
                label = "${version.profileName()} · ${version.label}",
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
            )
        }
    }
    if (startCharacterChat && current != null) NovexContentDialog(
        title = "新建世界角色对话",
        onDismiss = { startCharacterChat = false },
        confirmButton = {
            NovexPrimaryButton(
                label = "开始对话",
                enabled = selectedPersonaId != null && selectedVersionId != null,
                onClick = start@{
                    val personaId = selectedPersonaId ?: return@start
                    val versionId = selectedVersionId ?: return@start
                    startCharacterChat = false
                    onStartCharacterChat(versionId, personaId)
                },
                modifier = Modifier.width(112.dp),
            )
        },
        dismissButton = {
            NovexOutlineButton(label = "取消", onClick = { startCharacterChat = false })
        },
    ) {
                Text("玩家身份", fontWeight = FontWeight.Bold)
                personas.forEach { persona ->
                    NovexTextActionRow(
                        label = if (selectedPersonaId == persona.id) {
                            "✓  ${persona.name.ifBlank { "玩家" }}"
                        } else {
                            persona.name.ifBlank { "玩家" }
                        },
                        onClick = { selectedPersonaId = persona.id },
                    )
                }
                Text("角色版本", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                current.versions.forEach { version ->
                    val label = "${version.profileName()} · ${version.label}"
                    NovexTextActionRow(
                        label = if (selectedVersionId == version.id) "✓  $label" else label,
                        onClick = { selectedVersionId = version.id },
                    )
                }
                Text(
                    "当前只选择一个本体或分身，不会创建多角色共同对话。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
    }
    editVersion?.let { version ->
        val affectedWorlds = current?.worldsByVersion?.get(version.id).orEmpty()
        NovexContentDialog(
            title = "如何修改${version.profileName()}？",
            onDismiss = { editVersion = null },
            confirmButton = {
                NovexPrimaryButton(
                    label = "编辑共享版本",
                    onClick = {
                        editVersion = null
                        onEditCharacterVersion(version.characterId, version.id)
                    },
                    modifier = Modifier.width(132.dp),
                )
            },
            dismissButton = {
                NovexOutlineButton(
                    label = "另存分身",
                    onClick = {
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
                    },
                )
            },
        ) {
                    Text(
                        "编辑共享版本会同步影响：" +
                            affectedWorlds.joinToString("、") { it.name }.ifBlank { "当前角色库版本" } +
                            "。另存为新分身只替换当前世界的关联。",
                    )
                    NovexOutlineButton(
                        label = "从当前世界移除",
                        danger = true,
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
                    )
        }
    }
    error?.let { message ->
        NovexNoticeDialog("操作失败", message ?: "未知错误") { error = null }
    }
    if (creatorNotice) {
        NovexNoticeDialog(
            title = "帮我创作",
            message = "入口已保留，人工智能管理与写入本轮暂不开放，点击不会修改世界内容。",
            onDismiss = { creatorNotice = false },
        )
    }
}

@Composable
fun CatalogWorldEditorScreen(
    worldId: String?,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    val novex = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var draft by remember(worldId) { mutableStateOf(WorldEditorDraftState.create()) }
    var baselineDraft by remember(worldId) { mutableStateOf<WorldEditorDraftState?>(null) }
    var media by remember { mutableStateOf<Map<MediaAssetSlot, MediaAssetEntity>>(emptyMap()) }
    var persistedModuleIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingImages by remember { mutableStateOf<Map<MediaAssetSlot, PendingWorldImage>>(emptyMap()) }
    var pendingSlot by remember { mutableStateOf<MediaAssetSlot?>(null) }
    var visualExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var previewData by remember { mutableStateOf<WorldPageData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    BackHandler(enabled = previewData != null) {
        when (novexEditorBackAction(previewVisible = previewData != null)) {
            NovexEditorBackAction.CLOSE_PREVIEW -> previewData = null
            NovexEditorBackAction.PROMPT_SAVE -> Unit
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
        if (worldId == null) {
            val initial = WorldEditorDraftState.create(
                name = nextDefaultWorldName(novex.worlds().map { it.world.name }),
            )
            draft = initial
            baselineDraft = initial
        } else {
            val snapshot = novex.world(worldId)
            if (snapshot != null) {
                val initial = WorldEditorDraftState.from(snapshot.world, snapshot.modules)
                draft = initial
                baselineDraft = initial
                media = snapshot.media
                persistedModuleIds = snapshot.modules.mapTo(mutableSetOf()) { it.id }
            }
        }
        loaded = true
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
        baselineDraft = baselineDraft,
        currentDraft = draft,
        onPreview = ::preview,
        onSave = ::save,
        onDeleteRequest = worldId?.let { { confirmDelete = true } },
    ) {
        NovexEditorSection(header = "基础资料") {
                NovexInlineField(
                    label = "名称",
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    placeholder = "世界名称",
                )
                NovexInlineField(
                    label = "标签",
                    value = draft.tagsText,
                    onValueChange = { draft = draft.copy(tagsText = it) },
                    placeholder = "用顿号或逗号分隔",
                )
                NovexTextField(
                    label = "世界观概述",
                    value = draft.overview,
                    onValueChange = { draft = draft.copy(overview = it) },
                    minLines = 4,
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
        NovexNoticeDialog("保存失败", message ?: "未知错误") { error = null }
    }
    if (confirmDelete && worldId != null) {
        com.openminis.app.ui.novex.NovexDestructiveConfirmationDialog(
            title = "删除世界？",
            message = "将删除这个世界及其专属内容；共享角色版本和仍被引用的图片不会被删除。此操作无法撤销。",
            confirming = deleting,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                deleting = true
                scope.launch {
                    runCatching { novex.apply(NovexCommand.DeleteWorld(worldId)) }
                        .onSuccess { onDeleted() }
                        .onFailure {
                            deleting = false
                            confirmDelete = false
                            error = it.message
                        }
                }
            },
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
