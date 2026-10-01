package com.openminis.app.ui.chat

// [T-android-split-chat] Assistant-message + tool-pill + thinking rendering
// extracted verbatim from ChatScreen.kt: AssistantHeader, AssistantMessageView,
// BoundsTrackedBlock, InlineErrorBanner, ToolStopButton,
// formatToolDetailsForClipboard, ToolCallPill, ThinkingBlock.
// Full import block copied (unused=warnings); externally-called ones internal.

import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.openminis.app.R
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import novex.android.data.model.ThinkingLevel
import com.openminis.app.data.character.effectiveAssistantAvatarPath
import com.openminis.app.data.character.effectiveAssistantName
import com.openminis.app.data.character.usesRolePresentation

@Composable
internal fun AssistantHeader() {
    // [T-soul-md] Identity header = locked ✨ sparkle gradient icon +
    // SOUL.md-driven `name`. The emoji-customization field was removed,
    // so we no longer branch on `SoulMetadata.emoji`; the icon stays the
    // canonical sparkle (iOS: sparkles SF Symbol + gradient). Only the
    // `name` field is user-customizable — defaults to "Minis" when
    // SOUL.md is missing the field or set to the default value.
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val immersiveProfile = LocalImmersiveChatProfile.current
    val displayName = immersiveProfile.effectiveAssistantName
        ?: soulMeta.name.ifBlank { com.openminis.app.agent.SoulMetadata.DEFAULT.name }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // [T-android-user-assistant-spacing-16] top=10 so the
            // User→Assistant boundary reads ~16dp: user-bubble bottom(4) +
            // LazyColumn spacedBy(2) + this top(10) = 16. The header→body gap
            // inside the turn is unaffected (that's this row's bottom=2).
            .padding(top = 10.dp, bottom = 2.dp),
    ) {
        val avatar = immersiveProfile.effectiveAssistantAvatarPath
            ?.let { java.io.File(it) }
            ?.takeIf { it.exists() }
        if (avatar != null) {
            AsyncImage(
                model = avatar,
                contentDescription = "$displayName 头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(28.dp).clip(CircleShape),
            )
        } else if (immersiveProfile.usesRolePresentation) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
                modifier = Modifier.size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = novex.android.ui.NovexIcons.Person,
                        contentDescription = "$displayName 默认头像",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            val sparkleGradient = Brush.linearGradient(
                colors = listOf(SparkleColor1, SparkleColor2),
            )
            Icon(
                imageVector = novex.android.ui.NovexIcons.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(brush = sparkleGradient, blendMode = BlendMode.SrcIn)
                    },
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = displayName,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * [feat/ui-rikkahub] Assistant replies render FLAT — full-width borderless
 * text in the narrative (non-role) mode (RikkaHub/豆包-style). In role
 * presentation the reply returns to a speech bubble — white surface + hairline
 * border under the avatar/name header (chat-v1/02). The old gray bubble
 * survives as [LegacyCharacterAssistantBubble] so the change is a one-line
 * revert.
 */
@Composable
internal fun CharacterAssistantBubble(content: @Composable () -> Unit) {
    if (!LocalImmersiveChatProfile.current.usesRolePresentation) {
        content()
        return
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun LegacyCharacterAssistantBubble(content: @Composable () -> Unit) {
    if (!LocalImmersiveChatProfile.current.usesRolePresentation) {
        content()
        return
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                content()
            }
        }
    }
}

@Composable
internal fun AssistantMessageView(message: ChatMessage, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        AssistantHeader()

        // Render blocks in original order — text, thinking, and tool calls interleaved
        // exactly as they arrived in the stream (each assistant turn may contain multiple
        // text ↔ tool_use transitions, which must be preserved for coherent reading).
        val toolPillBlocks = message.toolBlocks.filter { it.kind == "tool_use" }
        val lastThinkingId = message.toolBlocks.lastOrNull { it.kind == "thinking" }?.id
        // [T-android-thinking-auto-collapse] Mirror the FlatChatItem path's
        // `isLastBlockOverall` so the legacy renderer's ThinkingBlock also
        // flips !isStreaming when a sibling block arrives (id of the last
        // block of ANY kind in the message). See ChatFlatItems builder.
        val lastBlockIdOverall = message.toolBlocks.lastOrNull()?.id
        // Backward compat: if there are no text blocks but message.content is non-empty
        // (e.g. legacy sessions saved before the text-block migration), fall back to
        // rendering message.content after all tool blocks.
        val hasAnyTextBlock = message.toolBlocks.any { it.kind == "text" }
        val lastTextBlockIndex = message.toolBlocks.indexOfLast { it.kind == "text" }
        message.toolBlocks.forEachIndexed { index, block ->
            when (block.kind) {
                "thinking" -> {
                    // T300: same per-message thinking-level gate as the
                    // FlatChatItem path. AssistantMessageView is currently
                    // unreferenced (legacy pre-FlatChatItem code) but the
                    // gate stays here so any future re-introduction
                    // doesn't silently bring back the always-render bug.
                    val effectiveLevel = message.thinkingLevel
                        ?: novex.android.data.model.ThinkingLevel.MEDIUM
                    if (effectiveLevel.isEnabled) {
                        // [T-android-thinking-auto-collapse] Stream signal
                        // requires THIS block to be the trailing block of
                        // any kind, not just the last thinking — see
                        // FlatChatItem path + iOS ThinkingBlockView parity.
                        val isTrailingThinking = block.id == lastBlockIdOverall
                        ThinkingBlock(
                            block,
                            isStreaming = isTrailingThinking && message.isStreaming,
                            isLast = block.id == lastThinkingId,
                        )
                    }
                }
                "info" -> {
                    FallbackInfoBlock(block)
                }
                "text" -> {
                    if (block.content.isNotEmpty()) {
                        val isLastTextBlock = index == lastTextBlockIndex
                        val streaming = message.isStreaming && isLastTextBlock
                        // T-android-gc-storm-issue17: defensive guard on the legacy
                        // pre-FlatChatItem path too.
                        LargeContentGuard(
                            content = block.content,
                            isStreaming = streaming,
                            stableKey = "legacy-text:${message.id}:${block.id}",
                        ) {
                            StreamingMarkdownText(
                                content = block.content,
                                // Only the trailing text block is "still streaming"; earlier
                                // text blocks (before a tool call) are frozen.
                                isStreaming = streaming,
                            )
                        }
                    }
                }
                else -> {
                    // tool_use
                    ToolCallPill(block, allToolBlocks = toolPillBlocks)
                }
            }
        }

        // Typing indicator when streaming with no content yet (info-only blocks don't count)
        val hasRealBlocks = message.toolBlocks.any { it.kind != "info" }
        if (message.isStreaming && message.content.isEmpty() && !hasRealBlocks) {
            TypingIndicator()
        }

        // Legacy fallback: render message.content when no text blocks exist (old sessions).
        if (!hasAnyTextBlock && message.content.isNotEmpty()) {
            LargeContentGuard(
                content = message.content,
                isStreaming = message.isStreaming,
                stableKey = "legacy-fallback:${message.id}",
            ) {
                StreamingMarkdownText(
                    content = message.content,
                    isStreaming = message.isStreaming,
                )
            }
        }

        // Inline error banner (iOS: red exclamation + error text + Retry button)
        if (message.error != null) {
            InlineErrorBanner(error = message.error, onRetry = onRetry)
        }
    }
}

