package com.openminis.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.data.character.ContentModuleReferenceEntity
import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexModuleReferenceOption
import com.openminis.app.novex.domain.requireMedia
import com.openminis.app.ui.novex.NovexNoticeDialog
import com.openminis.app.ui.novex.NovexDraftExitBoundary
import com.openminis.app.ui.novex.NovexOutlineButton
import com.openminis.app.ui.novex.NovexPrimaryButton
import com.openminis.app.ui.novex.NovexSelectionAction
import com.openminis.app.ui.novex.NovexSelectionSheet
import com.openminis.app.ui.novex.NovexSettingsCustomRow
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.novex.NovexTextField
import com.openminis.app.ui.novex.NovexTopAction
import com.openminis.app.ui.novex.rememberNovexAttachedModuleImages
import com.openminis.app.ui.novex.rememberNovexWorkspace
import com.openminis.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A module owns a full page; the parent world/character page only shows its compact summary. */
@Composable
fun CatalogContentModuleDetailScreen(
    moduleId: String,
    onBack: () -> Unit,
    onHelpCreate: (NovexContentAddress) -> Unit,
) {
    val context = LocalContext.current
    val novex = rememberNovexWorkspace()
    val owner = remember(moduleId) { ModuleOwner.contentModule(moduleId) }
    val scope = rememberCoroutineScope()
    var module by remember(moduleId) { mutableStateOf<ContentModuleEntity?>(null) }
    var loaded by remember(moduleId) { mutableStateOf(false) }
    var name by rememberSaveable(moduleId) { mutableStateOf("") }
    var contentJson by rememberSaveable(moduleId) { mutableStateOf("{}") }
    var baselineName by rememberSaveable(moduleId) { mutableStateOf<String?>(null) }
    var baselineContent by rememberSaveable(moduleId) { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<MediaAssetEntity?>(null) }
    var itemImages by remember { mutableStateOf<Map<String, MediaAssetEntity>>(emptyMap()) }
    var references by remember { mutableStateOf<List<ContentModuleReferenceEntity>>(emptyList()) }
    var referenceOptions by remember { mutableStateOf<List<NovexModuleReferenceOption>>(emptyList()) }
    var referenceRefresh by remember { mutableStateOf(0) }
    var addReference by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val attachedArtifactImages = rememberNovexAttachedModuleImages(module?.managementOwnerAddress())

    var imagePickerOwner by remember { mutableStateOf<ModuleOwner?>(null) }
    fun refreshImages(detail: com.openminis.app.novex.domain.NovexModuleDetail?) {
        if (detail == null) return
        image = detail.image; itemImages = detail.itemImages
        contentJson = com.openminis.app.novex.domain.NovexModuleImageOrigins.copyFrom(detail.module.contentJson, contentJson)
        baselineContent = baselineContent?.let { com.openminis.app.novex.domain.NovexModuleImageOrigins.copyFrom(detail.module.contentJson, it) }
    }
    imagePickerOwner?.let { target ->
        com.openminis.app.ui.novex.NovexModuleImagePicker(onDismiss = { imagePickerOwner = null }) { candidate ->
            novex.apply(NovexCommand.AttachImage(target, MediaAssetSlot.MODULE_IMAGE, candidate.bytes,
                candidate.mimeType, source = candidate.source)).requireMedia()
            refreshImages(novex.module(moduleId))
        }
    }

    LaunchedEffect(moduleId, referenceRefresh) {
        novex.module(moduleId)?.let { detail ->
            module = detail.module
            // Refreshing image/reference metadata must not overwrite unsaved text.
            if (baselineName == null) {
                name = detail.module.name
                contentJson = detail.module.contentJson
                baselineName = name
                baselineContent = contentJson
            }
            image = detail.image
            itemImages = detail.itemImages
            references = detail.references
            referenceOptions = detail.referenceOptions
        }
        loaded = true
    }

    fun save() {
        if (saving || name.isBlank()) return
        saving = true
        scope.launch {
            runCatching {
                novex.apply(
                    NovexCommand.SaveModule(moduleId, name, contentJson),
                )
            }.onSuccess { onBack() }.onFailure {
                saving = false
                error = it.message
            }
        }
    }

    NovexDraftExitBoundary(
        baselineDraft = baselineName?.let { it to baselineContent },
        currentDraft = name to contentJson,
        saving = saving,
        onBack = onBack,
        onSaveAndExit = ::save,
    ) { requestBack ->
    SettingsScaffold(
        title = module?.name ?: "模块",
        onBack = requestBack,
        actions = {
            NovexTopAction(
                icon = R.drawable.ic_phosphor_sparkle,
                contentDescription = "帮我创作",
                label = "帮我创作",
                onClick = {
                    if (name != baselineName || contentJson != baselineContent) {
                        error = "请先保存当前模块修改，再进入帮我创作。草稿仍保留在此页面。"
                    } else module?.managementOwnerAddress()?.let(onHelpCreate)
                },
            )
        },
    ) {
        when {
            !loaded -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            module == null -> Text("模块不存在或已删除", modifier = Modifier.padding(24.dp))
            else -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                val artifactImage = attachedArtifactImages[moduleId]
                val displayedImage = image?.managedPath.existingMediaFile() ?: artifactImage
                displayedImage?.let { file ->
                    AsyncImage(
                        model = file,
                        contentDescription = "${name}代表图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp)),
                    )
                }
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    NovexOutlineButton(
                        label = when {
                            image != null -> "更换代表图"
                            artifactImage != null -> "添加本地代表图（覆盖）"
                            else -> "添加代表图（可选）"
                        },
                        onClick = {
                            imagePickerOwner = owner
                        },
                    )
                    if (image != null) {
                        NovexOutlineButton(
                            label = "移除",
                            danger = true,
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        novex.apply(NovexCommand.DetachImage(owner, MediaAssetSlot.MODULE_IMAGE))
                                    }.onSuccess {
                                        image = null
                                        val latest = novex.module(moduleId)?.module?.contentJson ?: contentJson
                                        contentJson = com.openminis.app.novex.domain.NovexModuleImageOrigins.copyFrom(latest, contentJson)
                                        baselineContent = baselineContent?.let { com.openminis.app.novex.domain.NovexModuleImageOrigins.copyFrom(latest, it) }
                                    }.onFailure { error = it.message }
                                }
                            },
                        )
                    }
                }
                if (image != null) com.openminis.app.ui.novex.NovexIllustrationConditionField(contentJson, requireNotNull(module).type, "main") { contentJson = it }
                if (image == null && artifactImage != null) {
                    Text(
                        "当前代表图来自创作成果库。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                NovexTextField(
                    label = "模块名称",
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                if (module?.managementOwnerAddress()?.kind == com.openminis.app.novex.domain.NovexContentKind.WORLD) {
                    com.openminis.app.ui.novex.NovexWorldbookConditionField(contentJson, requireNotNull(module).type) { contentJson = it }
                }
                SharedModuleDocumentFields(
                    document = ContentModuleDocumentCodec.decode(requireNotNull(module).type, contentJson),
                    allowWorldbookConditions = module?.managementOwnerAddress()?.kind == com.openminis.app.novex.domain.NovexContentKind.WORLD,
                    itemImageContent = { item ->
                        val persisted = (ContentModuleDocumentCodec.decode(requireNotNull(module).type, requireNotNull(module).contentJson)
                            as? com.openminis.app.data.character.ContentModuleDocument.Collection)?.items.orEmpty().any { it.id == item.id }
                        val target = ModuleOwner.contentModuleItem(moduleId, item.id)
                        itemImages[item.id]?.managedPath?.existingMediaFile()?.let { file ->
                            AsyncImage(model = file, contentDescription = "${item.name}图片", contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 180.dp))
                        }
                        if (itemImages[item.id] != null) com.openminis.app.ui.novex.NovexIllustrationConditionField(contentJson, requireNotNull(module).type, "entry:${item.id}") { contentJson = it }
                        Row {
                            NovexOutlineButton(label = if (!persisted) "保存条目后添加图片" else if (itemImages[item.id] == null) "添加图片" else "更换图片",
                                enabled = persisted, onClick = { imagePickerOwner = target })
                            if (itemImages[item.id] != null) NovexOutlineButton(label = "移除图片", danger = true, onClick = {
                                scope.launch { runCatching { novex.apply(NovexCommand.DetachImage(target, MediaAssetSlot.MODULE_IMAGE)); refreshImages(novex.module(moduleId)) }
                                    .onFailure { error = it.message } }
                            })
                        }
                    },
                    onChange = { contentJson = ContentModuleDocumentCodec.edit(contentJson, it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                module?.managementOwnerAddress()?.let { address ->
                    com.openminis.app.ui.novex.NovexCardReferenceSection(address, sourceModuleId = moduleId)
                }
                NovexPrimaryButton(
                    label = if (saving) "保存中" else "保存",
                    onClick = ::save,
                    enabled = name.isNotBlank() && !saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                )
                Text(
                    "代表图和内容都可留空。正文点击保存后生效；图片与引用操作即时保存。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                SettingsSection(
                    header = "内容引用",
                    footer = "引用世界、角色版本或其他内容模块，正文无需重复填写。",
                ) {
                    references.forEach { reference ->
                        val target = reference.target
                        val option = referenceOptions.firstOrNull { it.target == target }
                        NovexSettingsCustomRow(
                            title = option?.label ?: target.id,
                            subtitle = option?.kindLabel ?: "引用目标",
                            showChevron = false,
                            trailing = {
                                NovexOutlineButton(label = "移除", danger = true, onClick = {
                                scope.launch {
                                    runCatching {
                                        novex.apply(NovexCommand.RemoveModuleReference(moduleId, target))
                                    }.onSuccess { referenceRefresh++ }
                                        .onFailure { error = it.message }
                                }
                                })
                            },
                        )
                    }
                    NovexTextActionRow(
                        label = "添加引用",
                        onClick = { addReference = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
    }
    if (addReference) {
        val available = referenceOptions.filterNot { option ->
            references.any { it.target == option.target }
        }
        NovexSelectionSheet(
            title = "添加内容引用",
            onDismissRequest = { addReference = false },
            actions = if (available.isEmpty()) {
                listOf(NovexSelectionAction("没有可添加的引用", icon = R.drawable.ic_phosphor_info) {})
            } else {
                available.map { option ->
                    NovexSelectionAction("${option.kindLabel} · ${option.label}") {
                        scope.launch {
                            runCatching {
                                novex.apply(
                                    NovexCommand.AddModuleReference(
                                        moduleId,
                                        option.target,
                                        references.size,
                                    ),
                                )
                            }.onSuccess { referenceRefresh++ }
                                .onFailure { error = it.message }
                        }
                    }
                }
            },
        )
    }
    error?.let { message ->
        NovexNoticeDialog("操作失败", message ?: "未知错误") { error = null }
    }
}

private fun ContentModuleEntity.managementOwnerAddress(): NovexContentAddress = when (owner.type) {
    ModuleOwnerType.WORLD -> NovexContentAddress.world(owner.id)
    ModuleOwnerType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(owner.id)
    ModuleOwnerType.INTERACTIVE_FICTION -> NovexContentAddress.interactiveFiction(owner.id)
    ModuleOwnerType.CONTENT_MODULE -> error("内容模块没有独立管理根对象")
}
