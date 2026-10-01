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
 * 会话页 Scaffold 内的就地对话框群：移动到…、全屏编写、控制项查看、
 * 发送前压缩门槛、资料与存档、删会话、从此处删、新会话停流确认、
 * 增强缓存确认。开关位由宿主持有，本件只报回 set/clear。
 */
@Composable
internal fun ChatScreenInlineDialogs(
    viewModel: ChatViewModel,
    sessionId: String,
    chatRepository: ChatRepository,
    immersiveProfile: ImmersiveChatProfile,
    inputText: String,
    novexControlView: novex.core.ConversationControlOutcome.View?,
    pendingDeleteFromMessageId: String?,
    onPendingDeleteCleared: () -> Unit,
    pendingShareText: String?,
    onShareTextCleared: () -> Unit,
    showMoveSheet: Boolean,
    onMoveSheetDismissed: () -> Unit,
    onMoveToSession: (String) -> Unit,
    onImportCard: (android.net.Uri, Boolean) -> Unit,
    showComposerExpanded: Boolean,
    onComposerExpandedDismissed: () -> Unit,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    showConversationRecords: Boolean,
    onRecordsDismissed: () -> Unit,
    onBrowseChatFiles: (String) -> Unit,
    showClearChatDialog: Boolean,
    onClearChatDismissed: () -> Unit,
    onBack: () -> Unit,
    showNewChatStopDialog: Boolean,
    onNewChatStopDismissed: () -> Unit,
    onNewChat: (String?, String?, String?, String?) -> Unit,
    showEnhancedCacheDialog: Boolean,
    onEnhancedCacheDismissed: () -> Unit,
) {
            if (showMoveSheet) {
                MoveToSessionSheet(
                    onImportCard=if(viewModel.attachments.value.size==1)({world->
                        val attachment=viewModel.attachments.value.singleOrNull()
                        if(attachment!=null){onMoveSheetDismissed();onImportCard(attachment.uri,world)}
                    }) else null,
                    currentSessionId = sessionId,
                    chatRepository = chatRepository,
                    onDismiss = { onMoveSheetDismissed() },
                    onSelect = { targetId ->
                        ChatViewModelStore.stashPendingTransfer(
                            ChatViewModelStore.PendingTransfer(
                                inputText = pendingShareText ?: inputText,
                                attachments = viewModel.attachments.value,
                                // [T-android-moveto-stash-binding] Bind the stash to
                                // the chosen target so no other session can drain it.
                                targetId = targetId,
                            ),
                        )
                        viewModel.setInputText("")
                        viewModel.clearAttachments()
                        viewModel.clearShareInjectedFlag()
                        onShareTextCleared()
                        onMoveSheetDismissed()
                        onMoveToSession(targetId)
                    },
                )
            }


            // [A2c-expand] 全屏编写：modal 编辑器与输入框共用 inputText 状态，
            // 关闭即落回草稿，不丢内容。
            if (showComposerExpanded) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { onComposerExpandedDismissed() },
                    properties = androidx.compose.ui.window.DialogProperties(
                        usePlatformDefaultWidth = false,
                    ),
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = ChatColors.background,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .imePadding(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = { onComposerExpandedDismissed() }) {
                                    Icon(
                                        novex.android.ui.NovexIcons.Close,
                                        contentDescription = "关闭",
                                        tint = ChatColors.primaryText,
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = "编写",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ChatColors.primaryText,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                novex.android.ui.TextButton(
                                    onClick = { onComposerExpandedDismissed() },
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = com.openminis.app.ui.noven.NovenColors.Mint,
                                    ),
                                ) {
                                    Text("完成", fontWeight = FontWeight.SemiBold)
                                }
                            }
                            val expandedFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                            androidx.compose.foundation.text.BasicTextField(
                                value = inputText,
                                onValueChange = { viewModel.setInputText(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                                    .focusRequester(expandedFocus),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = ChatColors.primaryText,
                                    fontSize = 16.sp,
                                    lineHeight = 26.sp,
                                ),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                    com.openminis.app.ui.noven.NovenColors.Mint,
                                ),
                            ) { innerTextField ->
                                Box {
                                    if (inputText.isEmpty()) {
                                        Text(
                                            text = "把想法写长一点…",
                                            color = ChatColors.secondaryText,
                                            fontSize = 16.sp,
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                            LaunchedEffect(Unit) {
                                expandedFocus.requestFocus()
                                keyboardController?.show()
                            }
                        }
                    }
                }
            }

            novexControlView?.let { view ->
                NovexNoticeDialog(
                    title = view.title,
                    message = if (view.values.isEmpty()) {
                        "当前分支还没有记录该状态。"
                    } else {
                        view.values.entries.joinToString("\n") { (key, value) ->
                            "$key：${value.novexDisplayValue()}"
                        }
                    },
                    onDismiss = viewModel::dismissNovexControlView,
                )
            }

            // Pre-send context gate (iOS "Context Near Capacity" alert).
            // Raised when the compact threshold is crossed and auto-compact is
            // OFF; with it on the ViewModel compacts silently and never gets
            // here. Three actions, matching iOS:
            //   Send Anyway                  — skip compaction entirely
            //   Compact & Send               — compact this once, pref untouched
            //   Compact & Enable Auto-Compact— compact AND opt in, so the
            //                                  threshold stops prompting from
            //                                  now on (iOS T-chat-auto-compact-opt-in)
            val showCompactBeforeSend by viewModel.showCompactBeforeSendPrompt.collectAsState()
            if (showCompactBeforeSend) {
                MinisAlertDialog(
                    // Back-gesture / scrim dismissal must NOT silently drop the
                    // user's text — cancelCompactBeforeSend puts it back in the
                    // composer.
                    onDismissRequest = { viewModel.cancelCompactBeforeSend() },
                    title = stringResource(R.string.context_near_capacity_title),
                    text = stringResource(R.string.context_near_capacity_message),
                    confirmText = stringResource(R.string.context_compact_and_send),
                    onConfirm = { viewModel.compactAndSendPending() },
                    dismissText = stringResource(R.string.context_send_anyway),
                    onDismiss = { viewModel.sendPendingWithoutCompacting() },
                    neutralText = stringResource(R.string.context_compact_and_enable_auto),
                    onNeutral = {
                        viewModel.compactAndSendPending(alsoEnableAutoCompact = true)
                    },
                )
            }

            if (showConversationRecords) {
                novex.android.ui.NovexSelectionSheet(
                    title = "资料与存档",
                    onDismissRequest = { onRecordsDismissed() },
                    actions = listOf(
                        // 「本对话成果」而非「本对话文件」——进的是创作件库，
                        // 与会话设置里「对话空间」的原始文件浏览器拉开语义。
                        novex.android.ui.NovexSelectionAction("本对话成果") {
                            onRecordsDismissed()
                            viewModel.prepareNovexLearningFiles(onBrowseChatFiles)
                        },
                        novex.android.ui.NovexSelectionAction("资料整理进度") {
                            onRecordsDismissed()
                            viewModel.showNovexLearningCollections()
                        },
                        novex.android.ui.NovexSelectionAction("文游存档") {
                            onRecordsDismissed()
                            viewModel.showNovexCheckpoints()
                        },
                    ),
                )
            }
            if (showClearChatDialog) {
                NovexDeleteConversationDialog(
                    conversationId = viewModel.activeSessionId,
                    onDismiss = { onClearChatDismissed() },
                    onDeleted = { onClearChatDismissed(); onBack() },
                )
            }
            pendingDeleteFromMessageId?.let { messageId ->
                MinisAlertDialog(
                    onDismissRequest = { onPendingDeleteCleared() },
                    title = stringResource(R.string.chat_delete_from_here_title),
                    text = stringResource(R.string.chat_delete_from_here_body),
                    confirmText = stringResource(R.string.chat_delete_from_here_confirm),
                    isDestructive = true,
                    onConfirm = {
                        viewModel.deleteFromMessage(messageId)
                        viewModel.setInputText("")
                        onPendingDeleteCleared()
                    },
                )
            }
            // [T-new-chat-menu-entry] Streaming guard for the menu's New Chat:
            // confirm → stop the running task, then navigate to a fresh draft;
            // dismiss → stay in the current chat.
            if (showNewChatStopDialog) {
                MinisAlertDialog(
                    onDismissRequest = { onNewChatStopDismissed() },
                    title = stringResource(R.string.chat_menu_new_chat),
                    text = stringResource(R.string.chat_new_chat_stop_dialog_body),
                    confirmText = stringResource(R.string.chat_new_chat_stop_dialog_confirm),
                    isDestructive = true,
                    onConfirm = {
                        onNewChatStopDismissed()
                        viewModel.cancelStream()
                        onNewChat(
                            immersiveProfile.world?.id,
                            immersiveProfile.character?.id.takeIf {
                                immersiveProfile.characterVersionId == null
                            },
                            immersiveProfile.characterVersionId,
                            immersiveProfile.persona?.id,
                        )
                    },
                )
            }
            // [T-android-enhanced-cache] One-time extra-billing confirmation
            // before the first enable. Accepting records the durable ack and
            // turns the toggle on; subsequent enables skip the dialog.
            if (showEnhancedCacheDialog) {
                MinisAlertDialog(
                    onDismissRequest = { onEnhancedCacheDismissed() },
                    title = stringResource(R.string.chat_menu_enhanced_cache),
                    text = stringResource(R.string.enhanced_cache_dialog_body),
                    confirmText = stringResource(R.string.enhanced_cache_dialog_confirm),
                    onConfirm = {
                        viewModel.confirmAndEnableEnhancedCache()
                        onEnhancedCacheDismissed()
                    },
                )
            }
}
