package com.openminis.app.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexImageChange
import com.openminis.app.novex.domain.NovexInteractiveFictionSnapshot
import com.openminis.app.novex.domain.NovexModuleDraft
import com.openminis.app.novex.domain.requireInteractiveFiction
import com.openminis.app.novex.domain.requireNativeCard
import com.openminis.app.novex.domain.requireText
import com.openminis.app.ui.navigation.NovexEditorBackAction
import com.openminis.app.ui.navigation.novexEditorBackAction
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexContentModuleList
import com.openminis.app.ui.novex.NovexContentSection
import com.openminis.app.ui.novex.NovexDestructiveConfirmationDialog
import com.openminis.app.ui.novex.NovexDetailScaffold
import com.openminis.app.ui.novex.NovexDimensions
import com.openminis.app.ui.novex.NovexDraftPreviewScaffold
import com.openminis.app.ui.novex.NovexEditorFoldRow
import com.openminis.app.ui.novex.NovexEditorScaffold
import com.openminis.app.ui.novex.NovexEditorSection
import com.openminis.app.ui.novex.NovexInlineField
import com.openminis.app.ui.novex.NovexNoticeDialog
import com.openminis.app.ui.novex.NovexOptionalImageRow
import com.openminis.app.ui.novex.NovexPrimaryButton
import com.openminis.app.ui.novex.NovexSelectionAction
import com.openminis.app.ui.novex.NovexSelectionSheet
import com.openminis.app.ui.novex.NovexSummaryRow
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.novex.NovexTextField
import com.openminis.app.ui.novex.NovexTopAction
import com.openminis.app.ui.novex.rememberNovexWorkspace
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PendingInteractiveFictionImage(
    val uri: Uri,
    val bytes: ByteArray,
    val mimeType: String,
)

