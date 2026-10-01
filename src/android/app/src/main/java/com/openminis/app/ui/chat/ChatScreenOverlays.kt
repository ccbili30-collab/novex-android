package com.openminis.app.ui.chat

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.core.ConversationControlBehavior
import novex.core.NovexLearningControl
import novex.core.NovexLearningControlPolicy
import novex.core.NovexLearningTaskStatus
import novex.core.PlaythroughValue
import novex.android.ui.NovexNoticeDialog
import novex.android.ui.NovexDecisionAction
import novex.android.ui.NovexDecisionDialog
import novex.android.ui.NovexDecisionTone
import novex.android.ui.NovexSelectionAction
import novex.android.ui.NovexSelectionSheet
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import novex.android.ui.DropdownMenuItem
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import novex.android.ui.NovexActionMenu
import novex.android.ui.NovexMenuAction
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import novex.android.ui.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import novex.android.ui.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import novex.android.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelGroup
import novex.android.data.model.ProviderConfig
import novex.android.data.model.ProviderType
import novex.android.data.model.RoutingStrategy
import novex.android.data.model.ThinkingLevel
import com.openminis.app.data.character.effectiveAssistantAvatarPath
import com.openminis.app.data.character.effectiveAssistantName
import com.openminis.app.data.character.usesRolePresentation
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.components.MinisTextButton

/**
 * 会话页尾的对话框/Sheet 宿主：思维级别、模型选择、资料学习全家、
 * 记忆面板、导出/检查点、图库等。原先是 ChatScreen 函数体的尾部 220 行，
 * 抽出后 VM 侧状态在本文件内 collect，主函数只传开关位。
 */
