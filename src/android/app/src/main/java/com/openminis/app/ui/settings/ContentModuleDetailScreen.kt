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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleTextCodec
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ContentModuleReferenceEntity
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexModuleReferenceOption
import com.openminis.app.novex.domain.requireMedia
import com.openminis.app.ui.novex.NovexNoticeDialog
import com.openminis.app.ui.novex.NovexOutlineButton
import com.openminis.app.ui.novex.NovexPrimaryButton
import com.openminis.app.ui.novex.NovexSelectionAction
import com.openminis.app.ui.novex.NovexSelectionSheet
import com.openminis.app.ui.novex.NovexSettingsCustomRow
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.novex.NovexTextField
import com.openminis.app.ui.novex.NovexTopAction
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
) {
    val context = LocalContext.current
    val novex = rememberNovexWorkspace()
    val owner = remember(moduleId) { ModuleOwner.contentModule(moduleId) }
    val scope = rememberCoroutineScope()
    var module by remember { mutableStateOf<ContentModuleEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<MediaAssetEntity?>(null) }
    var references by remember { mutableStateOf<List<ContentModuleReferenceEntity>>(emptyList()) }
    var referenceOptions by remember { mutableStateOf<List<NovexModuleReferenceOption>>(emptyList()) }
    var referenceRefresh by remember { mutableStateOf(0) }
    var addReference by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var creatorNotice by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取图片")
                }
                val asset = novex.apply(
                    NovexCommand.AttachImage(
                        owner,
                        MediaAssetSlot.MODULE_IMAGE,
                        bytes,
                        context.contentResolver.getType(uri) ?: "image/*",
                    ),
                ).requireMedia()
                image = asset
            }.onFailure { error = it.message }
        }
    }

    LaunchedEffect(moduleId, referenceRefresh) {
        novex.module(moduleId)?.let { detail ->
            module = detail.module
            name = detail.module.name
            body = ContentModuleTextCodec.decode(detail.module.contentJson)
            image = detail.image
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
                    NovexCommand.SaveModule(moduleId, name, ContentModuleTextCodec.encode(body)),
                )
            }.onSuccess { onBack() }.onFailure {
                saving = false
                error = it.message
            }
        }
    }

    SettingsScaffold(
        title = module?.name ?: "模块",
        onBack = onBack,
        actions = {
            NovexTopAction(
                icon = R.drawable.ic_phosphor_sparkle,
                contentDescription = "帮我创作",
                label = "帮我创作",
                onClick = { creatorNotice = true },
            )
        },
    ) {
        when {
            !loaded -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            module == null -> Text("模块不存在或已删除", modifier = Modifier.padding(24.dp))
            else -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                image?.managedPath.existingMediaFile()?.let { file ->
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
                        label = if (image == null) "添加代表图（可选）" else "更换代表图",
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    if (image != null) {
                        NovexOutlineButton(
                            label = "移除",
                            danger = true,
                            onClick = {
                                scope.launch {
                                    novex.apply(NovexCommand.DetachImage(owner, MediaAssetSlot.MODULE_IMAGE))
                                    image = null
                                }
                            },
                        )
                    }
                }
                NovexTextField(
                    label = "模块名称",
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                NovexTextField(
                    label = "内容（可选）",
                    value = body,
                    onValueChange = { body = it },
                    minLines = 12,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                NovexPrimaryButton(
                    label = if (saving) "保存中" else "保存",
                    onClick = ::save,
                    enabled = name.isNotBlank() && !saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                )
                Text(
                    "代表图和内容都可留空；之后仍可随时补充。",
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
    if (creatorNotice) {
        NovexNoticeDialog(
            title = "帮我创作",
            message = "入口已保留，人工智能管理与写入本轮暂不开放，点击不会修改任何内容。",
            onDismiss = { creatorNotice = false },
        )
    }
    error?.let { message ->
        NovexNoticeDialog("操作失败", message ?: "未知错误") { error = null }
    }
}
