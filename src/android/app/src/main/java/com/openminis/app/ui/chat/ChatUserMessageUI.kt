package com.openminis.app.ui.chat

// [T-android-split-chat] User-message rendering extracted verbatim from
// ChatScreen.kt: UserMessageBubble, UserAttachmentList, FileAttachmentTile,
// fileIconFor, ImageGalleryDialog. Full import block copied (unused=warnings).
// Externally-called ones (UserMessageBubble, UserAttachmentList) are internal.

import android.net.Uri
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import novex.android.ui.DropdownMenu
import novex.android.ui.DropdownMenuItem
import com.openminis.app.R
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.data.character.effectivePlayerAvatarPath
import com.openminis.app.data.character.effectivePlayerName
import com.openminis.app.data.character.usesRolePresentation
import com.openminis.app.ui.theme.ChatColors
import novex.core.ContextSourceKind
import novex.android.ui.NovexContentDialog
import novex.android.ui.TextButton as NovexTextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// ─── User Message (right-aligned, iOS: tertiarySystemFill bubble, 18dp radius) ─

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun UserMessageBubble(
    message: ChatMessage,
    perTurnPrompt: String = "",
    // [T-android-candidate-bubble-gap] When true, this bubble directly
    // follows another user bubble (no AssistantHeader between them), so add
    // extra top padding to keep the two visually separated. Default false
    // preserves the original 4dp symmetric spacing for the normal
    // user→assistant→user cadence.
    precededByUser: Boolean = false,
    onCopy: () -> Unit = {},
    onShare: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = {},
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onWithdraw: (() -> Unit)? = null,
    onPreviewFile: (Uri, String) -> Unit = { _, _ -> },
) {
    var showMenu by remember { mutableStateOf(false) }
    var showContextUsage by remember { mutableStateOf(false) }
    val isQueued = message.isQueued
    val haptics = LocalHapticFeedback.current
    val immersiveProfile = LocalImmersiveChatProfile.current
    val playerName = immersiveProfile.effectivePlayerName ?: "玩家"
    val isRolePresentation = immersiveProfile.usesRolePresentation
    val personaAvatar = immersiveProfile.effectivePlayerAvatarPath
        ?.let { java.io.File(it) }
        ?.takeIf { it.exists() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // [T-android-user-assistant-spacing-16] 14dp top when stacked on
            // another user bubble (User→User, left intentionally larger to
            // separate distinct user messages — unchanged). When preceded by an
            // ASSISTANT turn, 10dp so the Assistant→User boundary reads ~16dp
            // together with the assistant trailing-block bottom + LazyColumn
            // spacedBy(2). Bottom stays 4dp: the User→Assistant boundary is
            // user.bottom(4) + spacedBy(2) + AssistantHeader.top(10) = 16.
            .padding(top = if (precededByUser) 14.dp else 10.dp, bottom = 4.dp),
    ) {
        // Cap the bubble at 80% of the LazyColumn's available width (mirrors iOS
        // proportional sizing; prevents single-line messages from spanning the
        // full row and losing their "trailing bubble" shape).
        val bubbleMaxWidth = this.maxWidth * 0.8f
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Spacer(modifier = Modifier.weight(1f).widthIn(min = 60.dp))
        Box(
            modifier = Modifier
                .widthIn(max = bubbleMaxWidth)
                // pointerInput on the OUTER box (= MinisMenu's anchor). Long-press
                // anywhere on the bubble (text or attachments) opens the menu;
                // press coords are stored in this box's coordinate space, which
                // is exactly what DropdownMenu's `offset` parameter expects.
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = {
                            // Haptic must be fired by hand here. `combinedClickable`
                            // (what the tool pill uses) buzzes on long-press for
                            // free; raw `detectTapGestures` does not, so this
                            // gesture — chosen for its press OFFSET — silently
                            // lost the feedback every other long-press menu has.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Anchor the menu to the bubble — DropdownMenu
                            // already places itself just below the anchor and
                            // auto-flips above when there's no room. Using
                            // the press y as offset (previous behavior) made
                            // the menu jump halfway down a tall bubble away
                            // from the user's finger, which on multi-line
                            // user messages landed in screen center.
                            showMenu = true
                        }
                    )
                }
        ) {
            // iOS: VStack(alignment: .trailing, spacing: 6) { attachments; text bubble }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Attachments ABOVE the text bubble, right-aligned FlowRow (iOS UserAttachmentList)
                val hasAttachments = message.imageUris.isNotEmpty() || message.attachmentNames.isNotEmpty()
                if (hasAttachments) {
                    UserAttachmentList(
                        imageUris = message.imageUris,
                        allFileNames = message.attachmentNames,
                        nonImageUris = message.attachmentUris,
                        onPreviewFile = onPreviewFile,
                    )
                }

                if (message.content.isNotBlank()) {
                    // Queued: transparent bg + dashed border + dimmed text +
                    // a red withdraw button alongside. Mirrors iOS
                    // AIChatView.swift queued-bubble overlay.
                    val secondaryTextColor = ChatColors.secondaryText
                    // [feat/ui-rikkahub] User voice = brand mint, soft fill:
                    // the bubble is the user's action/voice in the transcript,
                    // which is exactly where the redesign spends its accent.
                    // Normal = soft mint fill with dark/surface text for readability;
                    // long-press selected = solid mint with on-mint white text.
                    val isSelected = showMenu
                    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
                    val mint = com.openminis.app.ui.noven.NovenColors.Mint
                    val bubbleBg = when {
                        isQueued -> Color.Transparent
                        isSelected -> mint
                        else -> mint.copy(alpha = if (isDarkTheme) 0.22f else 0.14f)
                    }
                    val textColor = when {
                        isQueued -> secondaryTextColor
                        isSelected -> com.openminis.app.ui.noven.NovenColors.OnMint
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // [feat/ui-rikkahub] 18dp → 16dp: mainstream chat bubble formula.
                        val shape = RoundedCornerShape(16.dp)
                        val dashedStroke = if (isQueued) {
                            Modifier.drawBehind {
                                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 1.5.dp.toPx(),
                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f
                                    ),
                                )
                                val r = 16.dp.toPx()
                                drawRoundRect(
                                    color = secondaryTextColor.copy(alpha = 0.5f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                                    style = stroke,
                                )
                            }
                        } else Modifier
                        // The whole LazyColumn is wrapped in a SelectionContainer,
                        // which by default starts text-selection on long-press. We
                        // want long-press on a user bubble to open the Copy/Retry
                        // menu instead, with NO selection ever registered against
                        // this Text. If we let selection register and the user
                        // taps "Retry", retryFromMessage truncates the list and
                        // the SelectionManager toolbar update then sorts stale
                        // LayoutCoordinates → IllegalArgumentException
                        // ("layouts are not part of the same hierarchy").
                        // DisableSelection scopes the bubble out of selection.
                        // Long-press is captured on the outer Box (above) so
                        // press coords share a LayoutCoordinates space with the
                        // menu anchor.
                        androidx.compose.foundation.text.selection.DisableSelection {
                            // T167: when queued, leave room for the 44dp cancel
                            // IconButton sibling. Without weight(1f, fill=false)
                            // a wide bubble would consume the full 80% width
                            // cap and squeeze the cancel button off-screen.
                            val bubbleModifier = if (isQueued && onWithdraw != null) {
                                Modifier.weight(1f, fill = false)
                            } else Modifier
                            // T-android-gc-storm-issue17: long user messages (rare,
                            // but legacy sessions can carry pasted log dumps) blow
                            // up text layout the same way large assistant content
                            // does. User bubbles never stream so isStreaming=false.
                            LargeContentGuard(
                                content = message.content,
                                isStreaming = false,
                                stableKey = "user:${message.id}",
                            ) {
                                Text(
                                    text = message.content,
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.5.sp),
                                    modifier = bubbleModifier
                                        .background(bubbleBg, shape)
                                        .clip(shape)
                                        .then(dashedStroke)
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                        if (isQueued && onWithdraw != null) {
                            // T52: bring the withdraw target up to Material's
                            // 44dp recommended minimum so it can be hit
                            // reliably from the soft-IME-keyboard region;
                            // 28dp was below the spec and frustrating on
                            // Pixel-class displays. Icon nudged to 24dp to
                            // stay visually centred in the larger ring.
                            IconButton(
                                onClick = onWithdraw,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    imageVector = novex.android.ui.NovexIcons.Cancel,
                                    contentDescription = "Withdraw queued message",
                                    tint = Color(0xFFFF3B30),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
                // [feat/ui-rikkahub] 预览只显示用户的注入与模型的读取；
                // 系统管道（工具定义/系统提示/回答身份）不进预览。
                val injectedSources = message.novexContextUsage?.includedSources.orEmpty()
                    .filterNot { it.sourceId == "answer-identity:nova" }
                    .filterNot {
                        it.kind == ContextSourceKind.TOOL_DEFINITION ||
                            it.kind == ContextSourceKind.CONVERSATION_PROMPT ||
                            it.kind == ContextSourceKind.ANSWER_IDENTITY
                    }
                // [feat/ui-rikkahub] 每轮注入也是"带了东西"：写了就随每轮发送，
                // 气泡行永远把它列在第一位，否则用户写了注入却看不见。
                val injectedLabels = buildList {
                    if (perTurnPrompt.isNotBlank()) add("每轮注入")
                    injectedSources.map { carriedDisplayLabel(it.label) }.distinct().forEach(::add)
                }.distinct()
                val readSourceLabels = message.novexContextUsage?.sourceReads.orEmpty()
                    .filterNot { it.sourceId == "answer-identity:nova" }.map { it.label }.distinct()
                if (injectedLabels.isNotEmpty() || readSourceLabels.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (injectedLabels.isNotEmpty()) {
                            val preview = injectedLabels.take(2).joinToString("、")
                            val remaining = (injectedLabels.size - 2).coerceAtLeast(0)
                            Text(
                                text = buildString {
                                    append("携带：").append(preview)
                                    if (remaining > 0) append(" 等 ").append(injectedLabels.size).append(" 项")
                                },
                                color = ChatColors.tertiaryText,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showContextUsage = true }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                        if (readSourceLabels.isNotEmpty()) {
                            Text(
                                text = "已读取：${readSourceLabels.take(2).joinToString("、")}",
                                color = ChatColors.tertiaryText,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showContextUsage = true }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // Long-press context menu — anchored to the bubble itself.
            // Small positive y-offset so the menu doesn't sit flush against
            // the bubble; the alignEnd path mirrors the offset when it
            // auto-flips above (no room below), so the gap is symmetric in
            // both directions. T238: alignEnd pins the menu's right edge to
            // the bubble's right edge — user bubbles are right-aligned, the
            // menu should follow. Mirrors iOS Messages.app context menu.
            // T280: shrink width ~30% (default minWidth 240dp → 168dp,
            // alignEnd-branch max 280dp → 196dp) so the popup feels less
            // chunky on user bubbles, which only host 2-3 short items
            // (Copy / Retry / Edit). Override is local to the user-message
            // call site — other MinisMenu callers keep the default 240dp
            // minimum.
            // [T-android-tool-menu-minwidth] Match the tool-pill long-press
            // menu: width = min(220dp, screen width). Wants 220dp but must never
            // exceed the device width on a narrow screen; cap max to the same
            // value so the widthIn(min,max) range is always valid.
            UserBubbleMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                onCopy = onCopy,
                onShare = onShare,
                onRetry = onRetry,
                onEdit = onEdit,
                onDelete = onDelete,
            )
        }
        if (personaAvatar != null) {
            Spacer(Modifier.width(8.dp))
            AsyncImage(
                model = personaAvatar,
                contentDescription = "$playerName 头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(30.dp).clip(CircleShape).align(Alignment.Bottom),
            )
        } else if (isRolePresentation) {
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
                modifier = Modifier.size(30.dp).align(Alignment.Bottom),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = novex.android.ui.NovexIcons.Person,
                        contentDescription = "玩家默认头像",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
    }
    val usage = message.novexContextUsage
    if (showContextUsage && usage != null) {
        NovexContentDialog(
            title = "本轮上下文",
            onDismiss = { showContextUsage = false },
            confirmButton = {
                NovexTextButton(onClick = { showContextUsage = false }) { Text("完成") }
            },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "请求预置 ${usage.includedSources.size} 项 · ${usage.usedTokens} 词元",
                    color = ChatColors.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
                // [feat/ui-rikkahub] 每轮注入：折叠行，点开展开常设指令原文。
                if (perTurnPrompt.isNotBlank()) {
                    var perTurnOpen by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.clickable { perTurnOpen = !perTurnOpen },
                    ) {
                        Text(
                            "每轮注入",
                            color = ChatColors.primaryText,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            imageVector = if (perTurnOpen) novex.android.ui.NovexIcons.KeyboardArrowUp else novex.android.ui.NovexIcons.KeyboardArrowDown,
                            contentDescription = if (perTurnOpen) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    AnimatedVisibility(perTurnOpen) {
                        Text(
                            perTurnPrompt,
                            color = ChatColors.secondaryText,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                // [feat/ui-rikkahub] 2026-09-27 系统内部条目（工具定义/系统提示/
                // 回答身份）不暴露给用户——它们每轮固定重复，是管道不是资料。
                // 教学装配诊断链接一并下掉。
                val userSources = usage.includedSources.filterNot {
                    it.kind == ContextSourceKind.TOOL_DEFINITION ||
                        it.kind == ContextSourceKind.ANSWER_IDENTITY ||
                        it.kind == ContextSourceKind.CONVERSATION_PROMPT
                }
                userSources.forEach { source ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "「${contextSourceKindLabel(source.kind)}」${carriedDisplayLabel(source.label)}",
                            color = ChatColors.primaryText,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "${source.tokenCount} 词元${if (source.partial) " · 部分内容" else ""}",
                            color = ChatColors.tertiaryText,
                            fontSize = 12.sp,
                        )
                    }
                }
                if (usage.sourceReads.isNotEmpty()) {
                    Text("本轮查看记录", color = ChatColors.primaryText, fontWeight = FontWeight.SemiBold)
                    novex.core.NovexSourceReadCoverage.from(usage.sourceReads).forEach { coverage ->
                        val methods = usage.sourceReads.filter { it.sourceId == coverage.sourceId && it.revision == coverage.revision }
                            .map { it.method.label }.distinct().joinToString("、")
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(coverage.label, color = ChatColors.primaryText, fontWeight = FontWeight.Medium)
                            Text("$methods · 正文 ${coverage.coveredCharacters}/${coverage.totalCharacters} 字符 · ${if (coverage.complete) "本轮已通读" else "本轮未通读"}",
                                color = ChatColors.secondaryText, fontSize = 12.sp)
                            Text("修订 ${coverage.revision.take(12)} · ${coverage.sourceId}", color = ChatColors.tertiaryText, fontSize = 11.sp)
                        }
                    }
                    Text("目录预览和搜索不计入通读；过去读取过的正文仍可按来源重新查阅。", color = ChatColors.tertiaryText, fontSize = 12.sp)
                }
                if (usage.omittedSources.isNotEmpty()) {
                    Text("预置上下文中未引用", color = ChatColors.primaryText, fontWeight = FontWeight.SemiBold)
                    usage.omittedSources.forEach { source ->
                        Text("${source.label} · ${source.reason}", color = ChatColors.secondaryText)
                    }
                }
            }
        }
    }
}

/** [feat/ui-rikkahub] 上下文来源类型的中文标签——弹窗里标明每条注入是什么。 */
