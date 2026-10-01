package com.openminis.app.ui.chat

// [T-android-split-chat] Assistant-message + tool-pill + thinking rendering
// extracted verbatim from ChatScreen.kt: AssistantHeader, AssistantMessageView,
// BoundsTrackedBlock, InlineErrorBanner, ToolStopButton,
// formatToolDetailsForClipboard, ToolCallPill, ThinkingBlock.
// Full import block copied (unused=warnings); externally-called ones internal.

import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import novex.android.ui.DropdownMenu
import novex.android.ui.DropdownMenuItem
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.ui.components.MinisMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.ui.theme.ChatColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun ToolStopButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.stop_tool)
    // Outer Box keeps the *layout* footprint at 18×18 (unchanged capsule width).
    // The inner clickable Box is 24×24 and overflows the outer bounds equally on
    // all sides (requiredSize ignores the parent's 18dp constraint), enlarging the
    // touch target to 24 while the visual 10×10 red square stays identical.
    Box(
        modifier = modifier.size(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .requiredSize(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    onClickLabel = label,
                    onClick = onStop,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFFF3B30)),
            )
        }
    }
}

// ─── Tool Call Capsule (iOS: Capsule(), inline, tool-colored icon + title + duration) ─

/**
 * [T-android-tool-bubble-longpress-menu] Render a tool_use block as a
 * human-readable, paste-back-friendly clipboard string: the tool call
 * (name + id + pretty-printed input JSON) followed by the tool result
 * (char count + status + the result text). Input JSON is pretty-printed
 * when it parses as a JSON object/array; otherwise it's emitted verbatim
 * so a malformed / partial args string still copies usefully.
 */
