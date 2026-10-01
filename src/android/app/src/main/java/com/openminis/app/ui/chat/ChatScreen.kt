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

// [P3.3 裁军] 语音全家（speech/ + novex.android.voice/ + ui/chat/voice/）、
// 内置浏览器全家（browser/ + ui/browser/ + ui/preview/ + UrlPreviewSheet）、
// WebApp（webapp/）、内嵌媒体播放器（ui/media/InlineMediaPlayer）按用户
// 裁决整体退役：朗读/语音面板/麦克风按钮/浏览器 Sheet/HTML 沉浸预览/
// 桌面捷径/内嵌音视频播放等入口全部摘除；链接点击改走
// openExternalUrl/openMediaFileExternally 外跳系统处理器。

// iOS ChatColors equivalent
internal val ToolCheckColor = Color(0xFF34C759) // iOS .green
internal val ToolErrorColor = Color(0xFFFF3B30) // iOS .red
internal val ToolCancelColor = Color(0xFFFFCC00) // iOS .yellow
// Memory tool accent — matches iOS `.pink` on SF Symbols.
internal val ToolMemoryAccent = Color(0xFFFF2D55)
// Sparkle gradient colors (iOS uses linear gradient)
internal val SparkleColor1 = Color(0xFFB8B096) // rgb(0.72, 0.69, 0.59)
internal val SparkleColor2 = Color(0xFF99998C) // rgb(0.6, 0.6, 0.55)

// T129: cap photo/video and file pickers at 50 items per launch. Above this
// count Android's PickMultipleVisualMedia silently truncates anyway, but our
// document picker has no native cap — so we apply the same limit on both
// sides and toast the user when their selection is trimmed. Mirrors iOS
// PHPickerConfiguration.selectionLimit = 50.
private const val ATTACHMENT_PICK_LIMIT = 50

/**
 * [T-android-send-no-autoscroll-behind-preview] Follow-grace window after a
 * user message append: within it the reserve-change pin bypasses the
 * isNearBottom gate (send intent is unambiguous; the freshly-inserted rows
 * make the live anchor transiently read "not at bottom").
 */
/**
 * [T-slash-picker-fixed-height port from iOS 73f1b94a] Locked popup
 * height for the slash and mention pickers: up to 4 rows are visible,
 * any overflow scrolls. Computed as `rowHeight * visibleRows + 8dp`.
 * [T-android-slash-menu-density] Rows were tightened (vertical padding
 * 10→7dp) so rowHeight ≈ 42dp covers a 14sp title + 11sp subtitle + 7dp
 * vertical padding; 42*4 + 8 ≈ 176dp. Keeps 4 rows visible with no extra
 * blank space at the bottom.
 */
internal val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp

/**
 * Draw a thin scroll thumb on the right edge of a [LazyColumn] (or any
 * scrollable) so the user can see at a glance that the list overflows
 * and is scrollable — mirrors iOS `.scrollIndicators(.visible)` which
 * Compose does not provide out of the box for LazyColumn.
 *
 * The thumb fades in while scrolling / shortly after, similar to the
 * platform scrollbar.
 */
