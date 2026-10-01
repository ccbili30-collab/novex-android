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
 * 输入栏状态袋：原本散在 ChatScreen 函数体的 var 快照状态。主函数与
 * [ChatComposerSection] 共享同一批 MutableState（`var x by env.x` 委托），
 * 语义与原先的 remember 等价（均非会话键控，重启不保留）。
 */
internal class ChatComposerEnv {
    val sendSwipeLocation = mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    val sendSwipeProgress = mutableStateOf(0f)
    val inputFieldValue = mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(""))
    val inputFocused = mutableStateOf(false)
    val previewImageGallery =
        mutableStateOf<Pair<List<com.openminis.app.ui.components.ImageGalleryItem>, Int>?>(null)
    val showAttachMenu = mutableStateOf(false)
    val showComposerExpanded = mutableStateOf(false)
    val showContextMeter = mutableStateOf(false)
    val showMoveSheet = mutableStateOf(false)
    val lastSendTimeMs = mutableStateOf(0L)
}

/**
 * 输入栏区：@/斜杠弹出菜单、附件条、ContextMeter、发送键与上滑手势、
 * Move-to 胶囊角标。全部交互状态经 [env] 与宿主共享。
 */
@Composable
internal fun ChatComposerSection(
    viewModel: ChatViewModel,
    env: ChatComposerEnv,
    attachments: List<InputAttachment>,
    inputText: String,
    isStreaming: Boolean,
    inputFocusRequester: androidx.compose.ui.focus.FocusRequester,
    composerInputSynchronizer: ComposerInputSynchronizer,
    chatInputFontScale: Float,
    novexControls: List<novex.core.ConversationControlDefinition>,
    sideHandoffState: ChatViewModel.SideHandoffState,
    sideParentId: String?,
    contextCapacity: Pair<Int?, Int?>,
    contextEstimated: Boolean,
    contextUsageReady: Boolean,
    lastTurnContextTokens: Int,
    contextMeterMode: Int,
    onContextMeterModeChange: (Int) -> Unit,
    performSendOrEnqueue: (String) -> Unit,
    startHandoff: () -> Unit,
    launchCamera: () -> Unit,
    mediaPickerLauncher: androidx.activity.compose.ManagedActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest, List<android.net.Uri>>,
    cameraPermissionLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>,
    filePickerLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, List<android.net.Uri>>,
    onPreviewAttachment: (com.openminis.app.ui.sandbox.FileItem) -> Unit,
) {
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var sendSwipeLocation by env.sendSwipeLocation
    var sendSwipeProgress by env.sendSwipeProgress
    var inputFieldValue by env.inputFieldValue
    var inputFocused by env.inputFocused
    var previewImageGallery by env.previewImageGallery
    var showAttachMenu by env.showAttachMenu
    var showComposerExpanded by env.showComposerExpanded
    var showContextMeter by env.showContextMeter
    var showMoveSheet by env.showMoveSheet
    var lastSendTimeMs by env.lastSendTimeMs
    val swipeArmFraction = 0.8f
    val swipeThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
    val swipeHapticOffsetPx = with(LocalDensity.current) { 60.dp.toPx() }
    val swipeArrowHalfPx = with(LocalDensity.current) { 17.dp.toPx() }
    val swipeHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(top = 2.dp, bottom = 8.dp),
            ) {
                // T13 banner moved INSIDE the LazyColumn so it renders at the
                // visual end of the message list (mirrors iOS — see the
                // banner item before items() in the LazyColumn block above).

                // Slash-command menu (mirrors iOS slashCommandMenu) — rendered as
                // a Popup so it overlays content (tool status bar, chat list)
                // instead of pushing them up. Anchored above the composer via
                // PopupProperties so its bottom edge sits just above this Column.
                // Tap-outside dismisses via dismissOnClickOutside.
                val showSlashMenu by viewModel.showSlashMenu.collectAsState()
                // [A2c-cards] 卡盘=银卡（AI 自注册的本会话能力）+ 唯一
                // 保留的系统金卡「压缩卡」。其余系统指令卡已全部移除
                // （含存档组：将来由引擎按需注册为银卡）。
                val filteredSlashCommands = remember(
                    showSlashMenu,
                    viewModel.slashFilter.collectAsState().value,
                ) { viewModel.filteredSlashCommands() }
                if (showSlashMenu) {
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    // Anchor: top-edge of the composer column. Place
                                    // the popup so its bottom sits 12dp above that edge.
                                    // T301: bumped from 6dp — at 6dp the panel was
                                    // visually glued to the composer; 12dp gives a
                                    // clear breathing gap matching the iOS spacing.
                                    val gap = 12
                                    val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top - popupContentSize.height - gap)
                                        .coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = {
                            viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                        },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            // dismissOnClickOutside=false: with focusable=false the popup
                            // never receives focus, so the system "click outside" detector
                            // can't tell a tap on the BasicTextField below from a tap on
                            // the chat list — flipping this off would dismiss the popup
                            // every time the IME caret was moved. Dismiss is driven from
                            // the chat list / topbar tap-spy below instead, which lets
                            // the input field keep focus while still closing the menu
                            // when the user clearly looks elsewhere.
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                        // Locked popup height = 4 rows × 46dp + 8dp = 192dp.
                        // Short lists show empty space below the last row;
                        // long lists scroll inside the same frame with a
                        // visible scroll indicator. Prevents installed
                        // Skills + built-ins from pushing the menu past
                        // the input bar / off the top of the screen.
                        val slashListState = androidx.compose.foundation.lazy.rememberLazyListState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                // T240: keep a thin visible border instead of the
                                // diffuse 8dp halo that bled out past the panel edge.
                                .shadow(elevation = 3.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            // [A2c-cards] 高度随卡数：行高 ~52dp，上限封顶
                            // SLASH_PICKER_FIXED_HEIGHT，超出内部滚动。
                            // 不再固定 4 行高撑满。
                            val trayRows = (novexControls.size + filteredSlashCommands.size)
                                .coerceAtLeast(1)
                            val trayHeight = (trayRows * 52 + 14).dp
                                .coerceAtMost(SLASH_PICKER_FIXED_HEIGHT)
                            androidx.compose.foundation.lazy.LazyColumn(
                                state = slashListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(trayHeight)
                                    .verticalScrollbar(slashListState),
                            ) {
                            // [A2c-cards] 卡盘两组：银卡在上——文游快捷动作
                            // （原 ^ 按钮收编，命名由卡片/AI 自己提供）；
                            // 发丝分割后金卡——原斜杠指令以「指令卡」呈现，
                            // 不再暴露 "/" 语法。圆角矩阵行，不拟物。
                            items(
                                count = novexControls.size,
                                key = { i -> "novexctl:$i" },
                            ) { i ->
                                val control = novexControls[i]
                                // [A2c-cards] 银卡行=普通行 + 行首中空银框小卡标。
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(9.dp))
                                        .novexClickable {
                                            viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                                            viewModel.runNovexControl(control)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NovexMiniCardGlyph(color = ChatColors.secondaryText.copy(alpha = 0.55f))
                                    Spacer(modifier = Modifier.width(11.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = control.label,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ChatColors.primaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = if (control.behavior == ConversationControlBehavior.VIEW) "查看" else "动作",
                                            fontSize = 11.sp,
                                            color = ChatColors.secondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (novexControls.isNotEmpty() && filteredSlashCommands.isNotEmpty()) {
                                item(key = "__card_group_divider__") {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = ChatColors.toolBorder,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            // [A2c-cards] 金卡区：目前只剩「压缩卡」。
                            itemsIndexed(filteredSlashCommands, key = { _, c -> "cmd:${c.id}" }) { _, cmd ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(9.dp))
                                        .novexClickable {
                                            // [T-android-slash-menu-clears-input] Pass the
                                            // LIVE input so an action command keeps the
                                            // user's body text instead of wiping it.
                                            viewModel.setInputText(viewModel.executeSlashCommand(cmd, inputText))
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NovexMiniCardGlyph(color = Color(0xFFC9A24B))
                                    Spacer(modifier = Modifier.width(11.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "压缩卡",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ChatColors.primaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "把对话历史压缩成摘要，腾出上下文",
                                            fontSize = 11.sp,
                                            color = ChatColors.secondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            // [A2c-cards] 两组皆空时给一行说明，按钮不至于
                            // 点了没反应。
                            if (novexControls.isEmpty() && filteredSlashCommands.isEmpty()) {
                                item(key = "__card_empty__") {
                                    Text(
                                        text = "本会话暂无可用的指令卡",
                                        fontSize = 12.sp,
                                        color = ChatColors.secondaryText,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                    )
                                }
                            }
                            }
                        }
                    }
                }

                // T4: @ file-mention picker — same anchoring + tap-spy
                // contract as the slash popup (mutually exclusive in the VM,
                // so they never both render). Reuses Popup so the bar over
                // the composer is consistent and respects IME inset.
                val showMentionMenu by viewModel.showMentionMenu.collectAsState()
                val mentionEntries by viewModel.mentionEntries.collectAsState()
                val isMentionScanning by viewModel.isMentionScanning.collectAsState()
                val mentionSelectedIndex by viewModel.mentionSelectedIndex.collectAsState()
                if (showMentionMenu) {
                    androidx.compose.ui.window.Popup(
                        popupPositionProvider = remember {
                            object : androidx.compose.ui.window.PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: androidx.compose.ui.unit.IntRect,
                                    windowSize: androidx.compose.ui.unit.IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: androidx.compose.ui.unit.IntSize,
                                ): androidx.compose.ui.unit.IntOffset {
                                    val gap = 6
                                    val x = ((anchorBounds.left + anchorBounds.right - popupContentSize.width) / 2)
                                        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                    val y = (anchorBounds.top - popupContentSize.height - gap)
                                        .coerceAtLeast(0)
                                    return androidx.compose.ui.unit.IntOffset(x, y)
                                }
                            }
                        },
                        onDismissRequest = { viewModel.dismissMentionMenu() },
                        properties = androidx.compose.ui.window.PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            // Same rationale as the slash popup: dismissOnClickOutside=false
                            // because the input field below the popup sits in the
                            // "outside" region (focusable=false → caret moves still
                            // count as outside). The chat-list tap-spy that drives
                            // dismissSlashMenu also dismisses this menu via
                            // dismissMentionMenu(); see the LazyColumn pointerInput.
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .shadow(elevation = 8.dp, shape = RoundedCornerShape(10.dp))
                                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp)),
                        ) {
                            if (mentionEntries.isEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isMentionScanning) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 1.5.dp,
                                            color = ChatColors.secondaryText,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = stringResource(
                                            if (isMentionScanning) R.string.mention_scanning
                                            else R.string.mention_no_match
                                        ),
                                        fontSize = 13.sp,
                                        color = ChatColors.secondaryText,
                                    )
                                }
                            } else {
                                // [T-slash-picker-fixed-height port from iOS 73f1b94a]
                                // Mention picker shares the slash picker's
                                // locked 192dp height (4 rows × 46dp + 8dp)
                                // so both popups have the same band on screen.
                                val mentionListState = androidx.compose.foundation.lazy.rememberLazyListState()
                                // Keep the highlighted row visible when the user
                                // navigates with a hardware keyboard. iOS gets this
                                // for free from SwiftUI's List/scrollTo binding;
                                // mimic it explicitly here.
                                LaunchedEffect(mentionSelectedIndex, mentionEntries.size) {
                                    val idx = mentionSelectedIndex
                                    if (idx in mentionEntries.indices) {
                                        mentionListState.animateScrollToItem(idx)
                                    }
                                }
                                LazyColumn(
                                    state = mentionListState,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(SLASH_PICKER_FIXED_HEIGHT)
                                        .verticalScrollbar(mentionListState),
                                ) {
                                    itemsIndexed(mentionEntries, key = { _, e -> e.linuxPath }) { i, entry ->
                                        val isSelected = i == mentionSelectedIndex
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (isSelected) {
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                    } else {
                                                        Color.Transparent
                                                    },
                                                )
                                                .clickable {
                                                    val (newText, newCaret) = viewModel.selectMention(
                                                        entry,
                                                        currentText = inputFieldValue.text,
                                                        currentCaret = inputFieldValue.selection.end,
                                                    )
                                                    viewModel.setInputText(newText)
                                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                        text = newText,
                                                        selection = androidx.compose.ui.text.TextRange(newCaret),
                                                    )
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Single doc icon for every entry — the scope/mount
                                            // capsule on the right already labels what bucket
                                            // this is (workspace / skills / shared / memory /
                                            // <mountName>). iOS varies the icon per scope but
                                            // we keep it uniform here so the row stays
                                            // visually consistent at small sizes on Pixel 4a.
                                            Icon(
                                                imageVector = novex.android.ui.NovexIcons.Description,
                                                contentDescription = null,
                                                tint = ChatColors.secondaryText,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = entry.basename,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = ChatColors.primaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = entry.displayPath,
                                                    fontSize = 11.sp,
                                                    color = ChatColors.secondaryText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            // Scope / mount badge — matches iOS capsule.
                                            Text(
                                                text = entry.mountName ?: entry.scope.displayLabel,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = ChatColors.secondaryText,
                                                modifier = Modifier
                                                    .background(
                                                        ChatColors.toolCapsuleBg,
                                                        RoundedCornerShape(8.dp),
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // [T-side-handoff-strip] 回传状态条（决策 ①）：侧边页输入栏上方，
                // 忙碌/成功/失败全程可见；状态源在 ViewModel，退出页面不丢。
                if (sideParentId != null && sideHandoffState !is ChatViewModel.SideHandoffState.Idle) {
                    NovexSideHandoffStatusStrip(
                        state = sideHandoffState,
                        onRetry = { sideParentId?.let(viewModel::retrySideHandoff) },
                        onDismiss = viewModel::dismissSideHandoff,
                    )
                }

                // [T-execution-activity-strip] 输入栏上方任务条已撤（2026-09-16
                // 用户：冗余——转录内已有实时过程行，同屏播两遍）。过程动态见
                // 转录区的回合工作行；完整记录点行内「查看记录」。

                // Input box: iOS-style floating card — no visible border, separated
                // from the backdrop by a symmetric soft shadow painted by hand
                // (Android's Modifier.shadow only casts downward).
                val inputBgArgb = ChatColors.inputBg.toArgb()
                val shadowPaint = remember(inputBgArgb) {
                    android.graphics.Paint().apply {
                        color = inputBgArgb
                        isAntiAlias = true
                    }
                }
                // T185: Move-to-session capsule mirrors iOS
                // AIChatView.swift:1816 (.overlay(alignment: .topTrailing))
                // on the input card. We render it as the first child of the
                // composer Column, right-aligned, so it visually sits inside
                // the input card's top-right corner — Compose doesn't have a
                // free overlay primitive that doesn't need a Box wrapper,
                // and an in-flow Row at the top with Arrangement.End is the
                // cleanest equivalent.
                val showMoveCapsule by viewModel.hasInjectedShareContent.collectAsState()
                // Mirrors iOS swipe-up-to-send: drag the input bar upward —
                // if it holds text, a floating send-arrow + "Release to send"
                // capsule track the finger; releasing past `swipeArmFraction`
                // sends. With empty text + collapsed keyboard, releasing
                // activates the keyboard instead. Box wraps the existing
                // composer Column so the gesture + overlay live in the same
                // coordinate space without disturbing the bar's own layout.
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var totalDx = 0f
                            var totalDy = 0f
                            var claimed = false
                            var lastPos = down.position
                            verticalDrag(down.id) { change ->
                                val delta = change.positionChange()
                                totalDx += delta.x
                                totalDy += delta.y
                                lastPos = change.position
                                if (!claimed) {
                                    // Wait until a clearly vertical drag of
                                    // at least `slop` px before claiming.
                                    // Below that the TextField / list still
                                    // get the events (taps, text scroll, …).
                                    if (kotlin.math.abs(totalDy) < slop) return@verticalDrag
                                    if (kotlin.math.abs(totalDy) <= kotlin.math.abs(totalDx)) return@verticalDrag
                                    claimed = true
                                }
                                change.consume()
                                if (totalDy < 0) {
                                    // Swiping up. Show hint only when there
                                    // is text to send; otherwise keep the
                                    // overlay hidden and defer keyboard
                                    // activation to onEnd.
                                    val hasText = viewModel.inputText.value.isNotBlank()
                                    if (hasText) {
                                        val newProgress = (-totalDy / swipeThresholdPx).coerceIn(0f, 1f)
                                        if (newProgress >= swipeArmFraction && sendSwipeProgress < swipeArmFraction) {
                                            swipeHaptics.performHapticFeedback(
                                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                            )
                                        }
                                        sendSwipeProgress = newProgress
                                        sendSwipeLocation = lastPos
                                    } else if (sendSwipeProgress != 0f) {
                                        sendSwipeProgress = 0f
                                    }
                                } else if (sendSwipeProgress != 0f) {
                                    // Reversed direction; clear any hint.
                                    sendSwipeProgress = 0f
                                }
                            }
                            // Drag ended (finger up or pointer cancel).
                            val hasText = viewModel.inputText.value.isNotBlank()
                            val swipedUp = claimed && totalDy < 0 &&
                                kotlin.math.abs(totalDy) > kotlin.math.abs(totalDx)
                            if (swipedUp && hasText) {
                                val attachmentsCount = viewModel.attachments.value.size
                                val canSendNow = hasText || attachmentsCount > 0
                                if (sendSwipeProgress >= swipeArmFraction && canSendNow) {
                                    // T-drag-send-queue: route through the
                                    // shared send-or-enqueue handler so a
                                    // drag-to-send during streaming enqueues
                                    // the prompt instead of being dropped —
                                    // matches the send-button tap path which
                                    // already enqueues mid-stream via
                                    // viewModel.sendMessage → enqueuePrompt.
                                    performSendOrEnqueue(viewModel.inputText.value)
                                }
                                sendSwipeProgress = 0f
                            } else if (swipedUp && !hasText && !inputFocused) {
                                // Empty input + collapsed keyboard -> bring
                                // up the keyboard. If the keyboard is
                                // already open, do nothing so a stray drag
                                // doesn't re-trigger anything.
                                inputFocusRequester.requestFocus()
                                keyboardController?.show()
                                sendSwipeProgress = 0f
                            } else {
                                sendSwipeProgress = 0f
                            }
                        }
                    },
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // [feat/ui-rikkahub] card radius 20 → 24dp + 1dp hairline
                        // border (mainstream input-bar formula: big rounded container
                        // with a low-alpha outline over the soft shadow).
                        .drawBehind {
                            val radiusPx = 24.dp.toPx()
                            val canvas = drawContext.canvas.nativeCanvas
                            // Pass 1: symmetric ambient halo — small blur, low alpha.
                            shadowPaint.setShadowLayer(
                                7.dp.toPx(), 0f, 0f,
                                android.graphics.Color.argb(28, 0, 0, 0),
                            )
                            canvas.drawRoundRect(
                                0f, 0f, size.width, size.height,
                                radiusPx, radiusPx,
                                shadowPaint,
                            )
                            // Pass 2: soft downward shadow (spot light).
                            shadowPaint.setShadowLayer(
                                12.dp.toPx(), 0f, 3.dp.toPx(),
                                android.graphics.Color.argb(32, 0, 0, 0),
                            )
                            canvas.drawRoundRect(
                                0f, 0f, size.width, size.height,
                                radiusPx, radiusPx,
                                shadowPaint,
                            )
                        }
                        // [A2c-chrome] 去发丝描边——用户批注：外圈不要黑边，
                        // 只靠双层软阴影把卡片从底布上托起来。
                        .padding(top = if (attachments.isNotEmpty()) 8.dp else 4.dp),
                ) {
                    // T185: Move-to capsule lives INSIDE the composer card,
                    // pinned 8dp from the top-right corner, mirroring iOS
                    // AIChatView.swift:1816 (.overlay(alignment: .topTrailing)
                    // padding(.top, 6).padding(.trailing, 10)). A Popup
                    // keeps it out of the composer's layout flow so the
                    // attachment row + text field still own the full
                    // vertical rhythm.
                    if (showMoveCapsule) {
                        // T185: align Move-to right edge with the
                        // attachment row + button row (both 12dp). The
                        // anchorBounds rect is in px, so convert via
                        // LocalDensity rather than treating the constant
                        // as dp directly.
                        val popupDensity = androidx.compose.ui.platform.LocalDensity.current
                        val rightInsetPx = with(popupDensity) { 12.dp.roundToPx() }
                        val topInsetPx = with(popupDensity) { 6.dp.roundToPx() }
                        androidx.compose.ui.window.Popup(
                            popupPositionProvider = remember(rightInsetPx, topInsetPx) {
                                object : androidx.compose.ui.window.PopupPositionProvider {
                                    override fun calculatePosition(
                                        anchorBounds: androidx.compose.ui.unit.IntRect,
                                        windowSize: androidx.compose.ui.unit.IntSize,
                                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                        popupContentSize: androidx.compose.ui.unit.IntSize,
                                    ): androidx.compose.ui.unit.IntOffset {
                                        val x = (anchorBounds.right - popupContentSize.width - rightInsetPx)
                                            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                                        val y = (anchorBounds.top + topInsetPx).coerceAtLeast(0)
                                        return androidx.compose.ui.unit.IntOffset(x, y)
                                    }
                                }
                            },
                            onDismissRequest = {},
                            properties = androidx.compose.ui.window.PopupProperties(
                                focusable = false,
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false,
                            ),
                        ) {
                            androidx.compose.material3.Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                // Mirrors iOS .ultraThinMaterial — solid-
                                // looking pill against the input bg.
                                // Without a hairline border the capsule
                                // washed out into the input card on the
                                // light theme, which is why it stopped
                                // reading as a pill.
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = 0.dp,
                                tonalElevation = 0.dp,
                                border = androidx.compose.foundation.BorderStroke(
                                    0.5.dp,
                                    ChatColors.thumbnailBorder,
                                ),
                                modifier = Modifier.clickable { showMoveSheet = true },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                                ) {
                                    // arrow.right.circle look-alike: an
                                    // outlined ring around a → glyph.
                                    Box(
                                        modifier = Modifier
                                            .size(15.dp)
                                            .border(
                                                1.dp,
                                                ChatColors.secondaryText,
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            novex.android.ui.NovexIcons.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(10.dp),
                                            tint = ChatColors.secondaryText,
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "Move to…",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ChatColors.secondaryText,
                                    )
                                }
                            }
                        }
                    }
                    // Attachment thumbnails inside the box (iOS: 64×64 squares)
                    if (attachments.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                // T185: 12dp horizontal so the row's left
                                // edge lines up with the +/slash button
                                // column and the typed text below.
                                .padding(horizontal = 12.dp),
                            // The chip itself now bakes in 8dp of trailing
                            // visual room for the remove badge that spills
                            // past the top-right; no extra spacedBy needed.
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            items(attachments, key = { it.id }) { attachment ->
                                // [P3.3 裁军] 附件长按的 WebApp「添加到主屏」
                                // 菜单（webAppMenuExpanded，已被 TODO 停用的
                                // 死代码）随 webapp/ 整包退役删除。
                                AttachmentChip(
                                    attachment = attachment,
                                    onRemove = { viewModel.removeAttachment(attachment.id) },
                                    onClick = {
                                        // Mirror iOS InputAttachmentTile
                                        // (AIChatView.swift:3699) which
                                        // .sheet's an AttachmentPreviewView
                                        // routed by file type. Images go
                                        // through the in-app fullscreen
                                        // viewer; non-image files take the
                                        // in-app FilePreviewScreen when we
                                        // hold a host file path, falling
                                        // back to the system viewer for
                                        // foreign content:// URIs.
                                        if (attachment.isImage) {
                                            // Collect every image chip in
                                            // the composer row so the user
                                            // can swipe through them.
                                            val imageChips = attachments.filter { it.isImage }
                                            val startIdx = imageChips.indexOfFirst { it.id == attachment.id }
                                                .coerceAtLeast(0)
                                            previewImageGallery = imageChips.map { ic ->
                                                com.openminis.app.ui.components.ImageGalleryItem(
                                                    model = ic.uri,
                                                    caption = ic.fileName,
                                                )
                                            } to startIdx
                                        } else {
                                            // T162: shares funnel through
                                            // addAttachmentFromStagedShare,
                                            // which copies the bytes into
                                            // cacheDir/share_inbound/<uuid>-
                                            // <name> and returns a
                                            // Uri.fromFile() URI. Handing
                                            // that file:// URI directly to
                                            // Intent.ACTION_VIEW raises
                                            // FileUriExposedException on
                                            // API 24+ and crashed the app
                                            // on the user's first chip tap.
                                            // Route file:// chips into the
                                            // in-app FilePreviewScreen via
                                            // the host onPreviewAttachment
                                            // callback (same path the user-
                                            // bubble chip uses); leave
                                            // content:// chips on the
                                            // system viewer because we
                                            // don't have a host path for
                                            // those.
                                            val uri = attachment.uri
                                            val asFile = if (uri.scheme == "file") {
                                                uri.path?.let { java.io.File(it) }
                                            } else null
                                            if (asFile != null && asFile.exists()) {
                                                onPreviewAttachment(
                                                    com.openminis.app.ui.sandbox.FileItem(
                                                        file = asFile,
                                                        name = attachment.fileName,
                                                        isDirectory = false,
                                                        isSymlink = false,
                                                        size = asFile.length(),
                                                        modifiedMs = asFile.lastModified(),
                                                    )
                                                )
                                            } else {
                                                val intent = android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                ).apply {
                                                    setDataAndType(uri, attachment.mimeType)
                                                    addFlags(
                                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                                    )
                                                }
                                                try {
                                                    context.startActivity(intent)
                                                } catch (_: android.content.ActivityNotFoundException) {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "No app available to open this attachment.",
                                                        android.widget.Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // [P3.3 裁军] 语音会话波形/InlineVoiceInputPanel（语音
                    // 输入面板整块）随语音全家退役；输入区恒为文本框。
                    // [feat/ui-rikkahub] 胶囊单行：文本区 + 内嵌发送/回传钮；
                    // +、/、文游、麦克风、上下文计排第二行按钮区。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp),
                    ) {
                    Box(Modifier.weight(1f)) {
                    run {
                        // [T-android-enter-to-send-broken] Live read of the
                        // "Return key sends" preference. Bound here (not
                        // captured at BasicTextField construction) so a
                        // toggle in Settings reflects on the next IME
                        // commit without recomposing the chat tree.
                        val sendOnEnter = com.openminis.app.ui.settings
                            .returnKeySendsMessage(context)
                        // Shared "Enter pressed → send" body used by BOTH
                        // the hardware-keyboard onKeyEvent path AND the
                        // soft-keyboard KeyboardActions.onSend below.
                        // Pre-fix only the onKeyEvent path existed and
                        // most soft IMEs (Gboard, Sogou, MIUI) never
                        // route an Enter through onKeyEvent under
                        // ImeAction.Default — they just inserted a '\n'
                        // and the preference appeared not to work. We
                        // now flip imeAction to Send when the toggle is
                        // on, so the IME shows the send icon AND fires
                        // onSend; this lambda is the single source of
                        // truth for what "press Enter to send" means.
                        val performEnterSend: () -> Boolean = handler@{
                            if (inputText.isBlank() && attachments.isEmpty()) return@handler false
                            // Intercept slash commands so "/compact" et al.
                            // run locally instead of being sent as a chat
                            // turn. Mirrors iOS performSend().
                            if (viewModel.tryExecuteInputAsSlashCommand(inputText)) {
                                viewModel.setInputText("")
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                return@handler true
                            }
                            // T160: snapshot → clear state + IME →
                            // sendMessage. Same ordering as the send-
                            // button click; finishComposingText fires
                            // when focus drops so any IME composing
                            // buffer is committed/dropped before the
                            // empty inputText becomes visible.
                            val toSend = inputText
                            lastSendTimeMs = System.currentTimeMillis()
                            viewModel.setInputText("")
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            viewModel.sendMessage(toSend)
                            true
                        }
                        ChatComposerTextField(
                            value = inputFieldValue,
                            onValueChange = { tfv ->
                                val edit = interpretComposerTextEdit(inputFieldValue, tfv,
                                    System.currentTimeMillis() - lastSendTimeMs, sendOnEnter, showMentionMenu)
                                if (edit == ComposerTextEdit.Ignore) return@ChatComposerTextField
                                if (edit == ComposerTextEdit.Send) {
                                    performEnterSend()
                                    return@ChatComposerTextField
                                }
                                val systemSeparated = (edit as ComposerTextEdit.Replace).value
                                inputFieldValue = systemSeparated
                                if (inputText != systemSeparated.text) {
                                    composerInputSynchronizer.recordLocalEdit(systemSeparated.text)
                                    viewModel.setInputText(systemSeparated.text)
                                    viewModel.updateSlashMenuState(systemSeparated.text)
                                }
                                // Drive the @ mention picker on every keystroke
                                // and selection change — caret position alone
                                // can flip the active token's filter (e.g. user
                                // moves cursor without typing). VM filters out
                                // the slash-menu-priority case and any
                                // non-mention caret state.
                                viewModel.updateMentionMenuState(
                                    text = systemSeparated.text,
                                    caret = systemSeparated.selection.end,
                                )
                            },
                            focusRequester = inputFocusRequester,
                            onFocusChanged = { inputFocused = it },
                            onKeyEvent = onKeyEvent@{ event ->
                                    // T-at-filepicker-keyboard: while the @-mention
                                    // menu is open, hardware Up/Down navigates the
                                    // list and Return commits the highlighted entry.
                                    // Falls through to the normal Return-send path
                                    // when there are no mention candidates so the
                                    // user isn't stuck if the menu is empty.
                                    if (showMentionMenu && event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionUp -> {
                                                viewModel.mentionMenuUp()
                                                return@onKeyEvent true
                                            }
                                            Key.DirectionDown -> {
                                                viewModel.mentionMenuDown()
                                                return@onKeyEvent true
                                            }
                                            Key.Enter -> {
                                                val result = viewModel.executeSelectedMention(
                                                    currentText = inputFieldValue.text,
                                                    currentCaret = inputFieldValue.selection.end,
                                                )
                                                if (result != null) {
                                                    val (newText, newCaret) = result
                                                    viewModel.setInputText(newText)
                                                    inputFieldValue = androidx.compose.ui.text.input.TextFieldValue(
                                                        text = newText,
                                                        selection = androidx.compose.ui.text.TextRange(newCaret),
                                                    )
                                                    return@onKeyEvent true
                                                }
                                                // Menu open but no candidates → fall
                                                // through to Return-send / newline.
                                            }
                                            Key.Escape -> {
                                                viewModel.dismissMentionMenu()
                                                return@onKeyEvent true
                                            }
                                            else -> Unit
                                        }
                                    }
                                    // Return-key behavior is user-configurable
                                    // (Appearance → Return Key, default Newline =
                                    // iOS shipping default). Shift+Enter always
                                    // inserts a newline regardless of the setting,
                                    // mirroring iOS hardware-keyboard semantics.
                                    // [T-android-enter-to-send-broken] Hardware-
                                    // keyboard path. Soft IME route goes through
                                    // KeyboardActions.onSend below.
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.Enter &&
                                        !event.isShiftPressed &&
                                        sendOnEnter
                                    ) {
                                        performEnterSend()
                                    } else false
                                },
                            fontScale = chatInputFontScale,
                            sendOnEnter = sendOnEnter,
                            onSend = { performEnterSend() },
                        )
                    }
                    }

                    // 胶囊右端内嵌动作：侧边页回传钮 + 发送/停止钮（自按钮行上移）。
                    // 侧边页回传（决策 16/17；状态机 2026-09-15 下沉 ViewModel）：
                    // 符号入口——让侧边模型产出增量交接简报并并入主线；
                    // 全程反馈见输入栏上方的回传状态条。
                    val handoffRunning = sideHandoffState is ChatViewModel.SideHandoffState.Running
                    if (sideParentId != null) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    if (handoffRunning) ChatColors.sendButtonDisabled else com.openminis.app.ui.noven.NovenColors.Mint,
                                    CircleShape,
                                )
                                .clip(CircleShape)
                                .clickable(enabled = !handoffRunning && !isStreaming) { startHandoff() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                novex.android.ui.NovexIcons.KeyboardReturn,
                                contentDescription = "回传主对话",
                                tint = if (handoffRunning) ChatColors.primaryText.copy(alpha = 0.5f) else com.openminis.app.ui.noven.NovenColors.OnMint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Right: 3-state Send / Enqueue / Stop button (mirrors iOS sendButton).
                    //   • streaming + hasText  → SEND (routes through viewModel.sendMessage,
                    //     which dispatches to enqueuePrompt since _isStreaming is true).
                    //     Visual feedback for the queued prompt comes from the dashed
                    //     bubble that ChatViewModel.enqueuePrompt appends to the message
                    //     list — no extra button badge needed (matches iOS).
                    //   • streaming + !hasText → STOP (cancel current run).
                    //   • !streaming           → SEND (full color when hasText, dimmed
                    //     when empty; same as before).
                    // T180: an attachments-only send (no caption) is a
                    // valid message — mirrors iOS where !attachments.isEmpty
                    // satisfies the composer's send guard. Without this an
                    // image-only "look at this" send is impossible.
                    val hasText = inputText.isNotBlank()
                    val hasContent = hasText || attachments.isNotEmpty()
                    val showStop = isStreaming && !hasContent
                    if (showStop) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(0xFFFF3B30), CircleShape)
                                .clip(CircleShape)
                                .clickable { viewModel.cancelStream() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                novex.android.ui.NovexIcons.Stop,
                                contentDescription = "Stop",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    } else {
                        // Streaming with content → Send-into-queue; Idle with content → Send.
                        // Idle without text or attachments → disabled.
                        val canActivate = hasContent
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                // [feat/ui-rikkahub] 发送是输入栏唯一主
                                // 动作——激活态用品牌薄荷绿，停用仍灰。
                                .background(
                                    if (canActivate) com.openminis.app.ui.noven.NovenColors.Mint
                                    else ChatColors.sendButtonDisabled,
                                    CircleShape,
                                )
                                .clip(CircleShape)
                                // [T-longpress-stop] 2026-09-16 用户批④：生成中
                                // 长按发送键=立即打断（不用去够停止键）；平时行为不变。
                                .combinedClickable(
                                    enabled = canActivate || isStreaming,
                                    onLongClick = {
                                        if (isStreaming) {
                                            viewModel.cancelStream()
                                        }
                                    },
                                    // T-drag-send-queue: 点击走共享 send-or-enqueue
                                    // 处理器（斜杠短路、快照文本、清输入，发送中入队）。
                                    onClick = { performSendOrEnqueue(inputText) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                novex.android.ui.NovexIcons.Send,
                                contentDescription = "Send",
                                tint = if (canActivate) com.openminis.app.ui.noven.NovenColors.OnMint
                                else ChatColors.primaryText.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    } // end capsule row (text field + embedded actions)

                    // Button row below text field (iOS layout: + / ... mic send)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // [A2c-chrome] 外围留白放宽到 20dp——裸符号行
                            // 需要更松的呼吸位，按钮不再贴着卡缘。
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left: + 附件入口（裸符号，不再套灰圆底）
                        Box {
                            ComposerGlyphButton(
                                onClick = { showAttachMenu = true },
                            ) {
                                Icon(
                                    novex.android.ui.NovexIcons.Add,
                                    contentDescription = "Attach",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                            MinisMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false },
                            ) {
                                // iOS parity: Take Photo / Choose Photos & Videos / Add File
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_take_photo)) },
                                    leadingIcon = { Icon(novex.android.ui.NovexIcons.CameraAlt, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            android.Manifest.permission.CAMERA,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            launchCamera()
                                        } else {
                                            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_choose_photos_videos)) },
                                    leadingIcon = { Icon(novex.android.ui.NovexIcons.PhotoLibrary, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        mediaPickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                                            ),
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_attach_add_file)) },
                                    leadingIcon = { Icon(novex.android.ui.NovexIcons.Description, contentDescription = null) },
                                    onClick = {
                                        showAttachMenu = false
                                        // OpenMultipleDocuments takes a mime-
                                        // type array; "*/*" stays the wildcard.
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        // [A2c-cards] 叠卡入口：打开「指令卡」托盘——银卡（文游
                        // 快捷动作，原 ^ 按钮收编）在上、金卡（原斜杠指令）在下；
                        // 不往输入框注入 "/"，键入 "/" 的过滤路径不受影响。
                        ComposerGlyphButton(
                            onClick = {
                                if (viewModel.showSlashMenu.value) {
                                    viewModel.setInputText(viewModel.dismissSlashMenu(inputText))
                                } else {
                                    viewModel.openInstructionCards()
                                }
                            },
                        ) {
                            // 竖向交叠的三卡图形，不是横向 layers 符号。
                            // 托盘展开时图形符号本身变薄荷——打开态提示在
                            // glyph 上，不加底色。
                            NovexCardStackGlyph(
                                fillColor = ChatColors.inputBg,
                                tint = if (showSlashMenu) com.openminis.app.ui.noven.NovenColors.Mint
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { contentDescription = "指令卡" },
                            )
                        }
                        // T187: Exit Edit Mode pill, only while editingMessageId
                        // is non-null. Tap clears the edit flag + composer text
                        // without truncating history. iOS parity:
                        // AIChatView.swift L1586 editExitButton.
                        val editingId by viewModel.editingMessageId.collectAsState()
                        if (editingId != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = ChatColors.inputBg,
                                modifier = Modifier.clickable {
                                    viewModel.cancelEdit()
                                    viewModel.setInputText("")
                                },
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_edit_exit_button),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ChatColors.secondaryText,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // [P3.3 裁军] 麦克风按钮、语言胶囊、SpeechLanguagePickerSheet、
                        // voice_chat 快捷动作消费块与「朗读回复」TTS 胶囊（ReadAloudPlayer/
                        // VoiceOutputState 流式喂入）随语音全家（ASR+TTS）整体退役删除。
                        Spacer(modifier = Modifier.width(8.dp))

                        if (showContextMeter) {
                            NovexContextMeter(
                                usedTokens = lastTurnContextTokens,
                                windowTokens = contextCapacity.second,
                                maximumTokens = contextCapacity.first,
                                estimated = contextEstimated,
                                ready = contextUsageReady,
                                mode = contextMeterMode,
                                onClick = { onContextMeterModeChange((contextMeterMode + 1) % 2) },
                            )
                        }

                        // [A2c-expand] 全屏编写入口——长文输入是创作产品的常态，
                        // 价值高于常驻的额度环。
                        ComposerGlyphButton(onClick = { showComposerExpanded = true }) {
                            Icon(
                                novex.android.ui.NovexIcons.Fullscreen,
                                contentDescription = "展开输入",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                        }

                        // [A2c-voice-cut] 语音/语言入口下线（用户决策：
                        // 没有此需求，功能将整体砍掉）。第二行只留
                        // + / 叠卡 / 额度环 / ⤢。
                    }
                }
            }
                // --- Swipe-to-send floating hint (extracted helper) ---
                SwipeToSendHint(
                    progress = sendSwipeProgress,
                    armFraction = swipeArmFraction,
                    location = sendSwipeLocation,
                    hoverAbovePx = swipeHapticOffsetPx,
                    arrowHalfPx = swipeArrowHalfPx,
                    // While streaming, sendMessage() routes the prompt
                    // through enqueuePrompt() instead — surface that in
                    // the hint so the user knows the gesture still works
                    // mid-stream (mirrors the send-button's send/enqueue
                    // toggle, since on Android there's no separate visual
                    // state for the queued case).
                    isEnqueue = isStreaming,
                )
}
}