internal fun formatToolDetailsForClipboard(block: AssistantBlock): String {
    val prettyInput = run {
        val raw = block.toolArgs
        if (raw.isBlank()) return@run "(none)"
        try {
            when (raw.trimStart().firstOrNull()) {
                '{' -> org.json.JSONObject(raw).toString(2)
                '[' -> org.json.JSONArray(raw).toString(2)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }
    val statusLabel = when (block.toolStatus) {
        ToolBlockStatus.SUCCESS -> "success"
        ToolBlockStatus.FAILED -> "error"
        ToolBlockStatus.TIMEOUT -> "timeout"
        ToolBlockStatus.CANCELLED -> "cancelled"
        ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING -> "running"
        null -> "unknown"
    }
    val resultText = block.content
    return buildString {
        append("## Tool Call\n")
        append("name: ").append(block.toolName).append('\n')
        append("id: ").append(block.id).append('\n')
        append("input:\n").append(prettyInput).append('\n')
        append('\n')
        append("## Tool Result\n")
        append("(").append(resultText.length).append(" chars, ").append(statusLabel).append(")\n")
        if (resultText.isNotEmpty()) append(resultText)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolCallPill(
    block: AssistantBlock,
    allToolBlocks: List<AssistantBlock> = listOf(block),
    onRetry: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null,
    // T261: detail open routes through ChatViewModel so the sheet survives
    // LazyColumn item disposal. Default no-op for the legacy
    // AssistantMessageView call site (currently dead code).
    onOpenDetail: (String) -> Unit = {},
    // [T-android-tool-bubble-longpress-menu] Long-press actions. Null
    // disables the corresponding menu item (e.g. re-run is null while
    // streaming or when there's no preceding user turn to re-run from).
    onRerunFromHere: (() -> Unit)? = null,
    onCopyDetails: (() -> Unit)? = null,
    // [feat/ui-rikkahub] ChainOfThought connector control: draw the top stub
    // unless this is the turn's first step, bottom stub unless it's final.
    isFirst: Boolean = false,
    isLast: Boolean = false,
) {
    // T-android-jank-profile: this log was firing on every ToolCallPill
    // recomposition (every streaming token while a tool call is live),
    // showing up as 1.6% main thread time in profiles. Logs at composable
    // top level multiply with the number of pills × recompose rate. Gate
    // behind BuildConfig.DEBUG so production builds skip the string-build
    // entirely, and the rest of release builds don't pay for it.
    if (com.openminis.app.BuildConfig.DEBUG && false) {
        android.util.Log.d("ToolChain[UI]", "ToolCallPill render: id=${block.id} name=${block.toolName} title=${block.toolTitle} status=${block.toolStatus} contentLen=${block.content.length} argsLen=${block.toolArgs.length}")
    }

    // PENDING shares RUNNING's spinner affordance — tool JSON is received but
    // execution hasn't flipped the block to RUNNING yet (brief gap). TIMEOUT
    // shares FAILED's error styling but the icon mapping distinguishes them.
    val isRunning = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING
    val isDone = block.toolStatus == ToolBlockStatus.SUCCESS
    val isFailed = block.toolStatus == ToolBlockStatus.FAILED ||
        block.toolStatus == ToolBlockStatus.TIMEOUT
    val isCancelled = block.toolStatus == ToolBlockStatus.CANCELLED

    val toolAccent = toolAccentColor(block.toolName)
    val toolIcon = toolIconFor(block.toolName)

    // Icon color: tool color when running/done, error/cancel colors on failure
    val iconTint = when {
        isFailed -> ToolErrorColor
        isCancelled -> ToolCancelColor
        // [feat/ui-rikkahub] done/running stay muted like the thinking node;
        // only failure states keep their loud colors for glanceability.
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // iOS: always shows tool-type icon, only changes color based on status
    val displayIcon = toolIcon

    // Duration text (iOS: "0.4s" format)
    val durationText = if (block.durationMs > 0 && !isRunning) {
        val seconds = block.durationMs / 1000.0
        if (seconds < 10) String.format("%.1fs", seconds)
        else String.format("%.0fs", seconds)
    } else null

    // T125: the iOS shimmer sweep died with the capsule pill
    // ([feat/ui-rikkahub] timeline step). Running state now reads from the
    // bouncing dots + muted label, same as mainstream chat clients' tool steps.

    // [T-android-tool-bubble-longpress-menu] Long-press menu state, scoped
    // to this pill. The DropdownMenu is anchored to the pill via the Box
    // wrapper below so it opens beneath the tapped bubble.
    var showToolMenu by remember { mutableStateOf(false) }

    // [T-live-tool-tail]（2026-09-17 用户批：参照 dsh/codex 的工具调用显示
    // 流程）执行中的工具行下方挂暗色等宽小字尾巴：参数流式期显示累积参数
    // 尾部（bulk 工具解析最近模块名），执行期优先显示输出尾部——长静默轮
    // 也有"正在写什么"的持续反馈。完成后尾巴随折叠消失。
    val liveTailSource = when {
        !isRunning -> null
        block.toolStatus == ToolBlockStatus.RUNNING && block.content.isNotBlank() -> block.content
        else -> block.toolArgs
    }
    val liveTail = liveTailSource?.let { ToolLiveTail.liveTail(block.toolName, it) }

    // [feat/ui-rikkahub] 方案 B：工具行无负载展示（时间线步骤行）；运行中
    // 的 live tail 保留（上游 T-live-tool-tail），渲染在步骤行下方。
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    Column(modifier = Modifier.fillMaxWidth()) {
      Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .drawBehind {
                    // [feat/ui-rikkahub] ChainOfThought connector stubs —
                    // same left-edge rail (x=8dp) as the thinking step.
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
                .combinedClickable(
                    onClick = {},
                    onLongClick = if (onRerunFromHere != null || onCopyDetails != null) {
                        { showToolMenu = true }
                    } else null,
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.Center) {
                // Status icon — always the typed tool icon. Color shifts to
                // reflect terminal status (failed/cancelled keep loud colors);
                // done/running are muted like the ChainOfThought nodes.
                Icon(
                    displayIcon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(14.dp),
                )
            }

            // [T-step-timestamp v2 aa8b1128] Inline HH:mm:ss prefix removed
            // — user found it visually noisy on every tool pill. Start
            // time + elapsed duration now live in the tool detail bottom
            // sheet header instead (ToolDetailSheet, this file ~line
            // 5209). formatStepTimestamp() is still defined further down
            // because the detail sheet calls it.

            // Tool title + streaming dots after title (iOS: Text + bouncing "...").
            // weight(1f) lets the title absorb leftover width, ellipsis trims overflow.
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.toolTitle.ifEmpty { block.toolName },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isRunning) {
                    // iOS streaming: "..." bouncing dots after title text
                    StreamingDotsText()
                }
            }

            // Duration badge (iOS: monospaced gray text after title) — fixed width,
            // never compressed by the title. The HH:mm:ss start time is
            // surfaced inside the tool detail bottom sheet's bottom bar
            // instead of here — the inline pill list stays clean.
            if (durationText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = durationText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    softWrap = false,
                    maxLines = 1,
                )
            }
            // T168: restore per-tool stop button (reverts T117). Renders only
            // for running/streaming/pending blocks so completed pills stay
            // clean. Routes to the same global cancelStream() — there is no
            // per-tool cancellation API on either platform.
            if (isRunning && onStop != null) {
                Spacer(modifier = Modifier.width(8.dp))
                ToolStopButton(onStop = onStop)
            }
        }
        // [T-android-tool-bubble-longpress-menu] Long-press menu anchored to
        // the pill. Items mirror the user-bubble menu's style (MinisMenu +
        // DropdownMenuItem + leading icon). Each item no-ops gracefully if
        // its callback is null (re-run is gated while streaming / when no
        // preceding user turn exists).
        // [T-android-tool-menu-minwidth] Minimum width = min(220dp, screen
        // width) — the menu wants to be 220dp wide, but must never exceed the
        // device width on a narrow screen. screenWidthDp is the usable width in
        // dp; cap max to the same value so the widthIn(min,max) range is always
        // valid (min <= max) even on a sub-220dp display.
        val toolMenuWidthDp = minOf(220, LocalConfiguration.current.screenWidthDp).dp
        MinisMenu(
            expanded = showToolMenu,
            onDismissRequest = { showToolMenu = false },
            offset = androidx.compose.ui.unit.DpOffset(0.dp, 6.dp),
            modifier = Modifier.widthIn(max = toolMenuWidthDp),
            minWidth = toolMenuWidthDp,
        ) {
            if (onRerunFromHere != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_rerun_from_here)) },
                    onClick = { showToolMenu = false; onRerunFromHere() },
                    leadingIcon = { Icon(novex.android.ui.NovexIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            if (onCopyDetails != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_longpress_copy_details)) },
                    onClick = { showToolMenu = false; onCopyDetails() },
                    leadingIcon = { Icon(novex.android.ui.NovexIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
      }
        // T251: removed inline Retry affordance next to cancelled/failed pills —
        // the pill's own status icon (yellow on FAILED, gray on CANCELLED) is
        // already the unified failure tip. The button was visually noisy and
        // redundant. ToolCallPill keeps the `onRetry` parameter so upstream
        // callers don't need to change; the lambda just isn't surfaced inline
        // any more. retryLast() / retryFromMessage() remain reachable from
        // other entry points (long-press menu, etc.).
        // iOS: Spacer(minLength: 0) — pill stays content-width, not full-row-width
    }

    // [T-live-tool-tail] dsh/codex 式滚动尾巴：暗色等宽小字，与胶囊图标对齐。
    if (!liveTail.isNullOrEmpty()) {
        Text(
            text = liveTail,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 26.dp, bottom = 2.dp),
        )
    }

    generatedImageArtifact(block)?.let { artifact ->
        GeneratedImageArtifactCard(artifact)
    }
}

internal data class GeneratedImageBounds(val widthPx: Int, val heightPx: Int)

internal sealed interface GeneratedImageBoundsState {
    data object Loading : GeneratedImageBoundsState
    data object Unavailable : GeneratedImageBoundsState
    data class Available(val bounds: GeneratedImageBounds) : GeneratedImageBoundsState
}

internal fun readGeneratedImageBounds(file: File): GeneratedImageBounds? {
    if (!file.isFile || file.length() <= 0L) return null
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    return GeneratedImageBounds(options.outWidth, options.outHeight)
}

/**
 * Read only the local image header off the main thread, then make the visible
 * frame match that aspect ratio. The full-screen image viewer still owns
 * saving and sharing.
 */
@Composable
internal fun GeneratedImageArtifactCard(artifact: GeneratedImageArtifact) {
    val openImage = LocalMarkdownUrlClickHandler.current
    val imageFile = remember(artifact.filePath) { File(artifact.filePath) }
    val boundsState by produceState<GeneratedImageBoundsState>(
        initialValue = GeneratedImageBoundsState.Loading,
        key1 = artifact.filePath,
    ) {
        value = withContext(Dispatchers.IO) { readGeneratedImageBounds(imageFile) }
            ?.let(GeneratedImageBoundsState::Available)
            ?: GeneratedImageBoundsState.Unavailable
    }
    var imageLoadFailed by remember(artifact.filePath) { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val density = LocalDensity.current

    if (boundsState == GeneratedImageBoundsState.Loading) return
    if (boundsState == GeneratedImageBoundsState.Unavailable) {
        Text(
            text = stringResource(R.string.generated_image_unavailable),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 5.dp),
        )
        return
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        val sourceBounds = (boundsState as? GeneratedImageBoundsState.Available)?.bounds
            ?: return@BoxWithConstraints
        val frame = generatedImageFrame(
            maxWidthPx = constraints.maxWidth,
            maxHeightPx = with(density) { 420.dp.roundToPx() },
            imageWidthPx = sourceBounds.widthPx,
            imageHeightPx = sourceBounds.heightPx,
        ) ?: return@BoxWithConstraints
        val frameWidth = with(density) { frame.widthPx.toDp() }
        val frameHeight = with(density) { frame.heightPx.toDp() }

        Box(
            modifier = Modifier
                .width(frameWidth)
                .height(frameHeight)
                .clip(shape)
                .then(
                    if (imageLoadFailed) {
                        Modifier.background(ChatColors.toolCapsuleBg)
                    } else {
                        Modifier
                    },
                )
                .border(0.5.dp, ChatColors.toolBorder, shape)
                .clickable(enabled = !imageLoadFailed && openImage != null) {
                    openImage?.invoke(Uri.fromFile(imageFile).toString())
                },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = imageFile,
                contentDescription = artifact.title,
                contentScale = ContentScale.Fit,
                onSuccess = { imageLoadFailed = false },
                onError = { imageLoadFailed = true },
                modifier = Modifier.fillMaxSize(),
            )

            if (imageLoadFailed) {
                Text(
                    text = stringResource(R.string.generated_image_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

// iOS-style bouncing dots (3 dots, easeInOut, staggered delay)
// [T-android-split-chat] BouncingDots / StreamingDotsText / TypingIndicator
// moved verbatim to ChatIndicators.kt (same package, now `internal`).

// ─── Thinking Block (iOS: collapsible "Deep Thinking" section, blue tint) ────