@Composable
internal fun ChatScreenOverlays(
    viewModel: ChatViewModel,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository?,
    availableGroups: List<ModelGroup>,
    onSettings: () -> Unit,
    onBrowseChatFiles: (String) -> Unit,
    onModelGroupsClick: () -> Unit,
    showThinkingLevelSheet: Boolean,
    onThinkingDismissed: () -> Unit,
    showModelPicker: Boolean,
    onModelPickerDismissed: () -> Unit,
    previewGallery: Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int>?,
    onGalleryDismissed: () -> Unit,
) {
    val showMemorySheet by viewModel.showMemorySheet.collectAsState()
    val memoryToolRecords by viewModel.memoryToolRecords.collectAsState()
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val pendingNovexLearningPreflight by viewModel.pendingNovexLearningPreflight.collectAsState()
    val novexLearningTask by viewModel.novexLearningTask.collectAsState()
    val novexLearningError by viewModel.novexLearningError.collectAsState()
    val novexLearningResponsePreview by viewModel.novexLearningResponsePreview.collectAsState()
    val novexLearningDetails by viewModel.novexLearningDetails.collectAsState()
    val novexConversationExport by viewModel.novexConversationExport.collectAsState()
    val novexCheckpoints by viewModel.novexCheckpoints.collectAsState()
    val novexLearningReadCoverage by viewModel.novexLearningReadCoverage.collectAsState()
    val novexLearningCollections by viewModel.novexLearningCollections.collectAsState()
    // [P3.3 裁军] 内置浏览器底部 Sheet（BrowserSheet/BrowserTabPool）随
    // browser/ + ui/browser/ 整包退役删除。

    // Memory bottom sheet
    if (showMemorySheet && memoryRepository != null) {
        SessionMemorySheet(
            memoryRepository = memoryRepository,
            toolRecords = memoryToolRecords,
            onDismiss = { viewModel.dismissMemorySheet() },
            onRevokeRecord = { record -> viewModel.revokeMemoryRecord(record) },
            onSaveRecord = { record, newContent -> viewModel.replaceMemoryRecord(record, newContent) },
        )
    }

    // [T-android-thinking-badge-navbar] Thinking-level sheet opened by tapping
    // the navbar thinking badge. Mirrors iOS ThinkingLevelSheetView: an Off row
    // plus every level the current model supports, each selectable.
    if (showThinkingLevelSheet) {
        val currentThinkingLevel by viewModel.thinkingLevel.collectAsState()
        ThinkingLevelSheet(
            currentLevel = currentThinkingLevel,
            availableLevels = viewModel.availableThinkingLevels,
            onSelect = { level ->
                viewModel.setThinkingLevel(level)
                onThinkingDismissed()
            },
            onDismiss = { onThinkingDismissed() },
        )
    }

    // Model Picker bottom sheet
    val modelSetupRequired by viewModel.modelSetupRequired.collectAsState()
    if (modelSetupRequired) {
        novex.android.ui.NovexContentDialog(
            title = "连接模型后再发送",
            onDismiss = viewModel::dismissModelSetup,
            confirmButton = {
                novex.android.ui.TextButton(onClick = {
                    viewModel.dismissModelSetup()
                    onSettings()
                }) { Text("连接模型") }
            },
            dismissButton = {
                novex.android.ui.TextButton(onClick = viewModel::dismissModelSetup) { Text("继续编辑") }
            },
        ) { Text("先在设置中连接一个可用模型。当前文字和附件会留在输入框中，不会自动发送。") }
    }
    if (showModelPicker) {
        val config by providerRepository.config.collectAsState()
        val activeEntryId by viewModel.activeEntryId.collectAsState()
        ChatModelSelectionSheet(
            // 生图专用分组不进文字聊天选择器：这类分组（生图来源组、迁移组
            // "已迁移生图"）只含图像输出模型、在分组管理页被刻意隐藏删不到，
            // 泄漏进聊天选择器就是"模型分组删完了还有"的残留观感。
            groups = availableGroups.filterNot { it.id in config.imageGenerationGroupIds },
            selectedGroupId = selectedGroupId,
            activeEntryId = activeEntryId,
            config = config,
            providerRepository = providerRepository,
            onSelectGroup = viewModel::selectGroup,
            onSelectGroupEntry = viewModel::selectGroupEntry,
            onSelectEntry = viewModel::selectEntry,
            onDismiss = onModelPickerDismissed,
            onEditGroups = onModelGroupsClick,
        )
    }

    // [P3.3 裁军] OffloadPermissionDialog 随 offload/ 退役删除。

    pendingNovexLearningPreflight?.let { preflight ->
        val scope = NovexLearningControlPolicy.preflightMessage(preflight)
        NovexDecisionDialog(
            title = "开始整理资料？",
            message = scope,
            onDismiss = viewModel::dismissNovexLearningPreflight,
            actions = listOf(
                NovexDecisionAction(
                    label = "确认并开始整理",
                    icon = R.drawable.ic_phosphor_brain,
                    tone = NovexDecisionTone.PRIMARY,
                    onClick = { viewModel.confirmNovexLearning(preflight.id) },
                ),
                NovexDecisionAction(
                    label = "稍后再说",
                    icon = R.drawable.ic_phosphor_arrow_left,
                    onClick = viewModel::dismissNovexLearningPreflight,
                ),
            ),
        )
    }

    novexLearningResponsePreview?.let { message ->
        NovexNoticeDialog(title = "最近一次模型返回", message = message,
            onDismiss = viewModel::closeNovexLearningResponsePreview)
    }
    novexConversationExport?.let { novex.android.ui.NovexConversationExportDialog(it,
        viewModel::closeNovexConversationExport, viewModel::prepareNovexConversationExport) }
    novexCheckpoints?.let { NovexCheckpointDetails(it, viewModel::closeNovexCheckpoints) }
    if (novexLearningResponsePreview == null && novexLearningDetails == null && novexLearningCollections == null && pendingNovexLearningPreflight == null) novexLearningError?.let { message ->
        NovexDecisionDialog(
            title = "资料整理已停止",
            message = "$message\n已完成的通读进度和笔记仍然保留。",
            onDismiss = viewModel::clearNovexLearningError,
            actions = listOf(
                NovexDecisionAction("资料与整理计划", R.drawable.ic_phosphor_brain,
                    onClick = viewModel::showNovexLearningDetails),
                NovexDecisionAction("返回任务", R.drawable.ic_phosphor_arrow_left,
                    onClick = viewModel::clearNovexLearningError),
            ),
        )
    }

    if (novexLearningResponsePreview == null && pendingNovexLearningPreflight == null) {
        novexLearningCollections?.let { states ->
            if (states.isEmpty()) NovexNoticeDialog("资料整理", "本分支尚无已导入资料集。添加文档后可在这里查看整理记录。",
                viewModel::closeNovexLearningDetails)
            else novex.android.ui.NovexSearchableSelectionSheet("资料整理进度", states.map { state ->
                novex.android.ui.NovexSelectionAction(state.collection.title,
                    description = "已整理 ${state.reviewLedger.reviewedBlocks} / ${state.reviewLedger.totalReadableBlocks} 个可读块") {
                    viewModel.selectNovexLearningCollection(state.collection.ref)
                }
            }, "搜索资料集", onDismissRequest = viewModel::closeNovexLearningDetails)
        }
        novexLearningDetails?.let { state ->
            NovexLearningDetailsDialog(state, novexLearningReadCoverage, viewModel::closeNovexLearningDetails,
                viewModel::previewLatestNovexLearningResponse, viewModel::requestNovexLearningContinuation,
                onFiles = { viewModel.prepareNovexLearningFiles { savedSessionId ->
                    viewModel.closeNovexLearningDetails(); onBrowseChatFiles(savedSessionId)
                } })
        }
    }
    if (novexLearningError == null && novexLearningResponsePreview == null && novexLearningDetails == null && novexLearningCollections == null && pendingNovexLearningPreflight == null) {
        novexLearningTask?.let { task ->
            val message = NovexLearningControlPolicy.progressMessage(task)
            val controls = NovexLearningControlPolicy.allowedControls(task.status)
            NovexDecisionDialog(
                title = "资料学习",
                message = message,
                onDismiss = {
                    if (NovexLearningControl.PAUSE in controls) viewModel.pauseNovexLearning()
                },
                actions = buildList {
                    add(NovexDecisionAction("资料与整理计划", R.drawable.ic_phosphor_brain,
                        onClick = viewModel::showNovexLearningDetails))
                    if (NovexLearningControl.PAUSE in controls) add(
                        NovexDecisionAction(
                            label = "暂停整理",
                            icon = R.drawable.ic_phosphor_arrow_left,
                            onClick = viewModel::pauseNovexLearning,
                        ),
                    )
                    if (NovexLearningControl.RESUME in controls) add(
                        NovexDecisionAction(
                            label = "继续整理",
                            icon = R.drawable.ic_phosphor_brain,
                            tone = NovexDecisionTone.PRIMARY,
                            onClick = viewModel::resumeNovexLearning,
                        ),
                    )
                    if (NovexLearningControl.EXTEND_BUDGET in controls) add(
                        NovexDecisionAction(
                            label = "增加预算并继续",
                            icon = R.drawable.ic_phosphor_brain,
                            tone = NovexDecisionTone.PRIMARY,
                            onClick = viewModel::requestNovexLearningBudgetExtension,
                        ),
                    )
                    if (NovexLearningControl.CANCEL in controls) add(
                        NovexDecisionAction(
                            label = "取消整理",
                            icon = R.drawable.ic_phosphor_trash,
                            tone = NovexDecisionTone.DESTRUCTIVE,
                            onClick = viewModel::cancelNovexLearning,
                        ),
                    )
                    if (NovexLearningControl.DISMISS in controls) add(
                        NovexDecisionAction(
                            label = "知道了",
                            icon = R.drawable.ic_phosphor_check,
                            tone = NovexDecisionTone.PRIMARY,
                            onClick = viewModel::dismissNovexLearningTaskNotice,
                        ),
                    )
                },
            )
        }
    }

    // [P3.3 裁军] UrlPreviewSheet（内预览）与 WebPreview 沉浸式 HTML 预览
    // （WebPreviewBottomSheet/WebPreviewFullscreenScreen/WebViewHolder）随
    // 内置浏览器全家退役删除；链接点击已在 urlClickHandler 收口外跳。

    // T279: sandbox file preview is now routed through the NavHost
    // FILE_PREVIEW destination via onPreviewAttachment (see line ~1103),
    // matching how user-bubble attachments and "Browse Chat Files" already work.
    // The old in-place Dialog wrapper here was the source of the gray
    // status/nav bars — a Compose Dialog creates its own Window that
    // doesn't inherit MainActivity's enableEdgeToEdge, so the platform
    // default scrim painted over the bars regardless of what
    // FilePreviewScreen itself did.

    // Fullscreen image gallery — tapped image link from chat markdown or
    // composer chip. Pager-backed so multi-image messages support iOS-
    // style swipe between images. Single-image case is a 1-item list.
    previewGallery?.let { (items, startIdx) ->
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = items,
            startIndex = startIdx,
            onDismiss = onGalleryDismissed,
        )
    }

    // [P3.3 裁军] 视频全屏内嵌播放器（MinisFullscreenVideoPlayer）与
    // WebApp「添加到主屏」Sheet（AddToHomeSheet）随对应体系退役删除；
    // 视频链接改经 openMediaFileExternally 外跳系统播放器。

}
