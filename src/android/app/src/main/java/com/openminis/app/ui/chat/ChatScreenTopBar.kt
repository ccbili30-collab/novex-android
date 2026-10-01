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

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.ManagedActivityResultLauncher
import com.openminis.app.data.character.ImmersiveChatProfile

/**
 * 会话页非对称三段式顶栏：标题列在「返回键 ↔ 动作簇」剩余区间内绝对居中
 * （自定义 Layout 测量），徽标排标题正下方，动作簇在右（侧边页换导出+删除）。
 * 淡化由 [effectiveChromeCollapsed] 驱动，唤回经 [onReviveChrome] 回报宿主。
 */
@Composable
internal fun ChatTopBar(
    viewModel: ChatViewModel,
    effectiveChromeCollapsed: Boolean,
    immersiveProfile: ImmersiveChatProfile,
    showChatTitlePill: Boolean,
    sessionTitle: String,
    onTitleClick: () -> Unit,
    selectedGroupName: String,
    availableGroups: List<ModelGroup>,
    providerRepository: ProviderRepository,
    modelName: String,
    onOpenModelPicker: () -> Unit,
    onOpenThinkingSheet: () -> Unit,
    returnFromConversation: () -> Unit,
    sideParentId: String?,
    onOpenSideDelete: () -> Unit,
    chromeCollapsed: Boolean,
    onReviveChrome: () -> Unit,
    showChatMenu: Boolean,
    onChatMenuShown: () -> Unit,
    onChatMenuDismissed: () -> Unit,
    onShowHistory: () -> Unit,
    onSettings: () -> Unit,
    onRequestSidePanel: () -> Unit,
    onShowRecords: () -> Unit,
    onShowClearDialog: () -> Unit,
    immersiveBackgroundPickerLauncher: ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
) {
            // 沉浸淡化（2026-09-15）：顶栏原地淡出淡入，不滑动；内容区顶部
            // 内边距恒定（见下方 padding），版式零跳动。侧边对话页不淡化。
            AnimatedVisibility(
                visible = !effectiveChromeCollapsed,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(220)),
            ) {
            // [feat/ui-rikkahub] 非对称三段式顶栏：标题列拿到返回键与动作簇之间
            // 的全部剩余宽度并在其中居中——NovexTopBarSurface 的 2×宽侧对称预留
            // 在模型 pill 进 actions 后会把标题挤成零宽，整列渲染但不可见。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChatColors.background)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(chatTopBarExpandedHeightDp(LocalDensity.current.fontScale).dp),
            ) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides ChatColors.primaryText) {
            androidx.compose.ui.layout.Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    // content[0]：标题 + 模型/状态徽标列，包裹内容宽度，
                    // 放置时以屏幕中线为轴实现绝对居中（见 measurePolicy）。
                    Box(
                        contentAlignment = Alignment.Center,
                    ) {
                        val noFontPad = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        )
                        // Fallback pulse animation (iOS: 3× red pulse on model switch)
                        val fallbackTrigger by viewModel.fallbackTrigger.collectAsState()
                        val fallbackPulseAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
                        LaunchedEffect(fallbackTrigger) {
                            if (fallbackTrigger == 0) return@LaunchedEffect
                            repeat(3) {
                                fallbackPulseAlpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(350))
                                fallbackPulseAlpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(350))
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Red.copy(alpha = 0.35f * fallbackPulseAlpha.value))
                                // [T-android-topbar-shrink] vertical 4dp→2dp.
                                // Combined with the expandedHeight drop below,
                                // closes the dead-space gap between the model
                                // name row and the TopAppBar bottom edge that
                                // T-topbar-model-row-clip's 76dp overshoot left
                                // behind. Horizontal 32dp keeps the fallback
                                // pulse highlight comfortably padded around
                                // the longest title.
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            // Nav title: current session title when one
                            // exists and the toggle is on, else fall back to
                            // the Soul name (matches the input placeholder
                            // "Message <SoulName>"), then to app_name
                            // ("Minis") as the terminal fallback.
                            // Tap opens the same SessionEditSheet used from
                            // the session list — drafts return null from
                            // loadSessionEntity so the sheet stays closed.
                            // SoulStore.cachedMetadata is the same source the
                            // input placeholder uses (see ~line 3581), so
                            // soul renames in Soul Settings reflect here live.
                            val topBarSoul by com.openminis.app.agent.SoulStore
                                .cachedMetadata.collectAsState()
                            val displayTitle = when {
                                immersiveProfile.usesRolePresentation &&
                                    immersiveProfile.effectiveAssistantName?.isNotBlank() == true ->
                                    immersiveProfile.effectiveAssistantName!!
                                showChatTitlePill
                                    && sessionTitle.isNotBlank()
                                    && sessionTitle != "New Chat" -> sessionTitle
                                topBarSoul.name.isNotBlank() -> topBarSoul.name
                                else -> stringResource(R.string.app_name)
                            }
                            Text(
                                text = displayTitle,
                                fontSize = 16.sp,
                                lineHeight = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ChatColors.primaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = noFontPad,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        onTitleClick()
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            // [A2a-rev] 副标题归还给模型：居中显示模型分组
                            // pill（点按出模型选择），健康点保留绿/橙语义色；
                            // ⚡/思考等级徽标排在其后。挂卡不占副标题。
                            val thinkingLevelBadgeState by viewModel.thinkingLevel.collectAsState()
                            val fastBadgeEligible by viewModel.showFastModeToggle.collectAsState()
                            val fastBadgeOn by viewModel.fastModeEnabled.collectAsState()
                            val hasFastBadge = fastBadgeEligible && fastBadgeOn
                            // Same visibility rule as the old subtitle badge:
                            // shown while a level is enabled, or Off-but-
                            // discoverable when the model supports reasoning.
                            val hasThinkingBadge = viewModel.availableThinkingLevels.isNotEmpty() &&
                                (
                                    thinkingLevelBadgeState.isEnabled ||
                                        viewModel.currentModelSupportsReasoning
                                )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .padding(top = 3.dp)
                                    .horizontalScroll(rememberScrollState()),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        // [A2c-chrome] 去灰底——模型 pill 只留
                                        // 点+名+折角三元素，不再是灰色矩形控件。
                                        .clickable { onOpenModelPicker() }
                                        .padding(horizontal = 9.dp, vertical = 5.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .background(
                                                if (modelName.isNotEmpty()) Color(0xFF34C759) else Color(0xFFFF9500),
                                                CircleShape,
                                            ),
                                    )
                                    val groupNameDisplay = selectedGroupName.ifEmpty {
                                        val defaultGroupId = providerRepository.defaultPrimaryGroupId
                                        availableGroups.firstOrNull { it.id == defaultGroupId }?.name
                                            ?: stringResource(R.string.model_picker_default_badge)
                                    }
                                    Text(
                                        text = groupNameDisplay,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ChatColors.secondaryText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 96.dp),
                                    )
                                    Icon(
                                        novex.android.ui.NovexIcons.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = ChatColors.tertiaryText,
                                        modifier = Modifier.size(13.dp),
                                    )
                                }
                                if (hasFastBadge) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(13.dp)
                                                .background(Color(0xFFFF9500), CircleShape),
                                        ) {
                                            Icon(
                                                novex.android.ui.NovexIcons.Bolt,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(10.dp),
                                            )
                                        }
                                    }
                                    if (hasThinkingBadge) {
                                        ThinkingLevelBadge(
                                            level = thinkingLevelBadgeState,
                                            onClick = { onOpenThinkingSheet() },
                                        )
                                    }
                                }
                            }
                        }
                    // content[1]：返回键
                    Box {
                        IconButton(onClick = returnFromConversation) {
                            Icon(novex.android.ui.NovexIcons.ArrowBack, contentDescription = "Back")
                        }
                    }
                    // content[2]：动作簇（模型 pill / DeepSeek 时钟 / ⋯；侧边页为导出+删除）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sideParentId != null) {
                        // 侧边页顶栏：删除（决策 16）+ 导出（2026-09-16 用户反馈侧边
                        // 无法导出诊断包——图片问题取证时就卡在这）。导出复用主线
                        // 弹窗与全局 wire-capture，无需绕回主线。
                        IconButton(onClick = { viewModel.prepareNovexConversationExport() }) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_phosphor_arrow_up),
                                contentDescription = "导出侧边对话包",
                            )
                        }
                        IconButton(onClick = { onOpenSideDelete() }) {
                            Icon(
                                novex.android.ui.NovexIcons.Delete,
                                contentDescription = "删除侧边对话",
                                tint = androidx.compose.ui.graphics.Color(0xFFFF5A5F),
                            )
                        }
                    } else {
                    // [A2a-rev] 模型 pill 已回到副标题（标题正下方居中）；
                    // actions 只剩语义化时钟与 ⋯。
                    NovexDeepSeekClock()
                    // iOS: "..." circle button → dropdown menu
                    Box {
                        IconButton(onClick = {
                            // [T-menu-revive] 2026-09-16 用户批④：淡化状态下打开
                            // 菜单自动唤回界面，菜单不再悬在隐形顶栏上。
                            if (chromeCollapsed) onReviveChrome()
                            onChatMenuShown()
                        }) {
                            Icon(
                                imageVector = novex.android.ui.NovexIcons.MoreHoriz,
                                contentDescription = "更多操作",
                            )
                        }
                        val chatActions = buildList {
                            add(NovexMenuAction("对话历史", R.drawable.ic_phosphor_search) {
                                onShowHistory()
                            })
                            add(
                                NovexMenuAction("对话设置", R.drawable.ic_phosphor_sliders_horizontal) {
                                    onSettings()
                                },
                            )
                            add(NovexMenuAction("侧边对话", R.drawable.ic_phosphor_arrow_left) {
                                onRequestSidePanel()
                            })
                            add(NovexMenuAction("资料与存档", R.drawable.ic_phosphor_note_pencil,
                                onClick = { onShowRecords() }))
                            // 导出对话包对全部通道开放（用户决策 2026-09-15）：
                            // 正式版用户反馈问题时也能提供诊断导出，不再只有预览版可导。
                            add(
                                NovexMenuAction("导出对话包", R.drawable.ic_phosphor_arrow_up,
                                    onClick = viewModel::prepareNovexConversationExport))
                            if (immersiveProfile.usesRolePresentation) {
                                add(
                                    NovexMenuAction("更换对话背景", R.drawable.ic_phosphor_image) {
                                        immersiveBackgroundPickerLauncher.launch(
                                            androidx.activity.result.PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                                            ),
                                        )
                                    },
                                )
                                add(
                                    NovexMenuAction("恢复角色默认背景", R.drawable.ic_phosphor_arrow_clockwise) {
                                        viewModel.setImmersiveBackground(null)
                                    },
                                )
                            }
                            add(
                                NovexMenuAction(
                                    "删除对话",
                                    R.drawable.ic_phosphor_trash,
                                    destructive = true,
                                ) { onShowClearDialog() },
                            )
                        }
                        NovexActionMenu(
                            expanded = showChatMenu,
                            onDismissRequest = onChatMenuDismissed,
                            actions = chatActions,
                        )
                    }
                    }
                    }
                },
            ) { measurables, constraints ->
                // 标题可用宽 = 屏宽 − 返回键 − 动作簇（非对称，不再 2×宽侧预留）；
                // 标题在剩余区间内居中，既不压交互区也不会被宽动作簇挤没。
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val leading = measurables[1].measure(loose)
                val trailing = measurables[2].measure(
                    loose.copy(maxWidth = (constraints.maxWidth - leading.width).coerceAtLeast(0)),
                )
                val heading = measurables[0].measure(
                    loose.copy(maxWidth = (constraints.maxWidth - leading.width - trailing.width).coerceAtLeast(0)),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    leading.placeRelative(0, (constraints.maxHeight - leading.height) / 2)
                    trailing.placeRelative(constraints.maxWidth - trailing.width, (constraints.maxHeight - trailing.height) / 2)
                    // [A2a-rev] 标题绝对居中：以屏幕中线为轴（用户指定的原
                    // 逻辑），仅测量上限用剩余区间宽防止溢出压到两侧按钮。
                    heading.placeRelative(
                        ((constraints.maxWidth - heading.width) / 2).coerceAtLeast(0),
                        (constraints.maxHeight - heading.height) / 2,
                    )
                }
            }
            }
            }
            }
}