internal fun Modifier.verticalScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    width: Dp = 3.dp,
    color: Color = Color(0x55888888),
): Modifier = this.then(Modifier.drawWithContent {
    drawContent()
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return@drawWithContent
    if (visibleItems.size >= totalItems &&
        visibleItems.first().index == 0 &&
        visibleItems.last().index == totalItems - 1 &&
        visibleItems.first().offset >= 0
    ) {
        // Fully visible, no scroll possible — no thumb.
        return@drawWithContent
    }
    val firstIndex = visibleItems.first().index
    val firstOffsetPx = visibleItems.first().offset.toFloat()
    val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentPx = avgItemSize * totalItems
    val viewportHeight = this.size.height
    if (totalContentPx <= viewportHeight || avgItemSize <= 0f) return@drawWithContent
    val scrollOffsetPx = firstIndex * avgItemSize - firstOffsetPx
    val thumbHeight = (viewportHeight * (viewportHeight / totalContentPx)).coerceAtLeast(24f)
    val maxScroll = (totalContentPx - viewportHeight).coerceAtLeast(1f)
    val maxTop = (viewportHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = (scrollOffsetPx / maxScroll * maxTop).coerceIn(0f, maxTop)
    val widthPx = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(this.size.width - widthPx - 1f, thumbTop),
        size = androidx.compose.ui.geometry.Size(widthPx, thumbHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(widthPx / 2, widthPx / 2),
    )
})

@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
fun ChatScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    onBack: () -> Unit,
    onBackReturnsToList: Boolean = false,
    /** [T-new-chat-menu-entry] "New Chat" from the chat "..." menu: caller
     *  navigates to a fresh draft chat (same funnel as the session list's
     *  new-chat button), replacing this chat on the back stack. */
    onNewChat: (
        worldId: String?,
        characterId: String?,
        characterVersionId: String?,
        personaId: String?,
    ) -> Unit = { _, _, _, _ -> },
    onSettings: () -> Unit = {},
    /** "Move to…" capsule (T51): called when the user picks a target session
     *  from MoveToSessionSheet after a share-injected turn. The caller is
     *  responsible for navigating; this screen has already stashed the
     *  pending transfer in [ChatViewModelStore.stashPendingTransfer]. */
    onMoveToSession: (sessionId: String) -> Unit = {},
    onImportCard: (android.net.Uri,Boolean)->Unit = {_,_->},
    onBrowseChatFiles: (String) -> Unit = {},
    onOpenCreatedCard: (kind: String, id: String) -> Unit = { _, _ -> },
    /** T150: open FilePreviewScreen for a non-image attachment in a user bubble. */
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit = {},
    /** [T-android-modelpicker-group-edit] Navigate to the Model Groups
     *  management screen — wired to the "Edit" button on the model picker's
     *  Model Groups section header. */
    onModelGroupsClick: () -> Unit = {},
    /** 侧边对话（2026-09-14 决策 12）：点书签或新建后全屏进入该侧边会话；
     *  调用方负责导航（Routes.chat(sideId)），返回键自然回到主线。 */
    onOpenSideSession: (sessionId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val appearancePrefs = remember { com.openminis.app.ui.settings.getAppearancePrefs(context) }
    // 输入栏交互状态袋（与 ChatComposerSection 共享，原 remember var 的托管位）
    val composerEnv = remember {
        ChatComposerEnv().apply {
            showContextMeter.value = appearancePrefs.getBoolean(
                com.openminis.app.ui.settings.KEY_SHOW_CONTEXT_METER, true)
        }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Scoped to a process-level per-session ViewModelStore (ChatViewModelStore)
    // so the ViewModel and its viewModelScope survive:
    //   - configuration changes (rotation — NavBackStackEntry still alive)
    //   - leaving the chat screen via popBackStack (NavBackStackEntry destroyed)
    // The VM is released only when the session is deleted (see SessionListViewModel).
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
        ),
    )
    // [T-android-larky-longsession-followup] Consume the tail-windowed
    // view instead of the canonical full list. For sessions with ≤300
    // messages this is the SAME reference (zero overhead); for longer
    // sessions (Larky's 600+) it caps at INITIAL_VISIBLE_MESSAGE_CAP and
    // grows in steps when the user reaches the top via [viewModel.loadOlderMessages].
    // Callers needing the full history (compact / fork / regenerate / send)
    // continue to read viewModel.messages directly inside the VM.
    val gameEntryState by viewModel.gameEntryState.collectAsState()
    val messages by viewModel.uiMessages.collectAsState()
    val toolApprovals by viewModel.pendingToolApprovals.collectAsState()
    val perTurnPrompt by viewModel.perTurnPrompt.collectAsState()
    LaunchedEffect(sessionId) { viewModel.restoreToolApprovals() }
    toolApprovals.firstOrNull()?.let { operation ->
        NovexToolApprovalDialog(operation,
            onApprove = { viewModel.decideToolOperation(operation, true) },
            onReject = { viewModel.decideToolOperation(operation, false) })
    }
    val compactDividerId = remember(messages) {
        messages.lastOrNull { message ->
            message.toolBlocks.firstOrNull()?.toolName == "compact"
        }?.id
    }
    var compactedHistoryExpanded by remember(sessionId) {
        mutableStateOf(true)
    }
    val transcriptMessages = remember(messages, compactedHistoryExpanded) {
        conversationMessagesForDisplay(messages, compactedHistoryExpanded)
    }
    val hasOlderMessages by viewModel.hasOlderMessages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    // [T-stream-stall-watchdog] First-chunk wait timestamp for TypingIndicator.
    val streamAwaitingSince by viewModel.streamAwaitingSince.collectAsState()
    val isCompactingNow by viewModel.isCompacting.collectAsState()
    val canResume by viewModel.canResume.collectAsState()
    val error by viewModel.error.collectAsState()
    val modelName by viewModel.modelName.collectAsState()
    val sessionTitle by viewModel.sessionTitle.collectAsState()
    val sessionCategory by viewModel.sessionCategory.collectAsState()
    val immersiveProfile by viewModel.immersiveProfile.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val availableGroups by viewModel.availableGroups.collectAsState()
    val selectedGroupName by viewModel.selectedGroupName.collectAsState()
    val providerName by viewModel.providerName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val panelExpansionState = remember(viewModel) { PanelExpansionState() }

    // [P3.3 裁军] 原 RECORD_AUDIO 三段式授权流（ensureMicPermissionFlow）随
    // 语音输入整体退役删除。

    // Hoisted to ChatViewModel so it survives ChatScreen disposal/recomposition
    // across forward navigation (file preview, env vars, etc.); see
    // ChatViewModel.listState for the why.
    val listState = viewModel.listState
    // T325: draft persists on the VM so navigation (e.g. push EnvVars and
    // pop back) doesn't wipe what the user has typed. Mirrors iOS
    // `AIChatView` which binds the composer against `vm.inputText`.
    val inputText by viewModel.inputText.collectAsState()
    LaunchedEffect(sessionId) {
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingChatInput(sessionId)?.let {
            viewModel.setInputText(it)
        }
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator.pendingChatAction.value
        if (pending == com.openminis.app.deeplink.DeepLinkCoordinator.ChatAction.OPEN_CREATION_TOOL ||
            pending == com.openminis.app.deeplink.DeepLinkCoordinator.ChatAction.ORGANIZE_IMPORTED_CARD) {
            com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingChatAction()
            if (viewModel.inputText.value.isBlank()) {
                viewModel.setInputText(if (pending == com.openminis.app.deeplink.DeepLinkCoordinator.ChatAction.ORGANIZE_IMPORTED_CARD)
                    "请读取本次选中的卡片，把原始设定整理成方便编辑的模块。保留原有设定，修改同一张卡；整理后的内容不要与原文重复采用。"
                else "请根据当前对话已有资料帮我创作；已有明确目标时直接完成，缺少目标时再和我讨论。")
            }
        }
    }
    val novexControls by viewModel.novexControls.collectAsState()
    val novexControlView by viewModel.novexControlView.collectAsState()

    // ─── T51: Share Injection + Move-to capsule ───────────────────────
    // Drain any pending share buffered by ShareCoordinator (cold start =
    // bufferVersion already non-zero on first composition; warm start =
    // version increments while the user is mid-session). Runs on every
    // bufferVersion bump.
    val shareBufferVersion by com.openminis.app.share.ShareCoordinator.bufferVersion.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        val pending = com.openminis.app.share.ShareCoordinator.consumeBuffer(context)
            ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[Share] injecting ${pending.items.size} item(s) into chat session=$sessionId",
        )
        val sharedDir = com.openminis.app.share.SharedShareStore.sharedFileDirectory(context)
        // [T-android-share-buffer-merge] Accumulate locally rather than
        // reading `inputText` inside the loop. `inputText` is captured from
        // composition and does NOT observe the setInputText calls made here,
        // so every text item was appended to the same stale base and only the
        // last one survived — a two-text share landed as just the second one
        // even after the store-level merge delivered both.
        var draft = inputText
        for (item in pending.items) {
            when (item.kind) {
                com.openminis.app.share.PendingShare.Item.Kind.INLINE_TEXT -> {
                    val sep = if (draft.isNotEmpty()) "\n" else ""
                    val needsTrailingSpace = item.value.startsWith("http://") ||
                        item.value.startsWith("https://")
                    draft = draft + sep + item.value +
                        if (needsTrailingSpace) " " else ""
                    viewModel.setInputText(draft)
                }
                com.openminis.app.share.PendingShare.Item.Kind.ATTACHMENT -> {
                    viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value))
                }
            }
        }
        viewModel.markShareInjected()
        com.openminis.app.share.SharedShareStore.cleanSharedFiles(context)
    }

    // T311: publish "this is the active chat" while ChatScreen is composed,
    // so `minis-config session.*` reads/writes target it. Mirrors iOS
    // `AIChatViewModel.activeSessionId` which is updated on appear / disappear.
    // [T-HANG-DIAG] capture the application context so we can read the
    // current hang count from non-composable scopes below. LocalContext is
    // already used elsewhere in this file via `context`, but DisposableEffect
    // is a non-composable scope so we lift the read up here.
    val tHangDiagAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val returnFromConversation = {
        com.openminis.app.crash.ProcessExitEvidence.record(context, "chat.return-request streaming=$isStreaming")
        if (onBackReturnsToList) viewModel.returnToConversationList(onBack) else onBack()
    }
    NovexGameEntryDialog(gameEntryState, viewModel::selectGameEntryPlayer, viewModel::retryGameEntry, returnFromConversation)
    androidx.activity.compose.BackHandler(onBack = returnFromConversation)
    androidx.compose.runtime.DisposableEffect(sessionId) {
        com.openminis.app.crash.ProcessExitEvidence.record(context, "chat.mount")
        viewModel.markConversationVisible()
        ChatViewModelStore.setActiveSession(sessionId)
        // [T-HANG-DIAG] enter / dispose markers around the ChatScreen lifetime
        // so we can correlate "user tapped session X" → loadSession timings
        // and any subsequent hang record. Removable by grepping out
        // `[T-HANG-DIAG]` from this file.
        println(
            "[T-HANG-DIAG] ChatScreen MOUNT session=$sessionId hangCount=" +
                com.openminis.app.diagnostics.HangDetector.currentHangCount(tHangDiagAppContext),
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "chatScreen.mount")
        onDispose {
            println("[T-HANG-DIAG] ChatScreen UNMOUNT session=$sessionId")
            com.openminis.app.crash.ProcessExitEvidence.record(context, "chat.unmount")
            ChatViewModelStore.setActiveSession(null)

        }
    }

    // Hang-detector quiet-period reset: if the user lands on a chat session
    // and stays for 10s without the watchdog firing again, the previous
    // hang count was a transient blip and the breaker can release. The call
    // itself is cheap — early-returns when the count is already zero.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        kotlinx.coroutines.delay(10_000)
        com.openminis.app.diagnostics.HangDetector.markHealthyTick()
    }

    // Drain any pending Move-to transfer when entering this session — the
    // source ChatScreen stashed (inputText + attachments) into the global
    // ChatViewModelStore.pendingTransfer slot before navigating here.
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        // [T-android-moveto-stash-binding] Pass this screen's session so the
        // store only hands over a stash addressed to it (and drops stale ones).
        val transfer = ChatViewModelStore.consumePendingTransfer(sessionId) ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[MoveTo] draining transfer into session=$sessionId text=${transfer.inputText.length}ch attachments=${transfer.attachments.size}",
        )
        // Clear any stale unsent attachments on the target session before
        // injecting (mirrors iOS injectPendingTransferIfNeeded).
        viewModel.clearAttachments()
        if (transfer.inputText.isNotEmpty()) {
            val sep = if (inputText.isNotEmpty()) "\n" else ""
            viewModel.setInputText(inputText + sep + transfer.inputText)
        }
        for (a in transfer.attachments) viewModel.addAttachment(a)
        viewModel.markShareInjected()
    }

    // Mirrors `inputText` for the BasicTextField but tracks selection so we
    // can position the cursor (e.g. AFTER the leading "/" when the slash
    // button inserts it) — a plain String overload would reset cursor to 0
    // on every external write.
    var inputFieldValue by composerEnv.inputFieldValue
    val composerInputSynchronizer = remember { ComposerInputSynchronizer() }
    // T217-2: suppress IME commits arriving briefly after send. clearFocus
    // triggers finishComposingText, which makes voice/Pinyin IMEs commit
    // their pending candidate back through onValueChange even after we
    // cleared inputText. Drop those late commits during a short window.
    var lastSendTimeMs by composerEnv.lastSendTimeMs
    // [P3.3 裁军] voiceUsedSinceClear + noteSendForInputModePref（语音输入
    // 模式记账，ComposerInputModePrefs）随语音输入退役删除。
    androidx.compose.runtime.LaunchedEffect(inputText) {
        if (!composerInputSynchronizer.shouldApplyExternal(inputText)) {
            return@LaunchedEffect
        }
        if (inputFieldValue.text != inputText) {
            // [T-android-slash-menu-align-ios-prepend] Honor a one-shot caret
            // override from the slash flow (prepend "/ " → caret 1; insert
            // "/<skill> " → caret after the prefix). Read-and-clear so it
            // applies exactly once; otherwise default the caret to the end
            // (existing behavior). Coerce into bounds defensively.
            val caret = viewModel.consumePendingCaret()?.coerceIn(0, inputText.length)
                ?: inputText.length
            inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                text = inputText,
                selection = androidx.compose.ui.text.TextRange(caret),
                // T217: explicitly drop any pending IME composing buffer so voice
                // recognition / Pinyin candidates don't get re-committed back into
                // the field after send (mirrors iOS unmarkText in AIChatView.swift
                // updateUIView L5638).
                composition = null,
            )
        }
    }
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Mirror of iOS `inputFocused` — needed so the swipe-up-on-empty-input
    // gesture only pops the keyboard when it's actually collapsed.
    var inputFocused by composerEnv.inputFocused


    val coroutineScope = rememberCoroutineScope()

    var showModelPicker by remember { mutableStateOf(false) }
    // [T-android-thinking-badge-navbar] Whether the thinking-level sheet
    // (opened by tapping the navbar thinking badge) is presented. Mirrors iOS
    // AIChatView.showThinkingLevelSheet.
    var showThinkingLevelSheet by remember { mutableStateOf(false) }
    var showAttachMenu by composerEnv.showAttachMenu
    var showChatMenu by remember { mutableStateOf(false) }
    var showHistoryNavigation by remember(sessionId) { mutableStateOf(false) }
    var historyJumpId by remember(sessionId) { mutableStateOf<String?>(null) }
    if (showHistoryNavigation) {
        novex.android.ui.NovexSearchableSelectionSheet(
            title = "对话历史", searchPlaceholder = "搜索原文",
            actions = viewModel.historyNavigationMessages().mapIndexed { index, message ->
                // 行升级（design-system §13）：序号 + 角色 + 内容预览；ChatMessage
                // 不带时间戳，时间以序号近似定位。
                val roleLabel = if (message.role == "user") "我" else "AI"
                novex.android.ui.NovexSelectionAction(
                    label = "#${index + 1} $roleLabel",
                    description = message.content.take(200).ifBlank {
                        if (message.role == "user") "（无文字内容）" else "回复与执行记录"
                    },
                    onClick = {
                        viewModel.revealHistoryMessage(message.id)
                        compactedHistoryExpanded = true
                        historyJumpId = message.id
                    })
            }, onDismissRequest = { showHistoryNavigation = false })
    }
    // [T-mcp-integration-android] MCPs-in-Session sheet visibility.
    // [P3.3 裁军] showMcpsSheet（会话内 MCP 开关 Sheet）随 MCP 集成面退役。
    var requestSideConversation by remember { mutableStateOf(false) }
    var requestSidePanel by remember { mutableStateOf(false) }
    // ── 侧边对话页状态（决策 12/16）──────────────────────────────────────
    // 非空 = 当前会话是侧边会话：顶栏只放删除、输入区放回传符号、不渲染
    // 书签列与状态把手（防嵌套；状态只属于主线）。
    var sideParentId by remember(sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionId) {
        sideParentId = runCatching { chatRepository.sessionById(sessionId)?.sideOfSession }.getOrNull()
    }
    // ── 沉浸淡化（2026-09-15 用户确认：不做收起做淡化）──────────────────
    // 范围：顶栏 + 主线页侧边组件（书签列/状态把手）+ 右下浮动按钮——全部原地
    // 透明度归零，布局零跳动（此前滑出+让位会跳版式，被用户当成 bug）。
    // 触发：点聊天空白处切换；空闲 6 秒自动淡出（生成中不豁免）。侧边对话页
    // 不淡化。任何触摸都会重置空闲计时，且**唤回**淡出的界面；为避免"隐藏
    // 手势自己又触发唤回"，600ms 内刚隐藏/刚唤回的手势互不抢状态。
    var chromeCollapsed by remember { mutableStateOf(false) }
    var lastInteractionAt by remember { mutableStateOf(0L) }
    var chromeHiddenAtMs by remember { mutableLongStateOf(0L) }
    var chromeRevivedAtMs by remember { mutableLongStateOf(0L) }
    val effectiveChromeCollapsed = chromeCollapsed && sideParentId == null
    val chromeFadeAlpha by animateFloatAsState(
        targetValue = if (effectiveChromeCollapsed) 0f else 1f,
        animationSpec = tween(220),
        label = "chromeFadeAlpha",
    )
    // [T-chrome-autofade-setting] 空闲 6 秒自动淡出受「外观」开关控制，默认
    // 关闭（2026-09-16 用户决策）——关闭时仅保留点空白处的手动淡出/唤回。
    val chromeAutoFadeEnabled = remember { com.openminis.app.ui.settings.chromeAutoFadeEnabled(context) }
    LaunchedEffect(lastInteractionAt, chromeCollapsed, sideParentId) {
        if (!chromeAutoFadeEnabled || sideParentId != null || chromeCollapsed) return@LaunchedEffect
        kotlinx.coroutines.delay(6_000)
        chromeCollapsed = true
        chromeHiddenAtMs = System.currentTimeMillis()
    }
    // ── 底部活动条（2026-09-15 用户决策 ②：技能调用动态贴底滚动播报）──────
    // 数据在转录区内部的扁平化流水线里产出（flatItems），状态提升到这里供
    // 输入栏上方的条带读取；openedProcess 同理提升，条带点击可打开完整记录。
    var openedProcess by remember(sessionId) { mutableStateOf<FlatChatItem.AssistantProcess?>(null) }
    // [T-handoff-queue] 生成结束后消费排队的回传（决策 7）。
    val isStreamingForHandoffQueue by viewModel.isStreaming.collectAsState()
    LaunchedEffect(isStreamingForHandoffQueue, sideParentId) {
        val parent = sideParentId
        if (!isStreamingForHandoffQueue && parent != null) {
            viewModel.runQueuedSideHandoff(parent)
        }
    }
    // 回传守卫（决策 18）与状态机已下沉 ChatViewModel（2026-09-15 ①）：
    // 页面销毁不再丢流程；这里只保留返回主线时的一次性重载（消费 dirty 标记）。
    var showSideDeleteDialog by remember { mutableStateOf(false) }
    val sideHandoffState by viewModel.sideHandoffState.collectAsState()
    val chatLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(chatLifecycleOwner, sessionId, sideParentId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && sideParentId == null &&
                NovexSideHandoff.consumeParentDirty(context, sessionId)
            ) {
                coroutineScope.launch { viewModel.reloadActiveConversation() }
            }
        }
        chatLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { chatLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val startHandoff: () -> Unit = {
        val parent = sideParentId
        if (parent != null && !isStreaming) {
            viewModel.startSideHandoff(parent)
        }
    }
    if (showSideDeleteDialog && sideParentId != null) {
        novex.android.ui.NovexContentDialog(
            "删除这条侧边对话？",
            onDismiss = { showSideDeleteDialog = false },
            confirmButton = {
                novex.android.ui.TextButton(onClick = {
                    showSideDeleteDialog = false
                    coroutineScope.launch {
                        runCatching { chatRepository.dropSession(sessionId) }
                        onBack()
                    }
                }) { Text("删除", color = novex.android.ui.NovexColors.Danger) }
            },
        ) {
            Text(
                "聊天记录将删除，且不可恢复。已并入主对话的交接简报不受影响。",
                style = novex.android.ui.NovexType.Body,
                color = novex.android.ui.NovexColors.Text,
            )
        }
    }
    // T185: Move-to-session sheet visibility. Hoisted to the top of
    // ChatScreen so the trigger (capsule inside the composer) and the
    // sheet body (rendered later in the layout tree) share the same
    // backing state without needing fragile scope wiring.
    var showMoveSheet by composerEnv.showMoveSheet
    var pendingShareText by remember { mutableStateOf<String?>(null) }
    var showComposerExpanded by composerEnv.showComposerExpanded
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showConversationRecords by remember { mutableStateOf(false) }
    var pendingDeleteFromMessageId by remember { mutableStateOf<String?>(null) }
    // [T-new-chat-menu-entry] Confirmation gate for "New Chat" while the
    // current session is still streaming — stopping the running task needs
    // an explicit confirm; idle sessions skip the dialog entirely.
    var showNewChatStopDialog by remember { mutableStateOf(false) }
    // [T-android-enhanced-cache] First-enable confirmation dialog visibility.
    var showEnhancedCacheDialog by remember { mutableStateOf(false) }

    // Bridge VM's slash-command "/clear" request into local Compose state so
    // the menu and slash-command entry points share a single confirmation
    // dialog instance. ack the VM flag immediately to avoid re-firing on
    // recomposition.
    val clearChatRequested by viewModel.clearChatConfirmRequested.collectAsState()
    LaunchedEffect(clearChatRequested) {
        if (clearChatRequested) {
            showClearChatDialog = true
            viewModel.ackClearChatConfirmRequest()
        }
    }

    // "Choose Photos & Videos" — uses the Photo Picker on Android 13+ via the
    // PickMultipleVisualMedia contract; AndroidX falls back to
    // ACTION_OPEN_DOCUMENT on older versions. Mirrors iOS PHPicker
    // (.imagesAndVideos, selectionLimit=50). T129: switched from single to
    // multi-select with a 50-item cap — picks above 50 are truncated and we
    // toast the user so they aren't silently dropped.
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = ATTACHMENT_PICK_LIMIT,
        ),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val isVideo = mimeType.startsWith("video/")
            val defaultName = if (isVideo) "video.mp4" else "image.jpg"
            val fileName = getFileName(context, uri) ?: defaultName
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = mimeType,
                    // Videos are routed as DOCUMENT for now — vision pipeline only
                    // handles images today; videos still upload as raw files so
                    // tools that read them (e.g. ffmpeg) get the bytes.
                    kind = if (isVideo) InputAttachment.Kind.DOCUMENT else InputAttachment.Kind.IMAGE,
                ),
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT items were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val immersiveBackgroundPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                com.openminis.app.data.character.CharacterCardStore.copyMedia(
                    context,
                    uri,
                    "chat-background",
                )
            }.onSuccess { viewModel.setImmersiveBackground(it) }
                .onFailure {
                    android.widget.Toast.makeText(context, it.message ?: "背景读取失败", android.widget.Toast.LENGTH_SHORT).show()
                }
        }
    }

    // "Take Photo" — Bug 1 in the MIUI feedback report had this silently
    // drop photos because the default TakePicture contract trusts
    // resultCode, and MIUI's camera occasionally returns CANCELED even
    // after writing the file (or OK with the file flushed late). We use
    // StartActivityForResult directly and trust the filesystem instead:
    // if the staging file has nonzero length, we got a photo.
    // [T-android-camera-rotate-lost-photo] MainActivity has no
    // configChanges="orientation", so capturing in one orientation and
    // returning in another RECREATES the Activity. These pending handles must
    // therefore survive the recreate — `remember` is reset on recomposition
    // after recreation, so the ActivityResult callback would see a null uri
    // and silently drop the just-taken photo (gallery picks are unaffected:
    // their result Uri arrives directly in-callback). `rememberSaveable`
    // persists through savedInstanceState: Uri is Parcelable; the staging
    // File is saved as its absolute path string and rebuilt on read.
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // [T-android-overlay-hide-camera] Release the overlay-suppress
        // gate as soon as we hear back from the camera Activity (success,
        // cancel, or system kill). Without this the floating overlay
        // would stay suppressed indefinitely after a single capture.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
        val uri = pendingCameraUri
        val file = pendingCameraFilePath?.let { java.io.File(it) }
        pendingCameraUri = null
        pendingCameraFilePath = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        // Don't trust resultCode on MIUI — check the file.
        val ok = file.exists() && file.length() > 0
        if (ok) {
            val fileName = getFileName(context, uri) ?: file.name
            viewModel.addAttachment(
                InputAttachment(
                    fileName = fileName,
                    uri = uri,
                    mimeType = "image/jpeg",
                    kind = InputAttachment.Kind.IMAGE,
                ),
            )
        } else {
            AppLogger.warning(
                "Camera",
                "capture failed: rc=${result.resultCode}, file=${file.name} len=${file.length()}",
            )
            file.delete()
        }
    }
    val launchCamera: () -> Unit = {
        val (uri, file) = createCameraOutputUri(context)
        pendingCameraUri = uri
        pendingCameraFilePath = file.absolutePath
        val intent = android.content.Intent(
            android.provider.MediaStore.ACTION_IMAGE_CAPTURE,
        ).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // [T-android-overlay-hide-camera] Suppress the floating bg-overlay
        // BEFORE handing off to the system camera. The camera Activity
        // takes foreground, which by #451's rule would otherwise satisfy
        // "Minis backgrounded → show overlay" and the capsule would draw
        // on top of the viewfinder. Cleared in the ActivityResult callback.
        com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(true)
        runCatching { cameraLauncher.launch(intent) }
            .onFailure {
                AppLogger.warning("Camera", "launch failed: ${it.message}")
                // Launch never reached the camera Activity — release the
                // suppress flag here since the result callback won't fire.
                com.openminis.app.service.SessionActivityTracker.setCameraSuppressActive(false)
                pendingCameraUri = null
                pendingCameraFilePath = null
                file.delete()
            }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    // App-icon quick action: when the user launched via
    // `minis://action/camera_chat`, auto-open the camera on first compose.
    // Consumed exactly once so re-entering the chat later does NOT re-trigger.
    // [P3.3 裁军] 原 voice_chat 快捷动作变体随语音输入退役（详见输入区墓碑）。
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingChatAction.value
        if (pending == com.openminis.app.deeplink.DeepLinkCoordinator
                .ChatAction.OPEN_CAMERA
        ) {
            com.openminis.app.deeplink.DeepLinkCoordinator
                .consumePendingChatAction()
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    // File picker launcher — T129: multi-select via OpenMultipleDocuments
    // (GetContent has no multi-select equivalent). The launch arg is now a
    // mime-type array; "*/*" stays as the wildcard. Selections above
    // ATTACHMENT_PICK_LIMIT are truncated with a toast so silent drops can't
    // happen. OpenMultipleDocuments returns persistable URIs by default
    // (good — survives process death better than the GetContent stream).
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val limited = uris.take(ATTACHMENT_PICK_LIMIT)
        for (uri in limited) {
            val fileName = getFileName(context, uri) ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val kind = if (mimeType.startsWith("image/")) InputAttachment.Kind.IMAGE else InputAttachment.Kind.DOCUMENT
            viewModel.addAttachment(
                InputAttachment(fileName = fileName, uri = uri, mimeType = mimeType, kind = kind)
            )
        }
        if (uris.size > ATTACHMENT_PICK_LIMIT) {
            android.widget.Toast.makeText(
                context,
                "Only the first $ATTACHMENT_PICK_LIMIT files were attached.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // [P3.3 裁军] Android 系统权限桥（OffloadPermissionManager：系统弹窗 +
    // 设置页门 + OffloadPermissionDialog）随 offload/ 整包退役删除；沙箱
    // 退役后已无发起运行时权限请求的工具调用方。

    val tagScroll = "ChatScrollFollow"
    // Scroll wrappers used by every code path that mutates the LazyColumn
    // position. Kept as named lambdas so re-enabling per-call telemetry
    // (during a scroll-positioning regression) is a one-line edit here
    // instead of changing 20+ call sites. Currently silent.
    val tracedScrollToItem: suspend (source: String, idx: Int, off: Int) -> Unit = { _, idx, off ->
        runCatching { listState.scrollToItem(idx, off) }
        Unit
    }
    var transcriptFollowState by remember(sessionId) { mutableStateOf(TranscriptFollowState()) }
    var lastUserDragAtMs by remember(sessionId) { mutableStateOf(Long.MIN_VALUE) }
    var submittedTurnNavigation by remember(sessionId) { mutableStateOf(SubmittedTurnNavigation()) }
    val scrollToLatestOnce: suspend (TranscriptViewportMove) -> Unit = scroll@{ reason ->
        if (!transcriptFollowState.shouldMoveFor(reason)) return@scroll
        // [feat/ui-rikkahub] Passive growth is the per-frame hot path. Pin with
        // requestScrollToItem — non-suspending, applied in the same frame —
        // because the old withFrameNanos + scrollToItem ran one frame behind
        // every streamed chunk and flickered the bottom edge. The in-progress
        // guard (not the interaction timestamp) is what can actually observe a
        // drag starting between this loop's emission and its collection.
        if (reason == TranscriptViewportMove.PassiveStreamGrowth) {
            if (listState.isScrollInProgress) return@scroll
            latestTranscriptItemIndex(listState.layoutInfo.totalItemsCount)?.let { latest ->
                // Same clamp semantics as the slow path below: a large forward
                // offset lands on the final line, not merely the final row.
                listState.requestScrollToItem(latest, Int.MAX_VALUE / 4)
            }
            return@scroll
        }
        // One frame lets a newly-added or newly-measured row enter the list.
        // Explicit actions always pass; passive growth only passes while the
        // temporary “follow latest” state is active.
        val dragBeforeFrame = lastUserDragAtMs
        withFrameNanos { }
        if (lastUserDragAtMs != dragBeforeFrame || !transcriptFollowState.shouldMoveFor(reason)) return@scroll
        latestTranscriptItemIndex(listState.layoutInfo.totalItemsCount)?.let { latest ->
            // A zero offset would place the beginning of a viewport-tall final
            // message at the top. A large forward offset is safely clamped by
            // LazyColumn to the real content end, so "return to latest" means
            // the final line rather than merely the final row.
            tracedScrollToItem(reason.name, latest, Int.MAX_VALUE / 4)
        }
    }
    LaunchedEffect(viewModel, sessionId) {
        viewModel.submittedUserMessageId.collect { submission ->
            if (!submission.canNavigateAfter(lastUserDragAtMs)) return@collect
            transcriptFollowState = transcriptFollowState.after(TranscriptFollowEvent.UserRequestedLatest)
            submittedTurnNavigation = submittedTurnNavigation.awaiting(submission.messageId)
        }
    }
    // T-android-jank-profile: gate verbose scroll telemetry behind a constant
    // so every snapshotFlow / derivedStateOf body in this file can cheaply
    // skip the AppLogger.debug call (which builds a long format string and
    // writes a daily log file). Flip locally when debugging scroll behavior.
    val verboseScrollLogs = false

    // Normal chronological layout has one authoritative end signal:
    // `canScrollForward == false`. There is deliberately no proximity zone.
    // Being one pixel from the end may show the return button, but it can never
    // grant the application permission to move the viewport.
    val isNearBottom = remember(listState) {
        derivedStateOf { isAtTranscriptLatest(listState.canScrollForward) }
    }
    // T170: derived "does content actually overflow the viewport?". Mirrors
    // iOS where `maxOffset > 0` naturally hides the FAB on short sessions.
    // Without this, an IME-driven synthetic drag-stop on a short chat could
    // pin the FAB on screen until the keyboard closed.
    val contentOverflows = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportSize = info.viewportEndOffset - info.viewportStartOffset
            val canScroll = listState.canScrollForward || listState.canScrollBackward
            val moreItemsThanVisible = info.totalItemsCount > info.visibleItemsInfo.size
            val visibleSum = info.visibleItemsInfo.sumOf { it.size }
            val sumExceedsViewport = visibleSum > viewportSize
            val result = canScroll || moreItemsThanVisible || sumExceedsViewport
            // T-android-jank-profile: gate per-frame derivedStateOf logs.
            if (false) {
                AppLogger.debug(
                    tagScroll,
                    "contentOverflows: canScroll=$canScroll moreItems=$moreItemsThanVisible sumExceeds=$sumExceedsViewport visibleSum=$visibleSum viewport=$viewportSize total=${info.totalItemsCount} visible=${info.visibleItemsInfo.size} → $result",
                )
            }
            result
        }
    }

    // [feat/ui-rikkahub] mainstream-style re-anchor: a scroll that SETTLES at the
    // live tail re-grants follow, so glancing up mid-stream and flicking back
    // down re-sticks the tail without the return-to-latest button. Upward
    // drags still revoke at DragStart. Programmatic moves also settle at the
    // tail, but they only run while follow is active or for explicit moves —
    // re-granting there matches intent. The 8dp tolerance mirrors mainstream clients'
    // isAtBottom(): a fling that decelerates just short of the end still
    // counts as "at the bottom".
    val settleGrantTolerancePx = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(listState, sessionId) {
        var wasScrolling = listState.isScrollInProgress
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (wasScrolling && !scrolling) {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()
                val settledAtLatest = !listState.canScrollForward ||
                    (lastVisible != null &&
                        lastVisible.index == latestTranscriptItemIndex(info.totalItemsCount) &&
                        lastVisible.offset + lastVisible.size - info.viewportEndOffset <= settleGrantTolerancePx)
                if (settledAtLatest) {
                    transcriptFollowState = transcriptFollowState.after(
                        TranscriptFollowEvent.UserRestingAtLatest,
                    )
                }
            }
            wasScrolling = scrolling
        }
    }

    // [T-android-scrollbtn-turn-walk] Up-button turn-walk state, mirroring iOS
    // `lastJumpedUserId` (dcdec3c5). Holds the id of the user message the
    // up-button last jumped to, so a REPEATED tap walks one turn further back
    // instead of re-landing on the same turn. Reset to null whenever the
    // anchoring context changes — manual drag, jump-to-bottom, message-list
    // change, or session switch — so the next tap re-anchors to whatever the
    // user is currently looking at rather than continuing a stale sequence.
    var lastJumpedUserId by remember(sessionId) { mutableStateOf<String?>(null) }

    // [T-android-scrollbtn-turn-walk] Content changed (new/removed messages) —
    // the turn-walk anchor may no longer line up, so restart it on the next tap.
    // iOS does this in its snapshot-apply path; on Android the equivalent
    // trigger is the message list itself changing identity/size.
    LaunchedEffect(messages.size) { lastJumpedUserId = null }

    // [T-android-scrollbtn-turn-walk] Up-button action: walk backwards through
    // the conversation one USER turn at a time (iOS `scrollToPreviousUserTurn`).
    // Replaces the old "jump to the very first message":
    //   - First tap: scroll to the user message of the turn the viewport is
    //     currently in (nearest user message at or above the visual top).
    //   - Repeated taps (no intervening drag / new message): each goes one
    //     turn further back.
    //   - Already at the first turn: stay put, no overscroll.
    // A browse action never grants passive code permission to move the
    // viewport; only another explicit navigation action may replace it.
    //
    // Index resolution goes through the LazyColumn's stable item KEYS
    // ("user:<id>", set in FlatChatItem.UserBubble) rather than arithmetic on
    // message indices. Two things make raw arithmetic wrong here: one message
    // flattens into MANY rows (header / markdown blocks / tool blocks), and a
    // conditional history/resume rows can shift every subsequent index. Keys
    // are immune to both.
    val scrollToPreviousUserTurn: suspend () -> Unit = scrollToPreviousUserTurn@{
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@scrollToPreviousUserTurn
        // Ordered oldest → newest list of user-message ids, matching the order
        // the user reads the conversation in.
        val userIds = transcriptMessages.filter { it.role == "user" }.map { it.id }
        if (userIds.isEmpty()) {
            // No user turns (rare) — index 0 is the chronological oldest row.
            tracedScrollToItem("FAB-UP/no-user-turns", 0, 0)
            return@scrollToPreviousUserTurn
        }
        // Physical offset, rather than item index, is the authoritative visual
        // top signal. This also remains correct during transient item remeasure.
        val topKey = visible.minByOrNull { it.offset }?.key as? String
        // Map the top row back to its position in `messages`. Row keys are
        // "<kind>:<messageId>[:extra]", and some kinds append their own suffix
        // after the id (e.g. "mdblock:<id>:text_<id>_0:1"), so the id is the
        // segment between the FIRST and SECOND colon — not everything after the
        // first one.
        val topMessageId = topKey
            ?.split(':')
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
        // `messages` is a TAIL WINDOW (ChatViewModel.uiMessages + loadOlderMessages),
        // so the visible top row can belong to a message that is not loaded yet.
        // The top of the viewport is the OLDEST content on screen, so when it
        // resolves to nothing the user is at/above the start of the window —
        // anchor on the oldest loaded message (index 0) and let the walk proceed
        // from there.
        val topMsgIdx = topMessageId
            ?.let { id -> transcriptMessages.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: 0
        // The current turn's anchor = nearest user message AT OR ABOVE the top
        // row (searching backwards through the conversation).
        val currentAnchor = transcriptMessages.take(topMsgIdx + 1).lastOrNull { it.role == "user" }?.id
            ?: userIds.first()
        // Decide the target — the rule from iOS `scrollToPreviousUserTurn`: if
        // the viewport is already at the anchor we last jumped to (the user has
        // seen this turn's start), step to the previous turn; otherwise land on
        // the current turn's anchor first.
        //
        // Android cannot re-derive the walk position from the viewport the way
        // iOS does, for two independent reasons — so once a walk has started we
        // always continue from `lastJumpedUserId`:
        //
        //  1. A LazyColumn CLAMPS at the end of its content. Near the oldest rows
        //     the target can't reach the top, `currentAnchor` recomputes to the
        //     same turn every tap, and the walk oscillates (device: taps 6/7
        //     flipping bfa090b0 <-> 345b4a6c).
        //  2. Top-aligning the landing (below) deliberately anchors the viewport
        //     on a NEWER row than the target, so the recomputed `currentAnchor`
        //     reads a turn NEWER than the one we just jumped to — the walk then
        //     bounced 9 -> 22 -> 9 -> 22 forever.
        //
        // `lastJumpedUserId` is cleared on drag / jump-to-bottom / new messages /
        // session switch, so this only ever chains genuine repeated taps; the
        // first tap after any of those still anchors off the viewport.
        val walkFrom = lastJumpedUserId?.takeIf { it in userIds } ?: currentAnchor
        val pos = userIds.indexOf(walkFrom)
        val steppedTarget = if (lastJumpedUserId == walkFrom && pos > 0) {
            userIds[pos - 1]
        } else {
            walkFrom
        }
        // [T-android-fab-up-skip-visible] Never "scroll" to a turn the user is
        // already looking at.
        //
        // `currentAnchor` is the nearest user message AT OR ABOVE the viewport
        // top, so when a user bubble is already on screen it IS the anchor —
        // and on the first tap (lastJumpedUserId == null) the target is the
        // anchor itself. The button then scrolls to a bubble that is already
        // visible, which reads as a dead tap: the transcript barely moves and
        // the user has to tap twice to go back one turn.
        //
        // Fix: treat every user turn currently rendered in the viewport as
        // "already seen" and walk further back until we find one that is not.
        // Only fully-visible bubbles count — a turn scrolled half off the top
        // edge is one the user has NOT finished reading, and jumping to it to
        // align its top edge is a genuine, useful move.
        //
        // Visibility is read from the pre-seek layout on purpose: the seek
        // below mutates the viewport, so anything derived afterwards would
        // describe where the search happened to stop, not where the user was.
        val viewportTop = info.viewportStartOffset
        val viewportBottom = info.viewportEndOffset
        val fullyVisibleUserIds: Set<String> = visible
            .asSequence()
            .filter { item ->
                val k = item.key as? String ?: return@filter false
                k.startsWith("user:") &&
                    item.offset >= viewportTop &&
                    item.offset + item.size <= viewportBottom
            }
            .mapNotNull { (it.key as? String)?.removePrefix("user:")?.takeIf { id -> id.isNotEmpty() } }
            .toSet()

        val target = if (steppedTarget in fullyVisibleUserIds) {
            // Walk back (toward older turns = LOWER index in `userIds`, which is
            // ordered oldest → newest) past every turn already on screen. If all
            // of them are visible we stop at the oldest — `scrollToItem` clamps,
            // so this stays a harmless no-op at the start of the conversation
            // rather than an overscroll.
            var i = userIds.indexOf(steppedTarget)
            while (i > 0 && userIds[i] in fullyVisibleUserIds) i--
            userIds[i]
        } else {
            steppedTarget
        }
        // Resolve the target user bubble's row index by its stable key.
        //
        // The flat-item list is built inside the LazyColumn's own scope and is
        // not reachable from here, so an off-screen target has to be found by
        // walking the viewport toward it. Two things make that delicate, and
        // both were observed failing on device before this shape:
        //
        //  1. The seek MUTATES the viewport. The anchor must therefore be
        //     computed BEFORE any seeking (it is — `currentAnchor` above is
        //     derived from the pre-seek layout), or every tap re-anchors to
        //     wherever the previous tap's seek happened to stop and the walk
        //     never advances.
        //  2. A seek that overshoots to the oldest row leaves the list unable
        //     to scroll further; if the target still isn't found we must
        //     RESTORE the original position rather than strand the user at the
        //     top of the transcript.
        val targetKey = "user:$target"
        fun indexOfTargetKey(): Int? =
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == targetKey }?.index

        val restoreIndex = listState.firstVisibleItemIndex
        val restoreOffset = listState.firstVisibleItemScrollOffset
        var targetIndex = indexOfTargetKey()
        var guard = 0
        // Scan every row until the target's key shows up.
        //
        // The previous seek walked only toward HIGHER indices in viewport-sized
        // strides, and that produced the user's dead tap: opening the 读屏
        // session and dragging slightly left the viewport on rows 0..8 with the
        // target user bubble at row 9 — just BELOW it. The stride seek jumped
        // 17 -> 41, straight past the target, found nothing, and fell into
        // RESTORE (a visible no-op).
        //
        // Direction genuinely cannot be assumed: `currentAnchor` is the nearest
        // user message at or above the viewport TOP, and how far its row sits
        // from the current window depends entirely on how tall the intervening
        // tool / thinking / shell-output blocks are — which in real
        // conversations is wildly variable (one assistant message in this
        // session spans rows 23..46). Sweeping the whole list from the top is
        // direction-free and cannot step over the target; `scrollToItem` takes
        // any index directly, so each step is just a layout pass.
        if (targetIndex == null) {
            val maxIdx = (info.totalItemsCount - 1).coerceAtLeast(0)
            var probe = 0
            while (targetIndex == null && probe <= maxIdx && guard++ < 200) {
                tracedScrollToItem("FAB-UP/seek", probe, 0)
                targetIndex = indexOfTargetKey()
                // Advance past whatever is now on screen rather than one row at
                // a time, but never skip ahead of the rows we have inspected.
                val hi = listState.layoutInfo.visibleItemsInfo.maxByOrNull { it.index }?.index
                probe = (hi ?: probe) + 1
            }
        }
        if (targetIndex == null) {
            // Target never materialised — undo the seek so the button is a
            // no-op rather than a jump to the very top.
            tracedScrollToItem("FAB-UP/restore", restoreIndex, restoreOffset)
            return@scrollToPreviousUserTurn
        }
        lastJumpedUserId = target
        // A normal chronological LazyColumn interprets offset 0 as placing the
        // target at the viewport start. One call is enough; no reverse-layout
        // size compensation or second corrective jump is needed.
        tracedScrollToItem("FAB-UP/turn-walk", targetIndex, 0)
    }

    // T-drag-send-queue: shared send-or-enqueue handler used by BOTH the
    // send-button tap and the swipe-up-to-send drag. Routes through
    // `viewModel.sendMessage(...)` which internally dispatches to
    // `enqueuePrompt()` when `_isStreaming.value` is true, so the message is
    // queued rather than dropped when the agent loop is mid-flight. Slash-
    // command input short-circuits to the command runner (mirrors the tap
    // path). Caller decides whether to invoke this — gating (canActivate,
    // armFraction, swipedUp) stays at the call site.
    val performSendOrEnqueue: (String) -> Unit = handler@{ rawText ->
        if (viewModel.tryExecuteInputAsSlashCommand(rawText)) {
            viewModel.setInputText("")
            keyboardController?.hide()
            focusManager.clearFocus()
            return@handler
        }
        if (!viewModel.hasModelForSend()) return@handler
        lastSendTimeMs = System.currentTimeMillis()
        // [T-android-slash-send-keeps-text] A send always ends the slash session.
        //
        // Tapping the "/" button over existing text puts the composer into
        // "over-content" mode: it prepends "/ " (so "hello" becomes "/ hello")
        // and stashes the original in savedInputBeforeSlash so every exit path
        // can restore it. But SEND was not one of those exit paths — it cleared
        // the text while leaving the stash and the open menu behind, so the
        // just-sent body was restored into the composer and the user saw their
        // message both sent AND still sitting in the input.
        //
        // Clearing the session here, before the text is cleared, makes send a
        // proper terminal exit: nothing is left to restore. The dismiss and
        // command-row paths keep their own restore behaviour untouched.
        viewModel.endSlashSessionForSend()
        viewModel.setInputText("")
        keyboardController?.hide()
        focusManager.clearFocus()
        viewModel.sendMessage(rawText)
    }
    // [T-android-composer-input-blocked-while-streaming] True only while the
    // user's FINGER is actively dragging the message list. Explicit navigation
    // actions go through `listState.scrollToItem`, which sets
    // `listState.isScrollInProgress = true` but emit NO DragInteraction. So a
    // gesture-only signal lets us distinguish "user scrolled the transcript"
    // (should dismiss the keyboard) from "a navigation button was tapped"
    // (must NOT touch focus). Without this the keyboard closed itself and
    // dropped the in-flight keystroke (the reported "can't type while
    // streaming" bug).
    var isUserDragging by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // T-android-jank-profile: drag interactions fire on every drag
            // event during a scroll (Press / Cancel / Stop). String-building
            // logs here added measurable load. Gate behind a constant.
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> {
                    isUserDragging = true
                    lastUserDragAtMs = android.os.SystemClock.uptimeMillis()
                    submittedTurnNavigation = SubmittedTurnNavigation()
                    transcriptFollowState = transcriptFollowState.after(
                        TranscriptFollowEvent.UserDragStarted,
                    )
                    // [T-android-scrollbtn-turn-walk] A manual drag breaks the
                    // up-button's turn-walk chain: the next tap should re-anchor
                    // to wherever the user landed, not continue the old sequence.
                    lastJumpedUserId = null
                }
                is androidx.compose.foundation.interaction.DragInteraction.Stop -> {
                    isUserDragging = false
                }
                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> {
                    isUserDragging = false
                }
                else -> Unit
            }
        }
    }
    // [T-android-tool-autoscroll] Start-of-turn edge from ViewModel: resume() /
    // retryLast() / retryFromMessage() / rerunFromToolBlock() emit Unit on
    // forceScrollToBottom because they don't append a new user-message row, so
    // no new user-message row is appended. The collector performs their one
    // allowed navigation after the explicit retry/resume action.
    LaunchedEffect(listState, viewModel) {
        viewModel.forceScrollToBottom.collect {
            scrollToLatestOnce(TranscriptViewportMove.UserRetriedTurn)
        }
    }
    // Passive stream growth, final markdown layout, image decoding, tool-card
    // measurement, and keyboard inset changes intentionally have no viewport
    // effect. Explicit actions route through scrollToLatestOnce above.

    // Auto-focus input on new sessions so keyboard pops up immediately.
    //
    // T176: theme switch (Activity recreate) re-enters this LE before the
    // composer's `Modifier.focusRequester(inputFocusRequester)` has been
    // attached for the new composition. requestFocus() then throws
    // `FocusRequester is not initialized` and the process crashes. Guard
    // with try/catch — we lose nothing if the focus call is a no-op on
    // the recreated activity (the user wasn't typing anyway), and the
    // common new-session path still works because the 300 ms delay lets
    // the Modifier attach.
    LaunchedEffect(gameEntryState) {
        if (sessionId.startsWith("__new__") && gameEntryState == NovexGameEntryState.Ready) {
            // Small delay to let the layout settle before requesting focus
            kotlinx.coroutines.delay(300)
            try {
                inputFocusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                AppLogger.debug(
                    tagScroll,
                    "auto-focus skipped: FocusRequester not attached (likely activity recreate / theme switch): ${e.message}",
                )
            }
        }
    }

    // Show top-level error in snackbar (only for errors without an assistant message)
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // T-imgsize: surface composer-side image-budget actions (compress / drop)
    // via Snackbar. Each event is one user send; we emit at most two short
    // notices (compressed count + dropped count) so the user understands
    // why we touched their attachments before the provider would 413.
    LaunchedEffect(Unit) {
        viewModel.imageBudgetEvent.collect { ev ->
            if (ev.compressedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_compressed, ev.compressedCount),
                )
            }
            if (ev.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.image_budget_total_exceeded),
                )
            }
        }
    }

    // T-request-imgsize: surface request-level image-budget elisions
    // (older images compacted into text placeholders to fit the 25MB
    // request cap). Independent flow from the composer-side budget so
    // both can fire on the same turn without racing.
    LaunchedEffect(Unit) {
        viewModel.requestBudgetEvent.collect { plan ->
            if (plan.droppedCount > 0) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.image_budget_request_elided,
                        plan.droppedCount,
                    ),
                )
            }
        }
    }

    var messageFontLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_MESSAGE, 0)) }
    var chatInputLevel by remember { mutableStateOf(appearancePrefs.getInt(com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT, 0)) }
    var toolPreviewEnabled by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_TOOL_PREVIEW, true)) }
    var showContextMeter by composerEnv.showContextMeter
    var contextMeterMode by rememberSaveable { mutableIntStateOf(0) }
    val lastTurnContextTokens by viewModel.lastTurnContextTokens.collectAsState()
    val contextCapacity by viewModel.contextCapacity.collectAsState()
    val contextEstimated by viewModel.contextEstimated.collectAsState()
    val contextUsageReady by viewModel.contextUsageReady.collectAsState()
    // T-chat-title-pill: live-toggled by Settings → Appearance and by
    // `minis-config set appearance.show_chat_title …`. Default ON.
    var showChatTitlePill by remember { mutableStateOf(appearancePrefs.getBoolean(com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE, true)) }
    // T-chat-title-pill-edit: state for the in-chat edit-title sheet (the
    // exact same SessionEditSheet hosted by the session list home screen,
    // reused via `internal` visibility — no duplicate UI). Populated by an
    // async repo lookup once the user taps the title pill.
    var editingSession by remember { mutableStateOf<novex.android.data.chat.SessionRow?>(null) }
    DisposableEffect(appearancePrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                com.openminis.app.ui.settings.KEY_FONT_MESSAGE -> messageFontLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT -> chatInputLevel = sp.getInt(key, 0)
                com.openminis.app.ui.settings.KEY_TOOL_PREVIEW -> toolPreviewEnabled = sp.getBoolean(key, true)
                com.openminis.app.ui.settings.KEY_SHOW_CONTEXT_METER -> showContextMeter = sp.getBoolean(key, true)
                com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE -> showChatTitlePill = sp.getBoolean(key, true)
            }
        }
        appearancePrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { appearancePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val markdownFontScale = com.openminis.app.ui.settings.fontScaleForLevel(messageFontLevel)
    val chatInputFontScale = com.openminis.app.ui.settings.fontScaleForLevel(chatInputLevel)

    // [P3.3 裁军] 原 previewUrl（UrlPreviewSheet 内预览）+ htmlPreviewHolder
    // （WebViewHolder 沉浸式 HTML 预览，ui/preview/ 整包）+ 固定捷径
    // minis://session/<id>/<path> 深链消费块随内置浏览器退役删除：
    //  - web 链接 → openExternalUrl（系统浏览器外跳）
    //  - 会话内 HTML 文件 → onPreviewAttachment（FilePreviewScreen 的 WebView）
    //  - 会话内视频文件 → openMediaFileExternally（系统播放器）

    // T-imgswipe-4f446d83: replace previous single-image preview state with a
    // gallery (list + start index) so callers can pass sibling images (input
    // chip row, message attachments, file-browser dir contents). Single-image
    // taps still work — they pass a 1-item list.
    var previewImageGallery by composerEnv.previewImageGallery
    val urlClickHandler = remember<(String) -> Unit>(viewModel) {
        { url ->
            // Pass the current session id so `minis://attachments/...` resolves
            // against this chat's session directory rather than whichever
            // session booted its PRoot shell most recently (which is what
            // the global bindMounts map would answer).
            when (val action = ChatLinkResolver.resolve(url, viewModel.currentSessionId, context)) {
                is ChatLinkAction.DeepLink -> ChatLinkResolver.dispatchDeepLink(context, url)
                is ChatLinkAction.SandboxFile -> {
                    when {
                        action.item.isImageFile -> {
                            // Single image — caption = filename. Sibling
                            // collection from markdown context is not
                            // plumbed here (iOS does cross-session
                            // assistant images via fingerprint).
                            previewImageGallery = listOf(
                                com.openminis.app.ui.components.ImageGalleryItem(
                                    model = action.item.file,
                                    caption = action.item.name,
                                ),
                            ) to 0
                        }
                        // [P3.3 裁军] 视频改经 FileProvider 交给系统播放器
                        // （原 MinisFullscreenVideoPlayer 内嵌播放退役）；
                        // HTML 文件改走 FilePreviewScreen 的 WebView（原
                        // WebViewHolder 沉浸预览退役）。
                        action.item.isVideoFile ->
                            com.openminis.app.ui.components.openMediaFileExternally(context, action.item.file)
                        action.item.isHtmlFile -> onPreviewAttachment(action.item)
                        // T279: route through the NavHost FILE_PREVIEW destination
                        // (same path as user-bubble attachments and "Browse Chat Files")
                        // so FilePreviewScreen inherits the Activity's edge-to-edge
                        // window setup. The previous in-place Dialog wrapper had
                        // its own Window without enableEdgeToEdge, painting the
                        // platform default scrim on the status / nav bars.
                        else -> onPreviewAttachment(action.item)
                    }
                }
                // [P3.3 裁军] 外部 scheme（intent/market/tel/mailto…）与 web
                // 链接一律 ACTION_VIEW 外跳系统处理器（原
                // BrowserExternalSchemeHandler + UrlPreviewSheet 退役）。
                is ChatLinkAction.ExternalApp ->
                    com.openminis.app.ui.components.openExternalUrl(context, action.url)
                is ChatLinkAction.Web ->
                    com.openminis.app.ui.components.openExternalUrl(context, action.url)
            }
        }
    }

    // [T-android-markdown-image-gallery-cross-message] Collect every
    // `![alt](src)` markdown image emitted by any assistant message in the
    // current windowed view, in chronological order, then open the paged
    // ImageGalleryViewer positioned at the tapped image. Mirrors iOS
    // AIChatView.handleMarkdownImageTap (AIChatView.swift:2082). The regex
    // matches the standard inline image form; tool-block content stays
    // untouched (toolBlocks live in a separate AssistantBlock list, not
    // in `content`). Video/audio extensions are filtered out so the gallery
    // only contains still images. Resolution of `minis://` → host File is
    // deferred to the gallery's Coil model — Coil's MinisImageFetcher walks
    // the same session-aware resolver we use for inline rendering.
    val markdownImageTapHandler = remember<(String, String) -> Unit>(messages, sessionId) {
        handler@{ tappedMessageId, tappedUrl ->
            val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
            data class Ref(val messageId: String, val source: String, val title: String)
            val refs = mutableListOf<Ref>()
            for (msg in messages) {
                if (msg.role != "assistant") continue
                val content = msg.content
                if (content.isEmpty()) continue
                for (m in imageRegex.findAll(content)) {
                    val alt = m.groupValues.getOrNull(1).orEmpty()
                    val src = m.groupValues.getOrNull(2).orEmpty()
                    if (src.isEmpty()) continue
                    val pathPart = src.substringBefore('?').substringBefore('#')
                    val ext = pathPart.substringAfterLast('.', "").lowercase()
                    // Skip non-image media so the gallery stays still-image only,
                    // matching iOS minisVideoExtensions / minisAudioExtensions.
                    if (ext in setOf("mp4", "mov", "avi", "mkv", "webm",
                                     "mp3", "wav", "aac", "flac", "ogg", "m4a")) continue
                    val title = alt.ifEmpty { pathPart.substringAfterLast('/').ifEmpty { src } }
                    refs.add(Ref(msg.id, src, title))
                }
            }
            if (refs.isEmpty()) {
                // Defensive: tap arrived for a URL that isn't in the visible
                // window (compacted away, just deleted, etc.). Fall back to
                // the single-item URL handler so the user still sees the
                // tapped image rather than swallowing the tap silently.
                urlClickHandler(tappedUrl)
                return@handler
            }
            val startIndex = refs.indexOfFirst { it.messageId == tappedMessageId && it.source == tappedUrl }
                .takeIf { it >= 0 }
                ?: refs.indexOfFirst { it.source == tappedUrl }.takeIf { it >= 0 }
                ?: 0
            val items = refs.map { ref ->
                // Resolve minis://... / file:// / /abs → host File so Coil
                // doesn't have to re-walk ContentPaths for every page swipe.
                // Falls back to the raw URL string when resolution misses —
                // AsyncImage will route it through MinisImageFetcher anyway.
                val resolved = resolveMdMediaFile(context, ref.source, sessionId)
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = resolved ?: ref.source,
                    caption = ref.title,
                )
            }
            previewImageGallery = items to startIndex
        }
    }

    CompositionLocalProvider(
        LocalMarkdownFontScale provides markdownFontScale,
        LocalToolPreviewEnabled provides toolPreviewEnabled,
        LocalMarkdownUrlClickHandler provides urlClickHandler,
        LocalMarkdownImageTapHandler provides markdownImageTapHandler,
        // Route markdown media resolution through this chat's session so
        // minis://attachments/* lookups don't rely on the global bindMounts
        // map (which is last-writer-wins across sessions).
        LocalMarkdownSessionId provides sessionId,
        LocalImmersiveChatProfile provides immersiveProfile,
    ) {
    val immersiveBackground = immersiveProfile.backgroundPath
        ?.let { java.io.File(it) }
        ?.takeIf { it.exists() }
    val immersiveBackgroundPainter = rememberAsyncImagePainter(immersiveBackground)
    Scaffold(
        modifier = if (immersiveBackground != null) {
            Modifier.paint(immersiveBackgroundPainter, contentScale = ContentScale.Crop)
        } else Modifier,
        containerColor = if (immersiveBackground != null) {
            ChatColors.background.copy(alpha = 0.80f)
        } else ChatColors.background,
        contentWindowInsets = WindowInsets(0),
        // [feat/ui-rikkahub] 非对称三段式顶栏 → ChatScreenTopBar.kt（ChatTopBar）。
        topBar = {
            ChatTopBar(
                viewModel = viewModel,
                effectiveChromeCollapsed = effectiveChromeCollapsed,
                immersiveProfile = immersiveProfile,
                showChatTitlePill = showChatTitlePill,
                sessionTitle = sessionTitle,
                onTitleClick = {
                    coroutineScope.launch {
                        editingSession = viewModel.loadSessionEntity()
                    }
                },
                selectedGroupName = selectedGroupName,
                availableGroups = availableGroups,
                providerRepository = providerRepository,
                modelName = modelName,
                onOpenModelPicker = { showModelPicker = true },
                onOpenThinkingSheet = { showThinkingLevelSheet = true },
                returnFromConversation = returnFromConversation,
                sideParentId = sideParentId,
                onOpenSideDelete = { showSideDeleteDialog = true },
                chromeCollapsed = chromeCollapsed,
                onReviveChrome = {
                    chromeRevivedAtMs = System.currentTimeMillis()
                    chromeCollapsed = false
                },
                showChatMenu = showChatMenu,
                onChatMenuShown = { showChatMenu = true },
                onChatMenuDismissed = { showChatMenu = false },
                onShowHistory = { showHistoryNavigation = true },
                onSettings = onSettings,
                onRequestSidePanel = { requestSidePanel = true },
                onShowRecords = { showConversationRecords = true },
                onShowClearDialog = { showClearChatDialog = true },
                immersiveBackgroundPickerLauncher = immersiveBackgroundPickerLauncher,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // [T-chrome-fade] 内容永远只让出系统状态栏的高度，顶栏悬浮在其上：
        // 淡出时内容原地不动（此前收起会让内容上移补位，跳版式像出 bug）。
        // 顶栏可见时会盖住顶部一两行内容（用户已确认接受）。
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarTop, bottom = padding.calculateBottomPadding())
                .imePadding()
                // 任何触摸都重置沉浸淡化的空闲计时；淡出状态下还负责唤回
                // （600ms 内刚手动隐藏的不抢——那正是隐藏手势的收尾）。
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val now = System.currentTimeMillis()
                        lastInteractionAt = now
                        if (chromeCollapsed && sideParentId == null &&
                            now - chromeHiddenAtMs > 600 && now - chromeRevivedAtMs > 600
                        ) {
                            chromeRevivedAtMs = now
                            chromeCollapsed = false
                        }
                    }
                },
        ) {
            val playthroughState by viewModel.activePlaythroughState.collectAsState()
            var latestDataUpdate by remember(sessionId) { mutableStateOf<NovexDataUpdateEvent?>(null) }
            LaunchedEffect(viewModel, sessionId) {
                viewModel.novexDataUpdates.collect { event -> latestDataUpdate = event }
            }
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            if (isCompactingNow) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("正在压缩对话，原消息保留", Modifier.weight(1f))
                novex.android.ui.TextButton(onClick = viewModel::cancelCompaction) { Text("停止") }
            }

            // Dismiss keyboard when the USER scrolls the messages. Gated on
            // `isUserDragging` (a real finger drag) rather than
            // `listState.isScrollInProgress` — the latter is also true during
            // explicit programmatic navigation, so the old code hid the
            // keyboard + cleared focus during a jump, which
            // closed the IME mid-stream and dropped the user's in-flight
            // keystroke. [T-android-composer-input-blocked-while-streaming]
            LaunchedEffect(isUserDragging) {
                if (isUserDragging) {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            }

            // Messages + scroll-to-bottom button
            Box(modifier = Modifier.weight(1f).pointerInput(Unit) {
                // 空白处单点 = 立即淡出界面（消息行自身的点击不会到达这里）；
                // 淡出状态下的唤回由外层触摸计时统一负责——同一次手势里两个
                // 手势器都会触发，这里跳过"刚被唤回"的那一下，避免刚淡入又被
                // 这里拍回去。
                detectTapGestures {
                    val now = System.currentTimeMillis()
                    if (!chromeCollapsed && now - chromeRevivedAtMs > 600) {
                        chromeCollapsed = true
                        chromeHiddenAtMs = now
                    }
                }
            }) {
                // Execution has one home: the collapsed transcript process. Do not duplicate
                // raw tool thumbnails above the composer or reserve space for a hidden overlay.
                val bottomReserve = 20.dp
                // Toolbar, tool-card and thinking-indicator remeasurements are
                // passive layout events. They intentionally never scroll; the
                // return-to-latest button is the explicit affordance.

                // Flatten each message into multiple LazyColumn items so that older blocks
                // (text / tool pills / thinking) are frozen LazyList items while only the
                // last streaming block changes height. This keeps scroll-hovering stable:
                // LazyListState anchors on a stable item key + pixel offset, and inserting
                // or growing the trailing item never disturbs earlier items.
                //
                // T94: long sessions (hundreds of messages, deep tool chains) made the
                // flatten step expensive enough to stall composition on the main thread —
                // every streaming token recomposed the parent and re-ran the O(N · blocks)
                // walk inside `remember`, producing visible jank and ANRs on slower
                // devices. Run the flatten on Dispatchers.Default and publish the result
                // through a snapshot-state field so the LazyColumn renders the previous
                // frame's list while the next one computes. Keyed on `sessionId` so a
                // chat-switch resets the cache; LaunchedEffect(messages) reruns the
                // computation on every new emission.
                val showAssistantIdentity = immersiveProfile.usesRolePresentation
                var transcriptViewportReady by remember(viewModel) {
                    mutableStateOf(viewModel.isTranscriptViewportPositioned)
                }
                val coldOpenDiag = remember(sessionId) { ColdOpenDiag() }
                var flatItems by rememberTranscriptRows(
                    viewModel = viewModel,
                    sessionId = sessionId,
                    transcriptMessages = transcriptMessages,
                    messages = messages,
                    showAssistantIdentity = showAssistantIdentity,
                    hasOlderMessages = hasOlderMessages,
                    listState = listState,
                    scrollToLatestOnce = scrollToLatestOnce,
                    tracedScrollToItem = tracedScrollToItem,
                    transcriptFollowState = transcriptFollowState,
                    onFollowStateChange = { transcriptFollowState = it },
                    lastUserDragAtMs = lastUserDragAtMs,
                    onUserDrag = { lastUserDragAtMs = it },
                    submittedTurnNavigation = submittedTurnNavigation,
                    onSubmittedNavigation = { submittedTurnNavigation = it },
                    coldOpenDiag = coldOpenDiag,
                    onViewportReady = { transcriptViewportReady = true },
                )
                openedProcess?.let { opened ->
                    val process = flatItems.filterIsInstance<FlatChatItem.AssistantProcess>()
                        .firstOrNull { it.key == opened.key } ?: opened
                    NovexExecutionProcessDialog(process, onDismiss = { openedProcess = null }, onOpenTool = {
                        openedProcess = null
                        viewModel.openToolDetail(it.id)
                    })
                }
                var streamWasRunning by remember(sessionId) { mutableStateOf(isStreaming) }
                LaunchedEffect(isStreaming) {
                    com.openminis.app.crash.ProcessExitEvidence.record(context, "chat.streaming=$isStreaming")
                    if (streamWasRunning && !isStreaming && transcriptFollowState.isFollowingLatest) {
                        scrollToLatestOnce(TranscriptViewportMove.StreamCompleted)
                        transcriptFollowState = transcriptFollowState.after(
                            TranscriptFollowEvent.StreamCompleted,
                        )
                    }
                    streamWasRunning = isStreaming
                }
                fun FlatChatItem.historyMessageId(): String? = when (this) {
                    is FlatChatItem.UserBubble -> message.id
                    is FlatChatItem.AssistantProcess -> messageId
                    is FlatChatItem.AssistantHeader -> messageId
                    is FlatChatItem.AssistantText -> messageId
                    is FlatChatItem.AssistantMarkdownBlock -> messageId
                    is FlatChatItem.AssistantThinking -> messageId
                    is FlatChatItem.AssistantToolUse -> messageId
                    is FlatChatItem.AssistantFallbackChoices -> messageId
                    is FlatChatItem.AssistantError -> messageId
                    is FlatChatItem.AssistantLegacyContent -> messageId
                    else -> null
                }
                LaunchedEffect(historyJumpId, flatItems) {
                    val target = historyJumpId ?: return@LaunchedEffect
                    val rows = transcriptRowsForLayout(flatItems)
                    val index = rows.indexOfFirst { it.historyMessageId()?.substringBefore('#') == target }
                    if (index >= 0) {
                        transcriptFollowState = transcriptFollowState.after(TranscriptFollowEvent.UserDragStarted)
                        tracedScrollToItem("history-navigation", index + if (hasOlderMessages) 1 else 0, 0)
                        historyJumpId = null
                    }
                }
                // SelectionContainer must wrap the WHOLE LazyColumn — placing
                // it per-item breaks long-press because items get disposed
                // when scrolled out and the selection registrar/detector goes
                // with them. One outer SelectionContainer registers each Text
                // child as it enters composition, and the long-press gesture
                // detector lives at this stable scope. Mirrors Compose's
                // recommended LazyColumn + selection pattern.
                //
                // The custom LocalTextToolbar replaces the system Copy bar
                // with a 3-button popup (Copy / Copy Markdown / Copy Rich
                // Text); the latter two read from the bounds registry which
                // each AssistantMessageView updates via onGloballyPositioned.
                // 转录区（SelectionContainer + LazyColumn + 选区手柄）→
                // ChatTranscript.kt；跟随/滚动策略状态仍由本函数持有。
                ChatTranscript(
                    viewModel = viewModel,
                    sessionId = sessionId,
                    listState = listState,
                    flatItems = flatItems,
                    messages = messages,
                    error = error,
                    canResume = canResume,
                    hasOlderMessages = hasOlderMessages,
                    isStreaming = isStreaming,
                    inputText = inputText,
                    inputFocusRequester = inputFocusRequester,
                    immersiveProfile = immersiveProfile,
                    sessionTitle = sessionTitle,
                    perTurnPrompt = perTurnPrompt,
                    streamAwaitingSince = streamAwaitingSince,
                    compactedHistoryExpanded = compactedHistoryExpanded,
                    onToggleCompactedHistory = { compactedHistoryExpanded = !compactedHistoryExpanded },
                    transcriptViewportReady = transcriptViewportReady,
                    coldOpenDiag = coldOpenDiag,
                    openedProcess = openedProcess,
                    onOpenProcess = { openedProcess = it },
                    onShareSnippet = { pendingShareText = it },
                    onPendingDelete = { pendingDeleteFromMessageId = it },
                    onShowMoveSheet = { showMoveSheet = true },
                    panelExpansionState = panelExpansionState,
                    bottomReserve = bottomReserve,
                    scrollToLatestOnce = scrollToLatestOnce,
                    onOpenCreatedCard = onOpenCreatedCard,
                    onPreviewAttachment = onPreviewAttachment,
                )

                // 浮动工具条哨兵 + 上/下回合 FAB → ChatTranscriptOverlay.kt
                ChatTranscriptOverlay(
                    viewModel = viewModel,
                    messages = messages,
                    transcriptViewportReady = transcriptViewportReady,
                    isNearBottom = isNearBottom,
                    contentOverflows = contentOverflows,
                    chromeFadeAlpha = chromeFadeAlpha,
                    isStreaming = isStreaming,
                    transcriptFollowState = transcriptFollowState,
                    onFollowStateChange = { transcriptFollowState = it },
                    onJumpedUserCleared = { lastJumpedUserId = null },
                    scrollToPreviousUserTurn = scrollToPreviousUserTurn,
                    scrollToLatestOnce = scrollToLatestOnce,
                )
                // above the LazyColumn (top-center), animates in once the
            }

            // T-chat-title-pill-edit: reuse SessionEditSheet from the session
            // list (same composable, exposed `internal`) so title + category
            // edits from the in-chat pill are visually + behaviourally
            // identical to the home-screen long-press flow.
            editingSession?.let { session ->
                com.openminis.app.ui.sessions.SessionEditSheet(
                    session = session,
                    onDismiss = { editingSession = null },
                    onSave = { newTitle, newCategory ->
                        viewModel.updateTitleAndCategory(newTitle, newCategory)
                        editingSession = null
                    },
                )
            }

            // ─── Input area → ChatComposerSection.kt（@/斜杠菜单、附件条、
            // ContextMeter、发送键与上滑手势、Move-to 胶囊角标）。交互状态经
            // composerEnv 与本函数共享。
            ChatComposerSection(
                viewModel = viewModel,
                env = composerEnv,
                attachments = attachments,
                inputText = inputText,
                isStreaming = isStreaming,
                inputFocusRequester = inputFocusRequester,
                composerInputSynchronizer = composerInputSynchronizer,
                chatInputFontScale = chatInputFontScale,
                novexControls = novexControls,
                sideHandoffState = sideHandoffState,
                sideParentId = sideParentId,
                contextCapacity = contextCapacity,
                contextEstimated = contextEstimated,
                contextUsageReady = contextUsageReady,
                lastTurnContextTokens = lastTurnContextTokens,
                contextMeterMode = contextMeterMode,
                onContextMeterModeChange = { contextMeterMode = it },
                performSendOrEnqueue = performSendOrEnqueue,
                startHandoff = startHandoff,
                launchCamera = launchCamera,
                mediaPickerLauncher = mediaPickerLauncher,
                cameraPermissionLauncher = cameraPermissionLauncher,
                filePickerLauncher = filePickerLauncher,
                onPreviewAttachment = onPreviewAttachment,
            )

            // 就地对话框群（移动/全屏编写/删会话/发送门槛等）→
            // ChatScreenInlineDialogs.kt，开关位仍由本函数持有。
            ChatScreenInlineDialogs(
                viewModel = viewModel,
                sessionId = sessionId,
                chatRepository = chatRepository,
                immersiveProfile = immersiveProfile,
                inputText = inputText,
                novexControlView = novexControlView,
                pendingDeleteFromMessageId = pendingDeleteFromMessageId,
                onPendingDeleteCleared = { pendingDeleteFromMessageId = null },
                pendingShareText = pendingShareText,
                onShareTextCleared = { pendingShareText = null },
                showMoveSheet = showMoveSheet,
                onMoveSheetDismissed = { showMoveSheet = false },
                onMoveToSession = onMoveToSession,
                onImportCard = onImportCard,
                showComposerExpanded = showComposerExpanded,
                onComposerExpandedDismissed = { showComposerExpanded = false },
                keyboardController = keyboardController,
                showConversationRecords = showConversationRecords,
                onRecordsDismissed = { showConversationRecords = false },
                onBrowseChatFiles = onBrowseChatFiles,
                showClearChatDialog = showClearChatDialog,
                onClearChatDismissed = { showClearChatDialog = false },
                onBack = onBack,
                showNewChatStopDialog = showNewChatStopDialog,
                onNewChatStopDismissed = { showNewChatStopDialog = false },
                onNewChat = onNewChat,
                showEnhancedCacheDialog = showEnhancedCacheDialog,
                onEnhancedCacheDismissed = { showEnhancedCacheDialog = false },
            )
        }
        // Top gradient fade: messages fade into the Scaffold background.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(6.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            ChatColors.background,
                            ChatColors.background.copy(alpha = 0f),
                        ),
                    )
                )
        )
        // 侧边组件轨（2026-09-15 第三轮）：书签横条左缘、跨主线↔侧边页常驻；
        // 状态半圆右缘仅主线；淡化系统由本屏驱动（chromeCollapsed）。
        NovexSideConversations(
            railSessionId = sideParentId ?: sessionId,
            currentSessionId = sessionId,
            isSidePage = sideParentId != null,
            chatRepository = chatRepository,
            requestNewSide = requestSideConversation,
            onNewSideConsumed = { requestSideConversation = false },
            requestSidePanel = requestSidePanel,
            onSidePanelConsumed = { requestSidePanel = false },
            onOpenSide = onOpenSideSession,
            handle = if (sideParentId == null) {
                val railState = playthroughState
                if (railState != null) NovexEdgeHandleSpec(
                    state = railState,
                    update = latestDataUpdate,
                    onDismissUpdate = { latestDataUpdate = null },
                ) else null
            } else null,
            chromeCollapsed = chromeCollapsed,
        )
        }

    // 尾部对话框/Sheet 宿主（ChatScreenOverlays.kt）：思维级别、模型
    // 选择、资料学习全家、记忆面板、导出/检查点、图库等。
    ChatScreenOverlays(
        viewModel = viewModel,
        providerRepository = providerRepository,
        memoryRepository = memoryRepository,
        availableGroups = availableGroups,
        onSettings = onSettings,
        onBrowseChatFiles = onBrowseChatFiles,
        onModelGroupsClick = onModelGroupsClick,
        showThinkingLevelSheet = showThinkingLevelSheet,
        onThinkingDismissed = { showThinkingLevelSheet = false },
        showModelPicker = showModelPicker,
        onModelPickerDismissed = { showModelPicker = false },
        previewGallery = previewImageGallery,
        onGalleryDismissed = { previewImageGallery = null },
    )
    } // CompositionLocalProvider
    }
}