/**
 * Wraps a per-message LazyColumn item, registering its bounds (in window
 * coordinates) into [LocalMessageBoundsRegistry] so the selection toolbar
 * can look up which message a selection rect belongs to. The slot key
 * disambiguates multiple items belonging to the same message id (e.g. a
 * message with several text blocks).
 */
@Composable
internal fun BoundsTrackedBlock(
    messageId: String,
    slotKey: String,
    markdown: String,
    content: @Composable () -> Unit,
) {
    val registry = LocalMessageBoundsRegistry.current
    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            registry?.put(messageId, slotKey, coords.boundsInWindow(), markdown)
        },
    ) {
        content()
    }
    androidx.compose.runtime.DisposableEffect(messageId, slotKey) {
        onDispose { registry?.remove(messageId, slotKey) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InlineErrorBanner(error: String, onRetry: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    val translatedError = remember(error) { novexErrorMessage(error) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFF3B30).copy(alpha = 0.12f))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(error))
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = novex.android.ui.NovexIcons.Error,
            contentDescription = null,
            tint = Color(0xFFFF3B30),
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = translatedError,
            color = Color(0xFFFF3B30),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFFF3B30).copy(alpha = 0.15f))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = novex.android.ui.NovexIcons.Refresh,
                    contentDescription = null,
                    tint = Color(0xFFFF3B30),
                    modifier = Modifier.size(10.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.chat_longpress_retry), color = Color(0xFFFF3B30), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * T14: per-card stop affordance shown on running/streaming tool blocks.
 * Mirrors iOS `ToolCapsuleView`'s small red square that appears trailing
 * the tool title when `block.toolStatus == .running`. Tapping it routes to
 * the same global `cancelStream()` callback iOS uses for `onStop?()` —
 * iOS also has no per-tool cancellation API; the per-card button is purely
 * an affordance-discoverability win. Resume banner (T13) makes the global
 * cancel UX recoverable.
 *
 * Visual: 14×14 red rounded square (Color 0xFFFF3B30 = iOS systemRed).
 */
