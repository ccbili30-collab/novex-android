package com.openminis.app.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.novex.domain.NovexImageChange
import com.openminis.app.novex.domain.NovexModuleDraft
import com.openminis.app.novex.domain.requireCharacter
import com.openminis.app.ui.navigation.NovexEditorBackAction
import com.openminis.app.ui.navigation.novexEditorBackAction
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexDetailScaffold
import com.openminis.app.ui.novex.NovexTopAction
import com.openminis.app.ui.novex.rememberNovexWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingCharacterImage(
    val uri: Uri,
    val bytes: ByteArray,
    val mimeType: String,
)

/** Product editor replacing the legacy settings-form route. */
@Composable
fun NovexCharacterEditorScreen(
    characterId: String?,
    versionId: String?,
    worldId: String?,
    createVariant: Boolean,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    val novex = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(characterId == null) }
    var draft by remember(characterId, versionId, createVariant) {
        mutableStateOf(CharacterEditorDraftState.create())
    }
    var sourceVersion by remember { mutableStateOf<CharacterVersionEntity?>(null) }
    var variantCount by remember { mutableStateOf(0) }
    var media by remember { mutableStateOf<Map<MediaAssetSlot, MediaAssetEntity>>(emptyMap()) }
    var persistedModuleIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingImages by remember { mutableStateOf<Map<MediaAssetSlot, PendingCharacterImage>>(emptyMap()) }
    var pendingSlot by remember { mutableStateOf<MediaAssetSlot?>(null) }
    var optionalExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var previewData by remember { mutableStateOf<CharacterPageData?>(null) }
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
                val image = PendingCharacterImage(
                    uri = uri,
                    bytes = bytes,
                    mimeType = context.contentResolver.getType(uri) ?: "image/*",
                )
                pendingImages = pendingImages + (slot to image)
                draft = draft.replaceImage(slot, image.bytes, image.mimeType)
            }.onFailure { error = it.message }
        }
    }

    LaunchedEffect(characterId, versionId, createVariant) {
        if (characterId == null) {
            draft = CharacterEditorDraftState.create()
            loaded = true
        } else {
            val snapshot = novex.character(characterId)
            val aggregate = snapshot?.character
            val version = when {
                versionId != null -> aggregate?.allVersions?.firstOrNull { it.id == versionId }
                else -> aggregate?.original
            }
            if (aggregate != null && version != null) {
                val modules = snapshot.modulesByVersion[version.id].orEmpty()
                draft = CharacterEditorDraftState.from(aggregate, version, modules, createVariant)
                sourceVersion = version
                variantCount = aggregate.variants.size
                media = snapshot.mediaByVersion[version.id].orEmpty()
                persistedModuleIds = if (createVariant) emptySet() else modules.mapTo(mutableSetOf()) { it.id }
            } else {
                error = "角色或角色版本不存在"
            }
            loaded = true
        }
    }

    fun save() {
        if (saving || draft.isBlank) return
        saving = true
        scope.launch {
            runCatching { novex.apply(draft.toSaveCommand(worldId)).requireCharacter() }
                .onSuccess { onSaved(it.character.id) }
                .onFailure { error = it.message; saving = false }
        }
    }

    fun preview() {
        if (!loaded || draft.isBlank) return
        val now = System.currentTimeMillis()
        val version = sourceVersion?.takeUnless { createVariant }?.copy(
            label = draft.label,
            profileJson = draft.profile().toJson(),
        ) ?: CharacterVersionEntity(
            id = "draft-character-version",
            characterId = characterId ?: "draft-character",
            kind = if (createVariant) CharacterVersionKind.VARIANT else CharacterVersionKind.ORIGINAL,
            label = draft.label,
            profileJson = draft.profile().toJson(),
            createdAt = now,
            updatedAt = now,
        )
        previewData = CharacterPageData(
            rootName = draft.rootName,
            version = version,
            profile = draft.profile(),
            worlds = emptyList(),
            media = media.filterKeys { slot -> draft.imageChanges[slot] !is NovexImageChange.Remove },
            modules = draft.modules.mapIndexed { index, module ->
                module.toCharacterPreviewEntity(version.id, index, now)
            },
            moduleImages = emptyMap(),
            moduleItemImages = emptyMap(),
            variantCount = variantCount + if (createVariant) 1 else 0,
        )
    }

    previewData?.let { preview ->
        NovexDetailScaffold(
            title = "角色草稿预览",
            onBack = { previewData = null },
            actions = {
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_eye,
                    contentDescription = "返回编辑",
                    onClick = { previewData = null },
                )
            },
        ) {
            CharacterPrimaryContent(
                data = preview,
                onChooseVersion = null,
                onOpenModule = null,
                mediaModels = pendingImages.mapValues { it.value.uri },
            )
            Spacer(Modifier.height(32.dp))
        }
        return
    }

    NovexDetailScaffold(
        title = when {
            characterId == null -> "创建角色"
            createVariant -> "创建分身"
            else -> "编辑角色"
        },
        onBack = onBack,
        actions = {
            IconButton(onClick = ::preview, enabled = loaded && !draft.isBlank) {
                Icon(painterResource(R.drawable.ic_phosphor_eye), contentDescription = "预览角色草稿")
            }
        },
        bottomBar = {
            Button(
                onClick = ::save,
                enabled = loaded && !draft.isBlank && !saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text(if (saving) "保存中" else "保存") }
        },
    ) {
        if (!loaded) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            CharacterEditorSection("角色身份") {
                if (characterId != null) Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("正在编辑", color = NovexColors.SecondaryText, modifier = Modifier.weight(1f))
                    Text(
                        if (createVariant) "由${sourceVersion?.label ?: "本体"}创建新分身" else draft.label,
                        color = NovexColors.Text,
                    )
                }
                val editingVariant = createVariant || sourceVersion?.kind == CharacterVersionKind.VARIANT
                if (!editingVariant) {
                    OutlinedTextField(
                        value = draft.rootName,
                        onValueChange = { value -> draft = draft.copy(rootName = value, name = value) },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("所属角色", color = NovexColors.SecondaryText, modifier = Modifier.weight(1f))
                        Text(draft.rootName, color = NovexColors.Text)
                    }
                    OutlinedTextField(
                        value = draft.label,
                        onValueChange = { draft = draft.copy(label = it) },
                        label = { Text("分身名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text("分身中的姓名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            CharacterEditorSection(
                header = "视觉资源",
                footer = "头像和主页背景都可以留空；创建分身时默认沿用来源图片，保存后仍可独立更换。",
            ) {
                CharacterEditorFoldRow(
                    title = "头像与主页背景",
                    expanded = draft.visualExpanded,
                    onToggle = { draft = draft.copy(visualExpanded = !draft.visualExpanded) },
                )
                if (draft.visualExpanded) characterImageSlots().forEach { (slot, label) ->
                    val model = when (draft.imageChanges[slot]) {
                        is NovexImageChange.Remove -> null
                        is NovexImageChange.Replace -> pendingImages[slot]?.uri
                        null -> media[slot]?.managedPath.existingMediaFile()
                    }
                    CharacterDraftImageRow(
                        label = label,
                        imageModel = model,
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

            CharacterEditorSection(
                header = "可选资料",
                footer = "除名称外均可留空；收起只改变编辑界面，不影响最终展示。",
            ) {
                CharacterEditorFoldRow("基本信息、自定义属性与关系", optionalExpanded) {
                    optionalExpanded = !optionalExpanded
                }
                if (optionalExpanded) {
                    CharacterDraftField("标签（用顿号或逗号分隔）", draft.tagsText) { draft = draft.copy(tagsText = it) }
                    CharacterDraftField("性别", draft.gender) { draft = draft.copy(gender = it) }
                    CharacterDraftField("年龄", draft.age) { draft = draft.copy(age = it) }
                    CharacterDraftField("种族", draft.race) { draft = draft.copy(race = it) }
                    CharacterDraftField("职业", draft.occupation) { draft = draft.copy(occupation = it) }
                    CharacterDraftField("人物简介", draft.summary, 4) { draft = draft.copy(summary = it) }
                    CharacterDraftField("自定义属性（每行：名称：内容）", draft.attributesText, 4) {
                        draft = draft.copy(attributesText = it)
                    }
                    CharacterDraftField("原创角色关系（每行：角色｜关系｜说明）", draft.relationshipsText, 4) {
                        draft = draft.copy(relationshipsText = it)
                    }
                }
            }

            SharedContentModuleDraftEditor(
                scope = ContentModuleScope.CHARACTER_VERSION,
                modules = draft.modules,
                expandedModuleIds = draft.expandedModuleIds,
                persistedModuleIds = persistedModuleIds,
                onToggle = { draft = draft.toggleModule(it) },
                onAdd = { draft = draft.addModule(it) },
                onUpdate = { moduleId, moduleName, document ->
                    draft = draft.updateModule(moduleId, moduleName, document)
                },
                onMove = { moduleId, target -> draft = draft.moveModule(moduleId, target) },
                onDelete = { draft = draft.removeModule(it) },
                onOpenDetails = onOpenModule,
            )
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
private fun CharacterEditorSection(
    header: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            header,
            color = NovexColors.SecondaryText,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
        if (footer != null) Text(
            footer,
            color = NovexColors.SecondaryText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CharacterEditorFoldRow(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), color = NovexColors.Text)
        Text(if (expanded) "收起" else "展开", color = NovexColors.SecondaryText)
    }
}

@Composable
private fun CharacterDraftImageRow(
    label: String,
    imageModel: Any?,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (imageModel != null) AsyncImage(
            model = imageModel,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)),
        )
        Text(label, modifier = Modifier.weight(1f).padding(start = if (imageModel == null) 0.dp else 12.dp))
        TextButton(onClick = onPick) { Text(if (imageModel == null) "选择" else "更换") }
        if (imageModel != null) TextButton(onClick = onRemove) { Text("移除") }
    }
}

@Composable
private fun CharacterDraftField(label: String, value: String, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private fun characterImageSlots(): List<Pair<MediaAssetSlot, String>> = listOf(
    MediaAssetSlot.CHARACTER_AVATAR to "头像",
    MediaAssetSlot.CHARACTER_PAGE_BACKGROUND to "主页背景",
)

private fun NovexModuleDraft.toCharacterPreviewEntity(
    ownerId: String,
    position: Int,
    now: Long,
): ContentModuleEntity = ContentModuleEntity(
    id = id,
    ownerType = ModuleOwnerType.CHARACTER_VERSION,
    ownerId = ownerId,
    type = type,
    name = name,
    contentJson = contentJson,
    position = position,
    collapsed = collapsed,
    createdAt = now,
    updatedAt = now,
)
