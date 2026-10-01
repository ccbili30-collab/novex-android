package com.openminis.app.ui.chat

// [T-android-split-chat] Assistant-message + tool-pill + thinking rendering
// extracted verbatim from ChatScreen.kt: AssistantHeader, AssistantMessageView,
// BoundsTrackedBlock, InlineErrorBanner, ToolStopButton,
// formatToolDetailsForClipboard, ToolCallPill, ThinkingBlock.
// Full import block copied (unused=warnings); externally-called ones internal.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import com.openminis.app.R
import com.openminis.app.ui.settings.autoExpandThinkingEnabled
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.components.MinisTextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ThinkingBlock(
    block: AssistantBlock,
    isStreaming: Boolean,
    isLast: Boolean = true,
    isFirst: Boolean = false,
) {
    // Per-block expand state, keyed by block.id so the user's manual toggle on
    // an earlier (finished) thinking block survives recomposition while a
    // later block is still streaming. The previous LaunchedEffect snapped
    // every non-last block back to collapsed on each `isLast` flip, which
    // fought the user's tap and produced a flicker that read as "tapping the
    // earlier block shows the streaming block's content."
    // [T-thinking-auto-expand-toggle] The initial auto-expand of a new
    // streaming block is gated on the Appearance setting (default ON =
    // historical behavior). When the user turned it off, a new streaming block
    // starts collapsed; a manual header tap still expands it (setting
    // userTouched, so neither the stream-end auto-collapse nor anything else
    // fights the user). Read once at mount — mirrors iOS ThinkingBlockView,
    // where the same UserDefaults gate sits at the one-shot auto-expand site.
    val context = LocalContext.current
    val autoExpandThinking = remember { autoExpandThinkingEnabled(context) }
    var expanded by remember(block.id) { mutableStateOf(autoExpandThinking && isLast && isStreaming) }
    var userTouched by remember(block.id) { mutableStateOf(false) }
    LaunchedEffect(block.id, isStreaming) {
        // One-shot auto-collapse when streaming for this block ends, but only
        // if the user hasn't taken control of its state yet.
        if (!isStreaming && !userTouched) expanded = false
    }
    val thinkingBlue = Color(0xFF007AFF)
    val charCount = block.content.length
    val charLabel = when {
        charCount >= 1000 -> "${charCount / 1000}K"
        else -> "$charCount"
    }
    // [T-thinking-render-perf-android] Compose `Text` measures/lays out the
    // ENTIRE string even when only ~300dp is visible, so a 200k-char thinking
    // block froze the UI (and a per-token recomposition re-measured all 200k
    // each tick). Two tiers guard this:
    //  • > HARD_CAP: the inline scroller can't render it at all — show a
    //    "View full content" entry that opens a native TextView dialog
    //    (Android TextView handles large text far better than Compose Text).
    //  • otherwise: render only the last WINDOW chars (tail) — capping layout
    //    cost to O(WINDOW) regardless of total length.
    val thinkingWindowSize = 8000
    val thinkingHardCap = 100_000
    val overHardCap = charCount > thinkingHardCap
    var showFullContent by remember(block.id) { mutableStateOf(false) }

    // [feat/ui-rikkahub] Timeline step visual (ChainOfThought): 24dp node
    // column with connector stubs at x=12dp, secondary-color label with
    // shimmer while streaming, faded live preview when collapsed. The old
    // blue card shell is gone; every behavior below is unchanged.
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val shimmer = rememberInfiniteTransition(label = "thoughtShimmer")
    val shimmerAlpha by shimmer.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "thoughtAlpha",
    )
    val liveThinking = isStreaming && block.toolStatus != ToolBlockStatus.SUCCESS
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header row — only the header reacts to taps. Mirrors iOS, where
        // .onTapGesture is on the header HStack, not the whole VStack. With
        // clickable on the outer Column, a release after dragging in the
        // inner scroller registered as a tap and toggled `expanded`.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // [feat/ui-rikkahub] ChainOfThought connector stubs: 1dp
                    // lines on the left-edge rail at x=8dp, 10dp gap around node.
                    val x = 8.dp.toPx()
                    val centerY = size.height / 2
                    val gap = 10.dp.toPx()
                    if (!isFirst) drawLine(
                        color = lineColor,
                        start = Offset(x, 0f),
                        end = Offset(x, centerY - gap),
                        strokeWidth = 1.dp.toPx(),
                    )
                    if (!isLast) drawLine(
                        color = lineColor,
                        start = Offset(x, centerY + gap),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                .clickable {
                    userTouched = true
                    // [T-thinking-render-perf-android] Over the hard cap the
                    // inline scroller is bypassed entirely; tapping the header
                    // opens the native full-content viewer instead of toggling
                    // the (never-shown) inline expansion.
                    if (overHardCap) showFullContent = true
                    else expanded = !expanded
                }
                .padding(vertical = 8.dp),
        ) {
            Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = novex.android.ui.NovexIcons.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = if (liveThinking) "思考中…" else "深度思考 · $charLabel",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(if (liveThinking) shimmerAlpha else 1f),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (overHardCap) {
                // [T-thinking-render-perf-android] No expand/collapse chevron —
                // the content is too large for the inline Compose scroller.
                // Offer the native full-content viewer instead.
                Text(
                    text = stringResource(R.string.thinking_view_full),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = if (expanded) novex.android.ui.NovexIcons.KeyboardArrowUp else novex.android.ui.NovexIcons.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // [feat/ui-rikkahub] Collapsed live preview while streaming: tail of
        // the thought capped at 100dp with a bottom fade — the ChainOfThought
        // reasoning-step behavior from mainstream chat clients.
        if (!expanded && liveThinking && !overHardCap && charCount > 0) {
            val previewTail = remember(charCount) {
                if (charCount > 600) block.content.substring(charCount - 600) else block.content
            }
            Box(
                modifier = Modifier
                    .padding(start = 32.dp)
                    .heightIn(max = 100.dp)
                    .clipToBounds(),
            ) {
                Text(
                    text = previewTail,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.6f to Color.Transparent,
                                1f to MaterialTheme.colorScheme.surface,
                            )
                        ),
                )
            }
        }

        // Expanded content. Mirrors iOS ThinkingBlockView (AssistantBlockView.swift:648):
        // an inner scroller capped at 300dp, auto-follow to the bottom while the
        // block is streaming, manual drag at any time, and pause-on-user-scroll
        // so a user reading earlier reasoning isn't yanked back to the tail by
        // the next token.
        AnimatedVisibility(visible = expanded && !overHardCap) {
            val scrollState = rememberScrollState()
            // [T-thinking-render-perf-android] Render only the tail window so
            // Compose lays out at most `thinkingWindowSize` chars. `remember`
            // keyed on the length recomputes the substring on each token, but
            // the cost is O(window) not O(total). Snap the cut to the next
            // newline (within 200 chars) so we don't start mid-line.
            val isTruncated = charCount > thinkingWindowSize
            val displayContent = remember(charCount) {
                if (isTruncated) {
                    val full = block.content
                    val start = charCount - thinkingWindowSize
                    val nl = full.indexOf('\n', start)
                    if (nl in start until start + 200) full.substring(nl + 1)
                    else full.substring(start)
                } else {
                    block.content
                }
            }
            // [T-android-thinking-inner-scroll] Pause auto-follow once the user
            // scrolls away from the bottom; resume it when they return. iOS
            // pulls the user back unconditionally — but that fights every
            // touch on Compose's smaller pause-threshold scroller, so we
            // honor the user's drag the way the outer chat list does.
            var userScrolledAway by remember(block.id) { mutableStateOf(false) }
            LaunchedEffect(scrollState, block.id) {
                snapshotFlow {
                    Triple(
                        scrollState.value,
                        scrollState.maxValue,
                        scrollState.isScrollInProgress,
                    )
                }.collect { (v, max, dragging) ->
                    // A nonzero gap from the bottom while the user is actively
                    // dragging counts as "they took control". We don't flip
                    // back until the gap closes — gives them room to scroll
                    // up briefly without ping-ponging.
                    val gap = (max - v).coerceAtLeast(0)
                    when {
                        dragging && gap > 4 -> userScrolledAway = true
                        gap <= 4 -> userScrolledAway = false
                    }
                }
            }
            // Auto-follow: on every content growth, scroll to the new bottom.
            // `snapshotFlow { block.content.length }` is recomposition-cheap
            // and only ticks when the block's text actually grew.
            LaunchedEffect(scrollState, block.id, isStreaming) {
                if (!isStreaming) return@LaunchedEffect
                snapshotFlow { block.content.length }
                    .collect {
                        if (userScrolledAway) return@collect
                        // scrollTo (not animateScrollTo) — animating fights
                        // back-to-back token ticks; iOS uses a 0.15s linear
                        // animation, but Compose's animateScrollTo cancels
                        // any in-flight scroll, so streaming bursts get
                        // jankier than a direct snap.
                        scrollState.scrollTo(scrollState.maxValue)
                    }
            }
            Column(
                modifier = Modifier
                    .padding(start = 32.dp, top = 6.dp)
                    .heightIn(max = 300.dp)
                    .verticalScroll(scrollState),
            ) {
                if (isTruncated) {
                    Text(
                        text = stringResource(
                            R.string.thinking_truncated_hint,
                            displayContent.length / 1000,
                            charCount / 1000,
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                Text(
                    text = displayContent,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    lineHeight = 19.sp,
                )
            }
        }

        // [T-thinking-render-perf-android] Hard-cap full-content viewer. A
        // native TextView (selectable, scrollable) renders arbitrarily large
        // thinking text without the Compose `Text` measure freeze.
        if (overHardCap && showFullContent) {
            ThinkingFullContentDialog(
                content = block.content,
                onDismiss = { showFullContent = false },
            )
        }
    }
}

/**
 * [T-thinking-render-perf-android] Full-screen viewer for thinking content
 * that exceeds the inline hard cap. Wraps a native Android [android.widget.TextView]
 * (inside a scroller) — it lays out very large strings far more cheaply than
 * Compose `Text`, and stays selectable.
 */
@Composable
internal fun ThinkingFullContentDialog(content: String, onDismiss: () -> Unit) {
    val textColor = MaterialTheme.colorScheme.onSurface
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Deep Thinking",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF007AFF),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    MinisTextButton(onClick = onDismiss) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx ->
                        android.widget.ScrollView(ctx).apply {
                            addView(
                                android.widget.TextView(ctx).apply {
                                    textSize = 13f
                                    setTextColor(textColor.toArgb())
                                    setTextIsSelectable(true)
                                    setPadding(36, 24, 36, 48)
                                    text = content
                                }
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/**
 * [feat/ui-rikkahub] Ghost action row under a completed assistant reply —
 * copy / regenerate / more. Long-press on AI text stays with text selection
 * (the SelectionContainer owns that gesture and Compose offers no way to
 * re-enter selection programmatically), so message-level actions surface
 * here instead — the same division of labour as mainstream chat clients: your own
 * bubbles keep the long-press menu, replies wear a visible row.
 */