@Composable
fun CatalogInteractiveFictionDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenModule: (String) -> Unit,
    onStartConversation: () -> Unit,
    onShareToConversation: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val workspace = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<NovexInteractiveFictionSnapshot?>(null) }
    var missing by remember { mutableStateOf(false) }
    var creatorNotice by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(projectId) {
        snapshot = workspace.interactiveFiction(projectId)
        missing = snapshot == null
    }

    fun withFullText(action: (String) -> Unit) {
        scope.launch {
            runCatching {
                workspace.apply(NovexCommand.ExportInteractiveFictionText(projectId)).requireText()
            }.onSuccess(action).onFailure {
                notice = "操作失败" to (it.message ?: "无法读取文游全文")
            }
        }
    }

    val current = snapshot
    NovexDetailScaffold(
        title = current?.project?.name ?: "文游",
        onBack = onBack,
        actions = {
            if (current != null) {
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_sparkle,
                    contentDescription = "帮我创作",
                    label = "帮我创作",
                    onClick = { creatorNotice = true },
                )
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_pencil_simple,
                    contentDescription = "编辑文游",
                    label = "编辑",
                    onClick = onEdit,
                )
            }
        },
        bottomBar = {
            if (current != null) NovexPrimaryButton(
                label = "开始文游",
                onClick = onStartConversation,
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = NovexDimensions.PageHorizontal,
                    vertical = 10.dp,
                ),
            )
        },
    ) {
        when {
            missing -> Text("文游不存在或已删除", modifier = Modifier.padding(24.dp))
            current == null -> Box(
                Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = NovexColors.Primary, strokeWidth = 2.dp) }
            else -> {
                InteractiveFictionPrimaryContent(current, onOpenModule)
                NovexContentSection(title = "使用与分享") {
                    NovexTextActionRow(
                        label = "分享到新对话",
                        icon = R.drawable.ic_phosphor_plus,
                        onClick = { withFullText(onShareToConversation) },
                    )
                    NovexTextActionRow(
                        label = "复制全文",
                        onClick = {
                            withFullText {
                                clipboard.setText(AnnotatedString(it))
                                notice = "已复制" to "文游全文已复制到剪贴板。"
                            }
                        },
                    )
                    NovexTextActionRow(
                        label = "导出 Novex 文游卡",
                        icon = R.drawable.ic_phosphor_arrow_up,
                        onClick = {
                            scope.launch {
                                runCatching {
                                    workspace.apply(
                                        NovexCommand.ExportNativeInteractiveFiction(projectId),
                                    ).requireNativeCard()
                                }.onSuccess { shareNovexCardPackage(context, it) }
                                    .onFailure {
                                        notice = "导出失败" to (it.message ?: "无法导出文游卡")
                                    }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (creatorNotice) NovexNoticeDialog(
        title = "帮我创作",
        message = "入口已保留。人工智能管理文游模块会在后续检查点接入，本次点击不会改写内容。",
        onDismiss = { creatorNotice = false },
    )
    notice?.let { (title, message) ->
        NovexNoticeDialog(title, message) { notice = null }
    }
}

@Composable
fun CatalogInteractiveFictionEditorScreen(
    projectId: String?,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    val workspace = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var draft by remember(projectId) { mutableStateOf(InteractiveFictionEditorDraftState.create()) }
    var baselineDraft by remember(projectId) { mutableStateOf<InteractiveFictionEditorDraftState?>(null) }
    var media by remember { mutableStateOf<Map<MediaAssetSlot, MediaAssetEntity>>(emptyMap()) }
    var persistedModuleIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingImages by remember {
        mutableStateOf<Map<MediaAssetSlot, PendingInteractiveFictionImage>>(emptyMap())
    }
    var pendingSlot by remember { mutableStateOf<MediaAssetSlot?>(null) }
    var visualExpanded by remember { mutableStateOf(false) }
    var chooseLaunchMode by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var previewData by remember { mutableStateOf<NovexInteractiveFictionSnapshot?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    BackHandler(enabled = previewData != null) {
        when (novexEditorBackAction(previewVisible = true)) {
            NovexEditorBackAction.CLOSE_PREVIEW -> previewData = null
            NovexEditorBackAction.PROMPT_SAVE,
            NovexEditorBackAction.LEAVE_EDITOR,
            -> Unit
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
                val image = PendingInteractiveFictionImage(
                    uri = uri,
                    bytes = bytes,
                    mimeType = context.contentResolver.getType(uri) ?: "image/*",
                )
                pendingImages = pendingImages + (slot to image)
                draft = draft.replaceImage(slot, image.bytes, image.mimeType)
            }.onFailure { error = it.message }
        }
    }

    LaunchedEffect(projectId) {
        if (projectId == null) {
            val initial = InteractiveFictionEditorDraftState.create(
                name = nextDefaultInteractiveFictionName(
                    workspace.interactiveFictions().map { it.project.name },
                ),
            )
            draft = initial
            baselineDraft = initial
        } else {
            val snapshot = workspace.interactiveFiction(projectId)
            if (snapshot == null) {
                error = "文游不存在"
            } else {
                val initial = InteractiveFictionEditorDraftState.from(snapshot.project, snapshot.modules)
                draft = initial
                baselineDraft = initial
                media = snapshot.media
                persistedModuleIds = snapshot.modules.mapTo(mutableSetOf()) { it.id }
            }
        }
        loaded = true
    }

    fun save() {
        if (saving || draft.isBlank) return
        saving = true
        scope.launch {
            runCatching { workspace.apply(draft.toSaveCommand()).requireInteractiveFiction() }
                .onSuccess { onSaved(it.id) }
                .onFailure { error = it.message; saving = false }
        }
    }

    fun preview() {
        if (!loaded || draft.isBlank) return
        val now = System.currentTimeMillis()
        val visibleMedia = media.filterKeys { slot -> draft.imageChanges[slot] !is NovexImageChange.Remove }
        previewData = NovexInteractiveFictionSnapshot(
            project = InteractiveFictionProjectEntity(
                id = projectId ?: "draft-game",
                name = draft.name,
                summary = draft.summary,
                launchMode = draft.launchMode,
                playerIdentity = draft.playerIdentity,
                createdAt = draft.createdAt,
                updatedAt = now,
            ),
            media = visibleMedia,
            modules = draft.modules.mapIndexed { index, module ->
                module.toInteractiveFictionPreviewEntity(projectId ?: "draft-game", index, now)
            },
            moduleImages = emptyMap(),
            moduleItemImages = emptyMap(),
        )
    }

    previewData?.let { preview ->
        NovexDraftPreviewScaffold(
            title = "文游草稿预览",
            onBack = { previewData = null },
        ) {
            InteractiveFictionPrimaryContent(
                data = preview,
                onOpenModule = null,
                mediaModels = pendingImages.mapValues { it.value.uri },
            )
            Spacer(Modifier.height(32.dp))
        }
        return
    }

    NovexEditorScaffold(
        title = if (projectId == null) "创建文游" else "编辑文游",
        onBack = onBack,
        loaded = loaded,
        canSave = !draft.isBlank,
        saving = saving,
        baselineDraft = baselineDraft,
        currentDraft = draft,
        onPreview = ::preview,
        onSave = ::save,
        onDeleteRequest = projectId?.let { { confirmDelete = true } },
    ) {
        NovexEditorSection(
            header = "基础资料",
            footer = "名称之外都可以留空；启动方式只决定开始时如何引导对话。",
        ) {
            NovexInlineField(
                label = "名称",
                value = draft.name,
                placeholder = "文游名称",
                onValueChange = { draft = draft.copy(name = it) },
            )
            NovexTextField(
                label = "简介",
                value = draft.summary,
                onValueChange = { draft = draft.copy(summary = it) },
                minLines = 3,
            )
            NovexSummaryRow(
                title = "启动方式",
                summary = draft.launchMode.displayName,
                onClick = { chooseLaunchMode = true },
            )
            NovexTextField(
                label = "固定玩家身份（可留空）",
                value = draft.playerIdentity,
                onValueChange = { draft = draft.copy(playerIdentity = it) },
                minLines = 3,
            )
        }
        NovexEditorSection(
            header = "封面与背景",
            footer = "两张图片均可留空，也可以独立添加、更换或移除。",
        ) {
            NovexEditorFoldRow("文游封面与对话背景", visualExpanded) {
                visualExpanded = !visualExpanded
            }
            if (visualExpanded) interactiveFictionImageSlots.forEach { slot ->
                val model = when (draft.imageChanges[slot]) {
                    is NovexImageChange.Remove -> null
                    is NovexImageChange.Replace -> pendingImages[slot]?.uri
                    null -> media[slot]?.managedPath.existingMediaFile()
                }
                NovexOptionalImageRow(
                    label = if (slot == MediaAssetSlot.INTERACTIVE_FICTION_COVER) "文游封面" else "对话背景",
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
        SharedContentModuleDraftEditor(
            state = draft.contentModules,
            persistedModuleIds = persistedModuleIds,
            onChange = { draft = draft.copy(contentModules = it) },
            onOpenDetails = onOpenModule,
        )
        Spacer(Modifier.height(32.dp))
    }

    if (chooseLaunchMode) NovexSelectionSheet(
        title = "选择启动方式",
        onDismissRequest = { chooseLaunchMode = false },
        actions = InteractiveFictionLaunchMode.entries.map { mode ->
            NovexSelectionAction(mode.displayName) {
                draft = draft.copy(launchMode = mode)
                chooseLaunchMode = false
            }
        },
    )
    error?.let { message -> NovexNoticeDialog("操作失败", message) { error = null } }
    if (confirmDelete && projectId != null) NovexDestructiveConfirmationDialog(
        title = "删除文游？",
        message = "将删除共享文游及其模块；已经创建的对话快照不会被改写。仍被引用的图片受引用保护。此操作无法撤销。",
        confirming = deleting,
        onDismiss = { confirmDelete = false },
        onConfirm = {
            deleting = true
            scope.launch {
                runCatching { workspace.apply(NovexCommand.DeleteInteractiveFiction(projectId)) }
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

@Composable
private fun InteractiveFictionPrimaryContent(
    data: NovexInteractiveFictionSnapshot,
    onOpenModule: ((String) -> Unit)?,
    mediaModels: Map<MediaAssetSlot, Any?> = emptyMap(),
) {
    val cover = mediaModels[MediaAssetSlot.INTERACTIVE_FICTION_COVER]
        ?: data.media[MediaAssetSlot.INTERACTIVE_FICTION_COVER]?.managedPath.existingMediaFile()
    val background = mediaModels[MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND]
        ?: data.media[MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND]?.managedPath.existingMediaFile()
    Box(Modifier.fillMaxWidth().height(210.dp)) {
        if (background != null) AsyncImage(
            model = background,
            contentDescription = "${data.project.name}背景",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        ) else NovexArtwork(
            kind = NovexArtworkKind.INTERACTIVE_FICTION,
            seed = data.project.id,
            imageModel = cover,
            contentDescription = "${data.project.name}封面",
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.68f),
                ),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                data.project.name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                data.project.launchMode.displayName,
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(
            horizontal = NovexDimensions.PageHorizontal,
            vertical = 16.dp,
        ),
    ) {
        Text("文游简介", color = NovexColors.Text, fontWeight = FontWeight.SemiBold)
        Text(
            data.project.summary.ifBlank { "尚未填写文游简介" },
            color = if (data.project.summary.isBlank()) NovexColors.SecondaryText else NovexColors.Text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (data.project.playerIdentity.isNotBlank()) {
            Text(
                "玩家身份",
                color = NovexColors.Text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                data.project.playerIdentity,
                color = NovexColors.Text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    NovexContentModuleList(
        modules = data.modules,
        moduleImages = data.moduleImages.mapValues { it.value.managedPath.existingMediaFile() },
        moduleItemImages = data.moduleItemImages.mapValues { (_, images) ->
            images.mapValues { it.value.managedPath.existingMediaFile() }
        },
        onOpenModule = onOpenModule,
    )
}

private fun NovexModuleDraft.toInteractiveFictionPreviewEntity(
    ownerId: String,
    position: Int,
    now: Long,
) = ContentModuleEntity(
    id = id,
    ownerType = ModuleOwnerType.INTERACTIVE_FICTION,
    ownerId = ownerId,
    type = type,
    name = name,
    contentJson = contentJson,
    position = position,
    collapsed = collapsed,
    createdAt = now,
    updatedAt = now,
)
