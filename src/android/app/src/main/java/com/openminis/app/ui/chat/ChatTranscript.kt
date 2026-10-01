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

import com.openminis.app.data.character.ImmersiveChatProfile

/**
 * 消息转录区：SelectionContainer（Markdown 工具条 + 选区手柄）包住整个
 * 反排 LazyColumn，外加选区/常驻句柄。原先是 ChatScreen 内联段（T29/T30/
 * T170/T258/T261/T342/T138 的滚动与选区语义全部保留）。
 */
@Composable
internal fun ChatTranscript(
    viewModel: ChatViewModel,
    sessionId: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    flatItems: List<FlatChatItem>,
    messages: List<ChatMessage>,
    error: String?,
    canResume: Boolean,
    hasOlderMessages: Boolean,
    isStreaming: Boolean,
    inputText: String,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    immersiveProfile: ImmersiveChatProfile,
    sessionTitle: String,
    perTurnPrompt: String,
    streamAwaitingSince: Long?,
    compactedHistoryExpanded: Boolean,
    onToggleCompactedHistory: () -> Unit,
    transcriptViewportReady: Boolean,
    coldOpenDiag: ColdOpenDiag,
    openedProcess: FlatChatItem.AssistantProcess?,
    onOpenProcess: (FlatChatItem.AssistantProcess?) -> Unit,
    onShareSnippet: (String) -> Unit,
    onPendingDelete: (String) -> Unit,
    onShowMoveSheet: () -> Unit,
    panelExpansionState: PanelExpansionState,
    bottomReserve: androidx.compose.ui.unit.Dp,
    scrollToLatestOnce: suspend (TranscriptViewportMove) -> Unit,
    onOpenCreatedCard: (String, String) -> Unit,
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                val messageBounds = remember { MessageBoundsRegistry() }
                // [T-selection-add-to-input] Toolbar's "Add to Chat Input"
                // action funnels the selected substring back into the
                // composer via the same StateFlow that the TextField is
                // bound to. Capture `viewModel` by reference so the
                // toolbar instance survives recomposition without
                // re-creation.
                // [T-add-to-input-focus] After append, request focus on the
                // composer + pop the soft keyboard so the user can keep
                // typing without an extra tap. Keyboard `show()` is best-effort
                // (controller may be null pre-attach); focus is guarded against
                // FocusRequester-not-attached the same way the auto-focus path
                // elsewhere in this file is.
                // MinisTextKit selection controller — declared BEFORE the
                // markdown toolbar so the toolbar can read table actions off it
                // ([T-android-markdown-table-copy-actions]). Hoisted ABOVE the
                // LazyColumn so item dispose can't kill the selection: when a
                // shard scrolls out of viewport it deregisters its TextShard,
                // but the (messageId, shardId, charOffset) endpoints stay valid;
                // scrolling back in re-registers the shard and the highlight
                // redraws automatically.
                val selectionController = remember { SelectionController() }
                // [P3.3 裁军] selectionReader（LazyReadAloudPlayer，选区朗读）
                // 随语音全家退役；工具栏不再提供“朗读”动作。
                val markdownToolbar = remember(context, messageBounds, viewModel, inputFocusRequester, keyboardController, selectionController) {
                    MinisMarkdownTextToolbar(
                        context = context,
                        registry = messageBounds,
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try {
                                inputFocusRequester.requestFocus()
                            } catch (_: IllegalStateException) {
                                // FocusRequester not yet attached — composer
                                // will gain focus on next user tap.
                            }
                            keyboardController?.show()
                        },
                        onShare = { snippet ->
                            onShareSnippet(snippet)
                            onShowMoveSheet()
                        },
                        selectionController = selectionController,
                    )
                }
                // Wrap any callback that truncates / replaces / removes rows from
                // the message list. Hiding the toolbar + clearing focus tears down
                // the SelectionManager's pending toolbar update before the
                // SelectionContainer subtree gets reshuffled — without this,
                // notifySelectionUpdateEnd → updateSelectionToolbar → getContentRect
                // → sort hits stale LayoutCoordinates and crashes with
                // "layouts are not part of the same hierarchy".
                val safeMutate: (() -> Unit) -> Unit = { block ->
                    markdownToolbar.hide()
                    focusManager.clearFocus()
                    block()
                }
                // Hoist slash-menu state up so the LazyColumn pointerInput
                // tap-spy below can react to it. The popup itself, declared
                // further down near the composer, reads viewModel.showSlashMenu
                // again — both subscriptions snap to the same StateFlow.
                val slashMenuOpen by viewModel.showSlashMenu.collectAsState()
                // T4: mirror state hoist for the mention picker so the chat-list
                // tap-spy can dismiss it the same way as the slash popup.
                val mentionMenuOpenForSpy by viewModel.showMentionMenu.collectAsState()
                // Intercept back press to dismiss slash/mention menus before
                // navigating away from the chat screen.
                androidx.activity.compose.BackHandler(
                    enabled = slashMenuOpen || mentionMenuOpenForSpy
                ) {
                    if (slashMenuOpen) {
                        viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                    }
                    if (mentionMenuOpenForSpy) {
                        viewModel.dismissMentionMenu()
                    }
                }
                // (selectionController declared above, before markdownToolbar.)
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalMessageBoundsRegistry provides messageBounds,
                    androidx.compose.ui.platform.LocalTextToolbar provides markdownToolbar,
                    LocalMinisSelectionController provides selectionController,
                    // [T-stream-stall-watchdog] TypingIndicator reads this to
                    // show "已等待 X 秒" while the first chunk of the in-flight
                    // request has not arrived (silent-relay visibility).
                    LocalStreamAwaitingSince provides streamAwaitingSince,
                ) {
                // Hoisted out of AlwaysStretchOverscrollBox lambda so
                // SelectionDragTracker (which lives outside the lambda) can
                // read the LazyColumn's window-space root coords for edge
                // auto-scroll calculations.
                var listRootCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
                // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
                // fires once per session when the LazyColumn first reports
                // a layout. Combined with `buildFlatChatItems.firstBuild`
                // (above) and `lazyColumn.firstItem.placed` (below) this
                // tells us whether the bottleneck is row-list build,
                // initial list measure, or per-row composition.
                val perfFirstLayoutFired = remember(sessionId) { java.util.concurrent.atomic.AtomicBoolean(false) }
                // [feat/ui-rikkahub] Per-reply action row anchors, recomputed
                // with the flat list (the scan is O(n) against joins the
                // builder already does per chunk).
                val assistantActions = remember(flatItems) { assistantActionAnchors(flatItems) }
                Box {
                AlwaysStretchOverscrollBox { sharedEffect ->
                LazyColumn(
                    state = listState,
                    reverseLayout = CHAT_TRANSCRIPT_REVERSE_LAYOUT,
                    // T30: when no tool status bar is rendered, a small bottom
                    // padding keeps the latest message off the composer's
                    // top edge so the conversation breathes. Reuses the same
                    // bottomReserve when the toolbar is present.
                    // Tuned so the visible gap to the composer's outer edge is ~18dp.
                    //
                    // [T-android-chat-first-message-top-padding] top reduced
                    // 12dp → 4dp. The first message's gap below the model
                    // title bar was top(12) + the first bubble's own top(4) =
                    // 16dp (≈44px @ 440dpi) — looser than needed. 4dp here +
                    // the bubble's 4dp = 8dp (≈22px), tighter but still a clear
                    // breath under the title bar. Bottom padding and inter-
                    // message spacing are untouched.
                    // [feat/ui-rikkahub] 2026-09-27 顶部让出悬浮标题栏的真实高度
                    // （76-120dp 按字号缩放）+ 8dp 呼吸：新对话的第一条消息不再
                    // 被透明标题栏盖住（此前只让 4/16dp，首条直接顶进栏底）。
                    contentPadding = PaddingValues(
                        top = chatTopBarExpandedHeightDp(LocalDensity.current.fontScale).dp + 8.dp,
                        bottom = if (bottomReserve == 0.dp) 12.dp else bottomReserve,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (transcriptViewportReady || flatItems.isEmpty()) 1f else 0f)
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned {
                            listRootCoords = it
                            if (perfFirstLayoutFired.compareAndSet(false, true)) {
                                val info = listState.layoutInfo
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstLayout",
                                    "totalItems=${info.totalItemsCount} visibleItems=${info.visibleItemsInfo.size} viewport=${info.viewportSize.width}x${info.viewportSize.height}",
                                )
                            }
                        }
                        .minisTextKitSelectionGesture(
                            controller = selectionController,
                            listState = listState,
                            rootCoordinates = { listRootCoords },
                            // Keep selection-edge scrolling aligned with the
                            // transcript's chronological list orientation.
                            reverseLayout = CHAT_TRANSCRIPT_REVERSE_LAYOUT,
                        )
                        // T29 dismiss-on-tap spy. Only active while the slash
                        // popup is showing. awaitFirstDown(requireUnconsumed=false,
                        // pass=Initial) lets us see the tap *before* any child
                        // gesture (LazyColumn scroll, message long-press) without
                        // consuming it — the gesture continues to its real
                        // handler. We close the menu on the very first finger
                        // down anywhere inside the chat list, exactly like
                        // tapping outside an iOS popover.
                        .pointerInput(slashMenuOpen, mentionMenuOpenForSpy) {
                            if (!slashMenuOpen && !mentionMenuOpenForSpy) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial,
                                )
                                if (slashMenuOpen) {
                                    viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                                }
                                if (mentionMenuOpenForSpy) {
                                    viewModel.dismissMentionMenu()
                                }
                            }
                        },
                    // Short transcripts also grow from the visual top. Bottom
                    // alignment would move every existing line whenever the
                    // active reply gains height, producing the reported creep.
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Top),
                    overscrollEffect = sharedEffect,
                ) {
                    // Normal chronological layout places history loading before
                    // the oldest row. Stable item keys preserve the reader's
                    // anchor when the older window is prepended.
                    if (hasOlderMessages) {
                        item(key = "__load_older_messages__", contentType = "load_older") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { viewModel.loadOlderMessages() }
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_load_older_messages),
                                    color = ChatColors.secondaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    items(
                        items = transcriptRowsForLayout(flatItems),
                        key = { it.key },
                        contentType = { it.contentType },
                    ) { item ->
                        // [Perf][LongCtx] T-android-long-ctx-reentry-perf:
                        // the newest row is the one initially revealed — its
                        // onPlaced is the moment the user actually sees
                        // content. SideEffect fires on first composition
                        // (before measure); onPlaced fires after layout.
                        if (item == flatItems.lastOrNull()) {
                            androidx.compose.runtime.SideEffect {
                                com.openminis.app.diagnostics.PerfLongCtx.step(
                                    sessionId,
                                    "lazyColumn.firstItem.compose",
                                )
                            }
                        }
                        // [Perf][LongCtx] aggregate compose-count tracker.
                        // Each row that enters composition during the reentry
                        // burst increments the per-session counter. When the
                        // 10th and 50th rows hit, emit one line each carrying
                        // the wall-time since `lazyColumn.firstLayout` plus
                        // the row's class — gives a "per-N-rows compose
                        // budget" signal without per-row log spam.
                        com.openminis.app.diagnostics.PerfLongCtx.maybeReportRowComposed(
                            sessionId,
                            item::class.java.simpleName,
                        )
                        // 0.4f matches iOS .opacity(0.5) closely once Compose's
                        // sRGB compositing is factored in. Renders below normal
                        // intensity but the message stays selectable + readable.
                        val rowAlpha = 1f
                        // [T-HANG-DIAG] log on first composition of any item
                        // whose content is large enough to be a likely hang
                        // suspect. SideEffect runs after the first successful
                        // composition; if rendering stalls on the way to that
                        // SideEffect, we'll see the LAUNCH-RENDER line for it
                        // immediately followed by the watchdog's HANG dump
                        // and the missing FINISH-RENDER tells us this is the
                        // item that locked up the layout pass. Gated on size
                        // so normal turns don't spam the log.
                        val tHangDiagLen = remember(item.key) {
                            when (item) {
                                is FlatChatItem.UserBubble -> item.message.content.length
                                is FlatChatItem.AssistantText -> item.messageMarkdown.length
                                else -> 0
                            }
                        }
                        if (tHangDiagLen >= 50_000) {
                            androidx.compose.runtime.SideEffect {
                                println(
                                    "[T-HANG-DIAG] LAUNCH-RENDER key=${item.key} " +
                                        "type=${item::class.java.simpleName} len=$tHangDiagLen",
                                )
                            }
                            androidx.compose.runtime.DisposableEffect(item.key) {
                                onDispose {
                                    println("[T-HANG-DIAG] FINISH-RENDER key=${item.key} (composed → disposed)")
                                }
                            }
                        }
                        val isNewestItem = item == flatItems.lastOrNull()
                        Box(
                            modifier = Modifier
                                .alpha(rowAlpha)
                                .then(
                                    if (isNewestItem) {
                                        Modifier.onPlaced {
                                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                                sessionId,
                                                "lazyColumn.firstItem.placed",
                                                "size=${it.size.width}x${it.size.height}",
                                            )
                                            // [T-android-jank-diag-logging]
                                            // One quotable line per session
                                            // open, after the first frame's
                                            // newest row has laid out.
                                            if (!coldOpenDiag.summaryEmitted) {
                                                coldOpenDiag.summaryEmitted = true
                                                val totalChars = messages.sumOf { m -> m.content.length }
                                                val maxChars = messages.maxOfOrNull { m -> m.content.length } ?: 0
                                                AppLogger.info(
                                                    "JankDiag",
                                                    "[JankDiag] coldOpen summary session=$sessionId msgs=${messages.size} rows=${flatItems.size} " +
                                                        "totalChars=$totalChars maxChars=$maxChars prewarmMs=$coldOpenDiag.lastPrewarmMs " +
                                                        "sinceMountMs=${System.currentTimeMillis() - coldOpenDiag.mountAtMs} " +
                                                        "hangCount=${com.openminis.app.diagnostics.HangDetector.currentHangCount(context)}",
                                                )
                                                // [T-android-content-perf-diag] Per-large-message structural
                                                // fingerprint so a future hang report maps straight to "which
                                                // message, what structure" without re-querying the DB. Gated at
                                                // 5000 chars — small messages never drive a render hang.
                                                messages.forEachIndexed { idx, m ->
                                                    if (m.content.length >= com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS) {
                                                        val s = com.openminis.app.diagnostics.ContentDiag.summarize(m.content)
                                                        AppLogger.info(
                                                            "Perf",
                                                            "[Perf][ContentDiag] session=$sessionId msgIdx=$idx role=${m.role} " +
                                                                "streaming=${m.isStreaming} ${s.asLogFields()}",
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                        Column {
                        ChatTranscriptRow(item,
                            ChatTranscriptRowState(isStreaming, canResume, viewModel.thinkingLevel.value, compactedHistoryExpanded),
                            selectionController, panelExpansionState, perTurnPrompt = perTurnPrompt) { action ->
                            when (action) {
                                is ChatTranscriptAction.Share -> { onShareSnippet(action.text); onShowMoveSheet() }
                                is ChatTranscriptAction.RetryFrom -> {
                                    safeMutate { viewModel.retryFromMessage(action.messageId) }
                                    coroutineScope.launch { scrollToLatestOnce(TranscriptViewportMove.UserRetriedTurn) }
                                }
                                is ChatTranscriptAction.Edit -> viewModel.editMessage(action.messageId)?.let { text ->
                                    viewModel.setInputText(text); inputFocusRequester.requestFocus()
                                }
                                is ChatTranscriptAction.DeleteFrom -> onPendingDelete(action.messageId)
                                is ChatTranscriptAction.Withdraw -> safeMutate { viewModel.withdrawQueuedMessage(action.messageId) }
                                is ChatTranscriptAction.PreviewAttachment -> onPreviewAttachment(action.file)
                                is ChatTranscriptAction.Prefill -> { viewModel.setInputText(action.text); inputFocusRequester.requestFocus() }
                                is ChatTranscriptAction.OpenProcess -> onOpenProcess(action.process)
                                is ChatTranscriptAction.RetryLast -> {
                                    safeMutate { viewModel.retryLast() }
                                    if (action.navigateToLatest) coroutineScope.launch { scrollToLatestOnce(TranscriptViewportMove.UserRetriedTurn) }
                                }
                                ChatTranscriptAction.Stop -> viewModel.cancelStream()
                                is ChatTranscriptAction.OpenToolDetail -> viewModel.openToolDetail(action.id)
                                is ChatTranscriptAction.RerunFrom -> {
                                    safeMutate { viewModel.rerunFromToolBlock(action.messageId, action.blockId) }
                                    coroutineScope.launch { scrollToLatestOnce(TranscriptViewportMove.UserRetriedTurn) }
                                }
                                is ChatTranscriptAction.OpenCard -> onOpenCreatedCard(action.kind, action.id)
                                ChatTranscriptAction.RevertCompact -> viewModel.revertCompact()
                                ChatTranscriptAction.ToggleCompactedHistory -> onToggleCompactedHistory()
                                is ChatTranscriptAction.SwitchBranch -> safeMutate { viewModel.switchMessageBranch(action.messageId, action.delta) }
                            }
                        }
                        // [feat/ui-rikkahub] Per-reply action row — anchored
                        // under the message's LAST flat item, inside the same
                        // LazyColumn slot so showing it never inserts a row or
                        // shifts the scroll anchor.
                        assistantActions[item.key]?.let { anchor ->
                            if (!anchor.isStreaming) {
                                AssistantMessageActionRow(
                                    markdown = anchor.markdown,
                                    showMutations = !isStreaming,
                                    onShare = { onShareSnippet(anchor.markdown); onShowMoveSheet() },
                                    onRegenerate = {
                                        safeMutate { viewModel.retryFromAssistantMessage(anchor.messageId) }
                                        coroutineScope.launch { scrollToLatestOnce(TranscriptViewportMove.UserRetriedTurn) }
                                    },
                                    onDelete = { onPendingDelete(anchor.messageId) },
                                )
                            }
                        }
                        }
                        } // Box (alpha wrapper)
                    }
                    // The resume action belongs after the newest message in a
                    // chronological list. It performs its own one-shot latest
                    // navigation through forceScrollToBottom and never enables
                    // passive streaming follow.
                    val lastAssistantHasError = messages
                        .lastOrNull { it.role == "assistant" }
                        ?.error
                        ?.isNotBlank() == true
                    if (canResume && !isStreaming && error == null && !lastAssistantHasError) {
                        item(key = "__resume_banner__", contentType = "resume_banner") {
                            ResumeBanner(onResume = viewModel::resume)
                        }
                    }
                }
                } // AlwaysStretchOverscrollBox
                if (messages.isEmpty() && !isStreaming) {
                    val character = immersiveProfile.character
                    val assistantName = immersiveProfile.effectiveAssistantName
                    if (immersiveProfile.usesRolePresentation && assistantName != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
                        ) {
                            immersiveProfile.effectiveAssistantAvatarPath?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { avatar ->
                                AsyncImage(
                                    model = avatar,
                                    contentDescription = "$assistantName 头像",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(84.dp).clip(CircleShape),
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            Text(assistantName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            val greeting = character?.greeting?.ifBlank { character.summary }.orEmpty()
                            if (greeting.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = greeting,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        // [A2c-empty] 空态两行：问候 + 语境行（绑卡会话点出所在
                        // 世界，无卡会话只留问候）。不放 logo 标记。
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
                        ) {
                            Text(
                                text = "想聊些什么，或一起创作？",
                                color = ChatColors.primaryText.copy(alpha = 0.85f),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                            if (sessionTitle.isNotBlank() && sessionTitle != "New Chat") {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "与「$sessionTitle」的世界一同落笔",
                                    color = ChatColors.secondaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                // SelectionDragTracker bridges gesture-published dragIntent
                // with listState scroll observation — that's what keeps the
                // selection extending across newly-scrolled-in shards when
                // the user's finger is stationary in the edge auto-scroll
                // zone (the inline pointer loop can't see those because
                // it only fires on pointer events).
                SelectionDragTracker(
                    controller = selectionController,
                    listState = listState,
                    listRootCoordinates = { listRootCoords },
                    reverseLayout = CHAT_TRANSCRIPT_REVERSE_LAYOUT,
                )
                MinisMarkdownTextToolbarHost(markdownToolbar)
                // MinisTextKit floating toolbar — driven by selectionController.
                MinisSelectionToolbarHost(
                    controller = selectionController,
                    // Clamp the menu's vertical position inside the
                    // LazyColumn's viewport in window coords, so it can't
                    // float above the chat header or below the composer /
                    // navigation bar. Computed lazily so the menu picks up
                    // re-layout (rotation, IME show/hide, etc.) without us
                    // having to recompose this composable.
                    contentViewportBounds = {
                        val coords = listRootCoords
                        if (coords != null && coords.isAttached) {
                            val origin = coords.positionInWindow()
                            androidx.compose.ui.geometry.Rect(
                                left = origin.x,
                                top = origin.y,
                                right = origin.x + coords.size.width,
                                bottom = origin.y + coords.size.height,
                            )
                        } else null
                    },
                    actions = SelectionToolbarActions(
                        // Resolve the parent message's joined markdown via
                        // the bounds registry — only when the selection sits
                        // within a single message (cross-message selections
                        // return null and the markdown / rich-text buttons
                        // are hidden).
                        resolveSelectionMarkdown = {
                            // Use the controller's own cached
                            // message-markdown — survives both endpoint
                            // shards scrolling off-screen, unlike the rect-
                            // based MessageBoundsRegistry lookup whose
                            // entries are removed on shard dispose.
                            selectionController.selectionMessageMarkdown()
                        },
                        onAddToInput = { snippet ->
                            viewModel.appendToInputText(snippet)
                            try { inputFocusRequester.requestFocus() } catch (_: IllegalStateException) {}
                            keyboardController?.show()
                        },
                        // [P3.3 裁军] 选区“朗读”动作随语音全家退役。
                        onShare = { snippet ->
                            onShareSnippet(snippet)
                            onShowMoveSheet()
                        },
                    ),
                )
                // iOS-style selection handle dots, one at each endpoint.
                MinisSelectionHandlesHost(
                    controller = selectionController,
                    listState = listState,
                    reverseLayout = CHAT_TRANSCRIPT_REVERSE_LAYOUT,
                )
                } // Box (selection scope)
                } // CompositionLocalProvider
}

/** 冷开诊断三件套（[T-android-jank-diag-logging]）：挂在 MutableState 上的
 * 一次性冷启动摘要字段，扁平化 effect 与转录区共享。 */
internal class ColdOpenDiag {
    var lastPrewarmMs by mutableLongStateOf(-1L)
    var summaryEmitted by mutableStateOf(false)
    val mountAtMs = System.currentTimeMillis()
}

/**
 * 转录扁平化管线（T94/T-streaming-side-channel/T-android-stream-pipeline-incremental）：
 * transcriptMessages → FlatChatItem 行表，冻结前缀按引用复用、活动后缀按 token
 * 增量重建，Dispatchers.Default 上算完发回快照状态。返回的 [MutableState]
 * 直接委托给调用处的 `var flatItems by …`。
 */
@Composable
internal fun rememberTranscriptRows(
    viewModel: ChatViewModel,
    sessionId: String,
    transcriptMessages: List<ChatMessage>,
    messages: List<ChatMessage>,
    showAssistantIdentity: Boolean,
    hasOlderMessages: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollToLatestOnce: suspend (TranscriptViewportMove) -> Unit,
    tracedScrollToItem: suspend (source: String, idx: Int, off: Int) -> Unit,
    transcriptFollowState: TranscriptFollowState,
    onFollowStateChange: (TranscriptFollowState) -> Unit,
    lastUserDragAtMs: Long,
    onUserDrag: (Long) -> Unit,
    submittedTurnNavigation: SubmittedTurnNavigation,
    onSubmittedNavigation: (SubmittedTurnNavigation) -> Unit,
    coldOpenDiag: ColdOpenDiag,
    onViewportReady: () -> Unit,
): androidx.compose.runtime.MutableState<List<FlatChatItem>> {
    // T94: 帧间缓存——LazyColumn 渲染上一帧的行表，下一帧结果算完才换。
    val flatItemsState = remember(sessionId, showAssistantIdentity) {
        mutableStateOf(
            viewModel.retainedTranscriptRows.takeIf {
                viewModel.retainedTranscriptSessionId == sessionId
            }.orEmpty()
        )
    }
    var flatItems by flatItemsState
                val prewarmMarkdown = rememberMarkdownPrewarmer()
                LaunchedEffect(transcriptMessages, sessionId, showAssistantIdentity) {
                    // [T-android-stream-pipeline-incremental] Frozen/live split.
                    //
                    // `messages` is CONSTANT within this effect (the effect is
                    // keyed on it and the streaming turn writes high-frequency
                    // fields into the streamingById side-channel, never the
                    // canonical list). So the rows for every message BEFORE the
                    // first streamed one (= the frozen prefix) can be computed
                    // ONCE per effect lifetime and reused by reference on every
                    // tick. Per tick we only rebuild the live suffix (usually a
                    // single message). Pre-split, every 80ms tick re-flattened
                    // ALL messages (1146 rows on the ANR-loop session), re-ran
                    // splitMarkdownIntoBlockTexts over every frozen message,
                    // and allocated the whole row set fresh — the 130–180MB/s
                    // GC storm and the 100s builds in minis-2026-06-10.log.
                    //
                    // Row-for-row equivalence with the old full build holds by
                    // construction: buildFlatChatItems' neighbor lookbacks
                    // (precededByUser / isResumeContinuation) only ever read
                    // EARLIER messages, the live suffix is built against the
                    // full merged list with fromIndex (lookbacks cross the
                    // boundary), and dedupe continuity is preserved via
                    // seedKeys. Frozen rows are the same instances every tick,
                    // so LazyColumn's key+equals skip path sees ZERO change.
                    //
                    // Throttle (unchanged): conflate() + sample(80) keeps UI
                    // publication at ~12fps regardless of token rate.
                    var frozenRows: List<FlatChatItem> = emptyList()
                    var frozenKeys: Set<String> = emptySet()
                    var frozenSplitIdx = -1
                    var streamWasActive = false
                    // [T-android-stream-pipeline-incremental] Flush the perf
                    // turn when this effect is CANCELLED mid-turn: the
                    // turn-end drain emits `_messages` FIRST (restarting this
                    // messages-keyed effect) and clears the side-channel
                    // after, so the cancelled collector never sees the
                    // empty-stream tick that would fire turnEnd — without the
                    // finally, same-session turns accumulate forever and no
                    // [StreamPerf] summary is ever emitted.
                    try {
                    kotlinx.coroutines.flow.combine(
                        kotlinx.coroutines.flow.flowOf(transcriptMessages),
                        viewModel.streamingById,
                    ) { msgs, stream -> msgs to stream }
                        .conflate()
                        .sample(80L)
                        .collect { (msgs, stream) ->
                            val tickStartNs = System.nanoTime()
                            if (stream.isNotEmpty() && !streamWasActive) {
                                streamWasActive = true
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnStart(sessionId)
                            }
                            // First message carrying a live overlay; everything
                            // before it is frozen. Empty stream → whole list is
                            // frozen (covers cold open and post-drain ticks).
                            val splitIdx = if (stream.isEmpty()) {
                                msgs.size
                            } else {
                                val i = msgs.indexOfFirst { stream.containsKey(it.id) }
                                if (i < 0) msgs.size else i
                            }
                            val frozenReused = splitIdx == frozenSplitIdx
                            if (!frozenReused) {
                                val tBuildStart = System.nanoTime()
                                val wasEmptyPre = flatItems.isEmpty()
                                // [T-android-perf-logging] Mark the start of a
                                // full (first / non-streaming) build so the
                                // gap to buildFlatChatItems.firstBuild bounds
                                // the construction cost in isolation.
                                if (wasEmptyPre && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        "buildFlatChatItems.start",
                                        "msgCount=${msgs.size}",
                                    )
                                }
                                val rows = withContext(Dispatchers.Default) {
                                    // [T-android-flatitems-sublist-cme] Pass a
                                    // SNAPSHOT COPY, not msgs.subList(...). A
                                    // subList is a live VIEW backed by msgs and
                                    // shares its modCount; building off-main
                                    // (Dispatchers.Default) while msgs is
                                    // concurrently replaced — and the nested
                                    // messages.subList(idx+1, …).all{} inside
                                    // buildFlatChatItems iterating that view —
                                    // threw ConcurrentModificationException from
                                    // a later frame's SubList.equals. Copying
                                    // severs the view so it can't comodify.
                                    buildFlatChatItems(
                                        messages = msgs.take(splitIdx),
                                        sessionId = sessionId,
                                        showAssistantIdentity = showAssistantIdentity,
                                    )
                                }
                                val buildMs = (System.nanoTime() - tBuildStart) / 1_000_000
                                frozenRows = rows
                                frozenKeys = rows.mapTo(HashSet()) { it.key }
                                frozenSplitIdx = splitIdx
                                // [T-android-coldload-offmain-parse] Parallel
                                // viewport prewarm: block-parse + inline-warm
                                // the newest (viewport-candidate) markdown
                                // fragments off-main so the first frame's rows
                                // compose as cache HITs. Deliberately launched
                                // in PARALLEL with the flatItems publish, not
                                // before it — blocking the publish would add
                                // the parse latency to time-to-first-frame,
                                // the exact thing this task removes; rows the
                                // prewarm hasn't reached yet just take the
                                // placeholder-then-swap path in
                                // MarkdownBlockBody. Cold/full builds only
                                // (stream empty) — live ticks never get here.
                                if (stream.isEmpty() && rows.isNotEmpty()) {
                                    val prewarmRowLimit = 16
                                    val prewarmCharBudget = 96_000
                                    val raws = mutableListOf<String>()
                                    var charSum = 0
                                    for (item in rows.asReversed()) {
                                        if (raws.size >= prewarmRowLimit || charSum >= prewarmCharBudget) break
                                        val raw = (item as? FlatChatItem.AssistantMarkdownBlock)?.rawText ?: continue
                                        raws.add(raw)
                                        charSum += raw.length
                                    }
                                    if (raws.isNotEmpty()) {
                                        launch(Dispatchers.Default) {
                                            val tPrewarmNs = System.nanoTime()
                                            prewarmMarkdown(raws)
                                            val prewarmMs = (System.nanoTime() - tPrewarmNs) / 1_000_000
                                            com.openminis.app.diagnostics.PerfLongCtx.step(
                                                sessionId,
                                                "coldPrewarm.done",
                                                "rows=${raws.size} chars=$charSum prewarmMs=$prewarmMs",
                                            )
                                        }
                                    }
                                }
                                // Only emit on the first non-streaming build per
                                // session (cheap reentry-path marker) or whenever
                                // build takes >50 ms (i.e. real work).
                                if ((wasEmptyPre || buildMs >= 50) && stream.isEmpty()) {
                                    com.openminis.app.diagnostics.PerfLongCtx.step(
                                        sessionId,
                                        if (wasEmptyPre) "buildFlatChatItems.firstBuild"
                                        else "buildFlatChatItems.slow",
                                        "msgCount=${msgs.size} rowCount=${rows.size} buildMs=$buildMs",
                                    )
                                    // [T-android-perf-logging] Low-memory risk
                                    // flag: a very high row count is the single
                                    // biggest contributor to cold-open GC
                                    // pressure.
                                    if (rows.size > 3000) {
                                        com.openminis.app.diagnostics.PerfLongCtx.step(
                                            sessionId,
                                            "buildFlatChatItems.highRowCount",
                                            "rowCount=${rows.size} threshold=3000 msgCount=${msgs.size}",
                                        )
                                    }
                                }
                            }
                            // Live suffix: only the streamed message(s). Built
                            // against the merged FULL list so neighbor lookbacks
                            // across the frozen/live boundary stay correct.
                            // sessionId = null keeps the hot path log-free.
                            val liveRows = if (splitIdx >= msgs.size) {
                                emptyList()
                            } else {
                                withContext(Dispatchers.Default) {
                                    val merged = mergeStreamingOverlay(msgs, stream)
                                    buildFlatChatItems(
                                        messages = merged,
                                        sessionId = null,
                                        fromIndex = splitIdx,
                                        seedKeys = frozenKeys,
                                        showAssistantIdentity = showAssistantIdentity,
                                    )
                                }
                            }
                            flatItems = foldNovexExecutionProcesses(if (liveRows.isEmpty()) frozenRows else frozenRows + liveRows)
                            viewModel.retainedTranscriptRows = flatItems
                            viewModel.retainedTranscriptSessionId = sessionId
                            com.openminis.app.diagnostics.StreamPerfMonitor.tick(
                                flattenNanos = System.nanoTime() - tickStartNs,
                                frozenReused = frozenReused,
                                frozenRows = frozenRows.size,
                                liveRows = liveRows.size,
                            )
                            if (stream.isEmpty() && streamWasActive) {
                                streamWasActive = false
                                com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                            }
                        }
                    } finally {
                        // Effect cancelled (turn-end drain emit / session
                        // switch / screen dispose) — flush the open turn.
                        if (streamWasActive) {
                            com.openminis.app.diagnostics.StreamPerfMonitor.turnEnd()
                        }
                    }
                }
                LaunchedEffect(flatItems.isNotEmpty(), sessionId) {
                    if (flatItems.isNotEmpty() && viewModel.consumeInitialTranscriptPositioning()) {
                        scrollToLatestOnce(TranscriptViewportMove.SessionOpened)
                    }
                    if (flatItems.isNotEmpty()) onViewportReady()
                }
                LaunchedEffect(
                    flatItems,
                    hasOlderMessages,
                    submittedTurnNavigation.pendingMessageId,
                ) {
                    val rowKeys = buildList {
                        if (hasOlderMessages) add("__load_older_messages__")
                        addAll(transcriptRowsForLayout(flatItems).map { it.key })
                    }
                    val resolution = submittedTurnNavigation.resolve(rowKeys)
                    val targetIndex = resolution.targetIndex ?: return@LaunchedEffect
                    // Consume before moving so stream-start / stream-end
                    // recompositions cannot repeat this navigation.
                    onSubmittedNavigation(resolution.nextState)
                    val dragBeforeFrame = lastUserDragAtMs
                    withFrameNanos { }
                    if (lastUserDragAtMs != dragBeforeFrame) return@LaunchedEffect
                    tracedScrollToItem(
                        TranscriptViewportMove.UserSentMessage.name,
                        targetIndex,
                        Int.MAX_VALUE / 4,
                    )
                }
                LaunchedEffect(listState, transcriptFollowState.isFollowingLatest) {
                    if (!transcriptFollowState.isFollowingLatest) return@LaunchedEffect
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val latest = latestTranscriptItemIndex(info.totalItemsCount)
                        val latestItem = info.visibleItemsInfo
                            .firstOrNull { it.index == latest }
                        // A fixed-height final control row can move when the body above grows.
                        listOf(info.totalItemsCount, latestItem?.size ?: -1,
                            latestItem?.offset ?: -1, info.viewportEndOffset)
                    }
                        .distinctUntilChanged()
                        .collect {
                            scrollToLatestOnce(TranscriptViewportMove.PassiveStreamGrowth)
                        }
                }
    return flatItemsState
}