internal fun PlaythroughValue.novexDisplayValue(): String = when (this) {
    is PlaythroughValue.Text -> value
    is PlaythroughValue.Number -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is PlaythroughValue.Flag -> if (value) "是" else "否"
}

// ─── [A2a] Bound-card chips in the chat top bar ──────────────────────────────

// [T-android-split-chat] UserMessageBubble / UserAttachmentList /
// FileAttachmentTile / fileIconFor / ImageGalleryDialog moved verbatim to
// ChatUserMessageUI.kt.
// [T-android-split-chat] FlatChatItem / mergeStreamingOverlay / buildFlatChatItems
// moved verbatim to ChatFlatItems.kt (now internal).

// [T-android-split-chat] AssistantHeader / AssistantMessageView /
// BoundsTrackedBlock / InlineErrorBanner / ToolStopButton /
// formatToolDetailsForClipboard / ToolCallPill / ThinkingBlock moved verbatim
// to ChatAssistantMessageUI.kt.

// ─── Tool Detail Bottom Sheet (iOS: ToolLiveSheet — nav bar + content + bottom bar) ──

// [T-android-split-chat] ToolDetailSheet + helpers (extractShellCommand,
// extractPartialJsonString, chunkToolOutput, initialRevealChunks,
// LazyRevealToolText, EditorCard) moved verbatim to ChatToolDetailUI.kt.
// [T-android-split-chat] AttachmentChip / InputCircleButton / MicButton /
// ToolPreviewThumbnail / FloatingToolStatusBar / ThinkingLevelPicker moved
// verbatim to ChatComposerWidgets.kt.

// [T-android-split-chat] createCameraOutputUri / getFileName /
// Model selection confirmation is owned by ChatModelSelectionSheet.


// [T-android-split-chat] fuzzyMatch / ModelPickerSheet / providerDotColor moved
// verbatim to ChatModelPickerSheet.kt (ModelPickerSheet now internal).

// [T-android-split-chat] BorderedMarkdownTable / FallbackInfoBlock /
// CompactSummarySheet / parseInlineMarkdown / rememberBrowserLiveSnapshot /
// ResumeBanner / SwipeToSendHint moved verbatim to ChatMiscViews.kt.
// Sun May 24 11:01:25 CST 2026

