package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import novex.android.ui.Checkbox
import androidx.compose.foundation.clickable
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.widget.Toast
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
/**
 * Streaming-friendly markdown renderer.
 *
 * Strategy:
 * - Parse markdown into a list of Block objects
 * - Each block is an independent composable — Compose's structural diff only
 *   recomposes blocks that actually changed
 * - The LAST block is the only one that changes during streaming (text appends to it)
 * - Completed blocks above are structurally stable → Compose skips them
 *
 * Update cadence: while [isStreaming] is true, content updates are coalesced
 * to at most one re-parse every [STREAMING_THROTTLE_MS] (~120 ms). Pixel 4a
 * traces showed every TextDelta (~5 ms cadence) was triggering a full
 * `parseMarkdownBlocks` over the entire accumulating string + a recompose of
 * every RenderBlock — a 5-row markdown table plus a few tool calls was enough
 * to ANR the main thread with 22 MB GC every 2 s. Throttling the *display*
 * content (not the underlying StateFlow) keeps the conversation visually
 * live (3-4 fps of growth is plenty for reading) while leaving 90 % of the
 * frame budget free.
 *
 * When the stream finishes ([isStreaming] flips to false), the final value
 * is published immediately so the user never sees a truncated last frame.
 */
// Adaptive streaming throttle, mirrors iOS CollectionViewMessageListV3
// `flushStreamingLayout` (100 ms when auto-scrolling, 3 s when away). On
// Android we don't have direct access to the chat-level scroll state from
// here, so substitute "doc length" as a proxy: long documents already cost
// more per parse pass, so amortize them by sampling less often. Crashes
// observed on Pixel 6 traced to ICU `RegexPattern::matcher` allocations
// piling up under Scudo (OOM at ~140s of streaming) — slowing parses on
// large bodies cuts native allocation pressure dramatically.
//
// [T-android-stream-flush-dualpath] Time-throttle tiers ported verbatim from
// iOS AIChatViewModel+SSEStream (the `throttle` ladder): the time path is one
// half of the dual-path flush — the other half is the newline fast-path below.
// Tiers scale with total length to hold the Pixel 4a ANR / Pixel 6 Scudo-OOM
// line on dense streams while keeping short replies responsive.
//   < 500  : 200ms   < 2000 : 300ms   < 32K : 500ms
//   < 64K  : 1000ms  < 128K : 1500ms  else  : 2000ms
private fun streamingThrottleFor(content: String): Long = when {
    content.length < 500 -> 200L
    content.length < 2_000 -> 300L
    content.length < 32_000 -> 500L
    content.length < 64_000 -> 1_000L
    content.length < 128_000 -> 1_500L
    else -> 2_000L
}


@Composable
fun StreamingMarkdownText(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /** MinisTextKit shard id (see [MarkdownBlock]). */
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            StreamingMarkdownTextBody(content, isStreaming, modifier)
        }
        return
    }
    StreamingMarkdownTextBody(content, isStreaming, modifier)
}

@Composable
private fun StreamingMarkdownTextBody(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // While streaming, sample `content` at the adaptive throttle interval.
    // produceState + snapshotFlow.conflate() makes the upstream value collection
    // suspend-safe and frees the runtime to drop intermediate values when the
    // collector falls behind. When streaming ends, emit the final value
    // unconditionally so we don't render a stale half-block.
    val displayContent by produceState(initialValue = content, content, isStreaming) {
        if (!isStreaming) {
            value = content
            return@produceState
        }
        snapshotFlow { content }
            .conflate()
            .collect { latest ->
                value = latest
                delay(streamingThrottleFor(latest))
            }
    }
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm.
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(displayContent) {
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownBlocks(displayContent).also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        // If the LE was cancelled while parseMarkdownBlocks was still running
        // (a newer chunk arrived), don't publish stale blocks.
        coroutineContext.ensureActive()
        blocks = computed
    }

    ShardSubIndexScope {
        Column(modifier = modifier) {
            // [T-android-stream-fade] Last block during a live stream gets
            // LocalAppendOnlyFade=true so MdText fades in newly-appended
            // word ranges (mirrors iOS TextFadeAnimator). Every other block
            // — completed prefix, non-streaming sessions — renders opaque.
            val lastIdx = blocks.size - 1
            blocks.forEachIndexed { idx, block ->
                if (isStreaming && idx == lastIdx) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalAppendOnlyFade provides true,
                    ) { RenderBlock(block) }
                } else {
                    RenderBlock(block)
                }
            }
        }
    }
}

/**
 * T285-md: full-document markdown viewer for FilePreviewScreen and any
 * other "open a `.md` file end-to-end" surface. Differs from
 * [StreamingMarkdownText] in two important ways:
 *
 *  1. Renders blocks via [LazyColumn] instead of [Column]. A 200-block
 *     document only composes the on-screen blocks on first frame, so
 *     `parseInline`/`collectInlineMathLatex` (still main-thread per
 *     RenderBlock) costs scale with viewport height, not document
 *     length. Critical for the chat-tap → preview transition: pre-T285-md
 *     a multi-KB markdown ran ~150-300ms of inline scanning across all
 *     blocks during the same frame the navigation animation started,
 *     stuttering the slide-in. (StreamingMarkdownText still uses Column
 *     because chat-side messages live inside ChatScreen's outer
 *     LazyColumn — putting a LazyColumn-in-LazyColumn there would hit
 *     the "infinite vertical constraint" runtime error.)
 *
 *  2. No streaming throttle / snapshotFlow plumbing — the file is
 *     loaded once and the content never mutates after publication, so
 *     the live-tail logic in [StreamingMarkdownText] would just be
 *     overhead.
 *
 * Pass the outer scroll [Modifier] (height/padding) to this composable;
 * do NOT wrap the call site in a `verticalScroll` — the LazyColumn
 * provides the scroll itself.
 */
@Composable
fun MarkdownDocument(
    content: String,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm —
    // the doc viewer benefits the same way: per-block inline scans become
    // cache hits as blocks scroll into view.
    val mdColors = currentMdColors()
    var blocks by remember(content) { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(content) {
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownBlocks(content).also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        coroutineContext.ensureActive()
        blocks = computed
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        // Composite key: index disambiguates blocks with identical raw
        // bodies (multiple `---` HR lines, repeated empty paragraphs, etc.
        // would otherwise crash LazyColumn with "Key was already used"),
        // while raw still helps item reuse when the list is rebuilt with
        // the same content at the same position.
        itemsIndexed(blocks, key = { idx, b -> "$idx:${b.raw}" }) { _, block ->
            RenderBlock(block)
        }
    }
}

// ─── Block-level splitting (Pattern A: ChatGPT/Claude-style scroll stability) ─
//
// Earlier the entire streaming markdown was rendered inside a single
// LazyColumn item. When that item's height grew mid-stream, LazyList's
// per-item anchor couldn't help — the user's scroll position drifted as the
// internal Column reflowed. Splitting the message into one LazyColumn item
// per markdown block shifts the anchor granularity down: completed blocks
// (anything before the trailing fence/blank-line boundary) become frozen
// items whose visual position is preserved by LazyList; only the trailing
// "live" block can change height.
//
// `splitMarkdownIntoBlockTexts` returns ordered raw text fragments. The
// boundary rule is:
//   - blank line OUTSIDE a fenced code block → split (paragraph end)
//   - fenced code block start/end → its own fragment
// Fence-internal blank lines never split. Tables and HR-only lines stay
// attached to their preceding/following fragment because the parser
// detects them at parse time anyway.

/**
 * Split a streaming markdown buffer into ordered raw-text fragments at
 * stable boundaries. Each fragment is suitable as the input to a
 * standalone [MarkdownBlock] composable. Concatenating the returned list
 * with "\n" reconstructs the input exactly.
 */
fun splitMarkdownIntoBlockTexts(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inFence = false
    val lines = content.lines()
    fun flush() {
        if (cur.isNotEmpty()) {
            // Trim trailing empty line we used as boundary, but keep
            // intentional internal newlines.
            out.add(cur.toString().trimEnd('\n'))
            cur.clear()
        }
    }
    for (line in lines) {
        val trimmed = line.trimStart()
        val isFence = trimmed.startsWith("```")
        if (isFence) {
            // A fence line both closes the previous fragment (when we're
            // not inside a fence) and opens/closes the fence fragment.
            if (!inFence) {
                flush()
                cur.append(line).append('\n')
                inFence = true
            } else {
                cur.append(line).append('\n')
                inFence = false
                flush()
            }
            continue
        }
        if (inFence) {
            cur.append(line).append('\n')
            continue
        }
        if (line.isBlank()) {
            // Boundary: paragraph end. Drop the blank line itself; it
            // signals the split.
            flush()
            continue
        }
        cur.append(line).append('\n')
    }
    flush()
    return out
}

/**
 * [T-android-defensive-fragment-merge] A fenced code block fragment is one
 * whose first non-blank line opens a ``` fence. Such fragments must stay
 * standalone (own LazyColumn row) for correct code rendering + horizontal
 * scroll, so coalescing never merges across them.
 */
private fun isFenceFragment(fragment: String): Boolean {
    val firstLine = fragment.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    return firstLine.trimStart().startsWith("```")
}

/**
 * [T-android-defensive-fragment-merge] Coalesce the per-paragraph fragments
 * produced by [splitMarkdownIntoBlockTexts] into fewer, larger fragments so
 * a long frozen assistant message becomes a handful of LazyColumn rows
 * instead of dozens.
 *
 * Why: each fragment is its own LazyColumn item carrying its own
 * BoundsTrackedBlock + MarkdownBlock + per-item Compose state. A dense
 * assistant reply (e.g. a 50-item list with blank lines) fans out into ~50
 * rows; a long session reaches several thousand rows, which on low-memory
 * devices contributes to a GC storm on cold-open full-build. Re-joining
 * adjacent plain-text fragments with their original blank-line separator
 * (`\n\n`) keeps the rendered markdown identical — MarkdownBlock re-parses
 * the joined text the same way it would parse them separately — while
 * cutting the row count ~8x.
 *
 * Rules:
 *   - Code-fence fragments are NEVER merged (kept standalone for syntax
 *     highlight + horizontal scroll). They flush the current accumulator
 *     and emit on their own.
 *   - Plain fragments accumulate until adding the next would exceed
 *     [maxChars]; then the accumulator flushes and a new one starts. This
 *     caps any single merged row's height so the streaming/scroll anchor
 *     granularity stays reasonable.
 *   - Joining uses `\n\n` so paragraph boundaries survive the round-trip.
 *
 * Callers should only apply this to FROZEN (non-streaming) messages — the
 * live streaming tail keeps fine-grained fragments so only the trailing
 * paragraph re-parses per token (Pattern A jank optimization).
 */
fun coalesceMarkdownFragments(fragments: List<String>, maxChars: Int = 2000): List<String> {
    if (fragments.size <= 1) return fragments
    val out = ArrayList<String>(fragments.size)
    val acc = StringBuilder()
    fun flush() {
        if (acc.isNotEmpty()) {
            out.add(acc.toString())
            acc.setLength(0)
        }
    }
    for (frag in fragments) {
        if (isFenceFragment(frag)) {
            flush()
            out.add(frag)
            continue
        }
        // Would appending this fragment overflow the budget? Flush first,
        // unless the accumulator is empty (a single oversized paragraph
        // still gets its own row rather than being dropped).
        if (acc.isNotEmpty() && acc.length + 2 + frag.length > maxChars) {
            flush()
        }
        if (acc.isNotEmpty()) acc.append("\n\n")
        acc.append(frag)
    }
    flush()
    return out
}

/**
 * Render a single markdown fragment (one or a few related blocks) inside
 * its own composable. Designed to be used as the body of an independent
 * LazyColumn item — each fragment is one item, so its height changes
 * cannot disturb the scroll position of any other fragment.
 *
 * `isStreaming` controls async re-parse: when false (frozen completed
 * fragment), the parse runs once on first composition and is never
 * recomputed. When true (the trailing live fragment), the parse is
 * re-run on every content tick, mirroring the original
 * StreamingMarkdownText behavior.
 */
@Composable
fun MarkdownBlock(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /**
     * MinisTextKit shard id — when supplied, every MdText composed beneath
     * this fragment will register with the ambient [SelectionController].
     * The id should be stable across recompositions so the controller's
     * registry doesn't churn (e.g. "msg:abc:block:7"). Null = participate
     * in no selection (legacy behavior).
     */
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            // [T-android-markdown-longtext-selection-broken] Disambiguate the
            // several MdTexts a multi-block fragment renders under this one
            // shardId so each registers its own shard.
            ShardSubIndexScope {
                MarkdownBlockBody(rawText, isStreaming, modifier)
            }
        }
        return
    }
    MarkdownBlockBody(rawText, isStreaming, modifier)
}

@Composable
private fun MarkdownBlockBody(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // For frozen blocks, parse once per distinct fragment text PROCESS-WIDE
    // ([MarkdownParseCaches.blocks]) — scroll-away/return and session re-entry
    // are cache hits instead of fresh main-thread parses. remember() keeps the
    // per-composition lookup free.
    //
    // [T-android-coldload-offmain-parse] Cold-load split: a cache HIT (or a
    // small fragment) still renders synchronously — no flicker on
    // scroll-back / re-entry, and small parses are sub-ms. A cache MISS on a
    // BIG fragment must NOT parse in composition: on session open the
    // viewport's fragments all miss at once and the synchronous
    // parseMarkdownBlocksBlocking + inline scans froze the main thread for
    // seconds (tester log: 3.5–8.5s on a 33K-char message; the streaming
    // breaker/degrade only cover isStreaming=true). Those parse off-main
    // with a bounded plain-text preview in the meantime — same structure as
    // the live branch below.
    if (!isStreaming) {
        val cached = remember(rawText) { MarkdownParseCaches.cachedBlocks(rawText) }
        // [T-android-longtext-anr] Synchronous render ONLY on a real cache HIT.
        // The old `|| rawText.length <= COLD_PARSE_OFFMAIN_THRESHOLD_CHARS` clause
        // let a small fragment parse (block-split + per-block inline regex) on the
        // main thread during composition. Individually sub-ms, but on session open
        // the first screen holds ~20 messages split into dozens of small fragments;
        // when the parallel viewport prewarm (ChatScreen) loses the race, every one
        // of those misses parsed synchronously in the same frame and the aggregate
        // froze the main thread for 30s+ → ANR (minis-2026-07-09-anr.log: all hang
        // stacks in Matcher/Pattern via the inline parser, right after first compose).
        // A cold MISS now always goes off-main with a plain-text preview, bounding
        // the first-frame main-thread cost to cheap Text layouts regardless of how
        // many fragments miss at once. Cache HITs (scroll-back, re-entry, prewarmed
        // rows) stay synchronous and flicker-free.
        if (cached != null) {
            Column(modifier = modifier) {
                cached.forEach { RenderBlock(it) }
            }
            return
        }
        val mdColors = currentMdColors()
        var parsed by remember(rawText) { mutableStateOf<List<MdBlock>?>(null) }
        LaunchedEffect(rawText) {
            val tStartNs = System.nanoTime()
            val computed = withContext(Dispatchers.Default) {
                MarkdownParseCaches.blocks(rawText).also {
                    MarkdownParseCaches.prewarm(it, mdColors)
                }
            }
            coroutineContext.ensureActive()
            parsed = computed
            com.openminis.app.logging.AppLogger.info(
                "Perf",
                "[Perf][ColdParse] step=coldParse.offmain chars=${rawText.length} " +
                    "blocks=${computed.size} parseMs=${(System.nanoTime() - tStartNs) / 1_000_000}",
            )
        }
        val blocks = parsed
        Column(modifier = modifier) {
            if (blocks == null) {
                // Bounded plain-text preview while the off-main parse runs —
                // one cheap Text layout, no markdown/regex/AnnotatedString.
                Text(
                    text = rawText.take(COLD_PARSE_PREVIEW_CHARS),
                    fontSize = BaseFontSize,
                    lineHeight = BaseLineHeight,
                    color = currentMdColors().text,
                )
            } else {
                blocks.forEach { RenderBlock(it) }
            }
        }
        return
    }
    // [T-android-live-block-degrade] B-lite: a LIVE fragment that has grown
    // huge is almost always an unsplittable single block (the splitter keeps
    // tables/fences whole — exactly the MiniMax giant-table ANR load). Parsing
    // it in full on every publish is O(fragment) with no upper bound, so over
    // the threshold render a bounded plain-text tail instead and do the full
    // parse ONCE when the fragment freezes (isStreaming flips false above).
    if (rawText.length > LIVE_FRAGMENT_DEGRADE_CHARS) {
        Column(modifier = modifier) {
            Text(
                text = stringResource(R.string.chat_stream_degraded_notice),
                style = MaterialTheme.typography.labelSmall,
                color = currentMdColors().blockquote,
            )
            Text(
                text = "…" + rawText.takeLast(LIVE_FRAGMENT_TAIL_CHARS),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = currentMdColors().text,
            )
        }
        return
    }
    // Live (streaming tail) block.
    //
    // [T-android-stream-flush-dualpath] Throttling moved UP to the message
    // accumulation layer (ChatViewModel.updateAssistantMessage) where the
    // dual-path (time OR newline+chars) flush actually accumulates across the
    // high-frequency token calls. The earlier per-fragment throttle here was
    // structurally broken: streaming text is split into many short-lived
    // fragment items, so this produceState (and its lastFlushMs accumulator)
    // reset on every fragment rebuild and never throttled at all — diagnostics
    // showed every tick flushing. `rawText` arriving here is already paced by
    // the VM, so the fragment just renders it directly; parse stays off-main
    // below.
    val displayContent by produceState(initialValue = rawText, rawText) {
        snapshotFlow { rawText }.conflate().collect { value = it }
    }
    // [T-android-inline-parse-offmain] Snapshot the theme colors in
    // composition so the Default-thread parse below can PREWARM the inline
    // caches with the exact keys RenderBlock will look up — main-thread
    // composition of the live block becomes a pure cache hit.
    val mdColors = currentMdColors()
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(displayContent) {
        // [T-android-stream-render-profile] Time the whole off-main tick
        // (block split + prewarm/incremental inline+math) — this is what the
        // incremental optimization shrinks.
        val parseStartNs = System.nanoTime()
        val computed = withContext(Dispatchers.Default) {
            parseMarkdownBlocks(displayContent).also {
                // [T-android-streaming-incremental-inline] Prewarm the frozen
                // blocks (all but the last) normally. The last block is the
                // growing live tail: when it's a Paragraph, warm it
                // incrementally (closed prefix reused + tiny fresh suffix) so
                // the main-thread RenderBlock resolves to an exact HIT without
                // re-scanning the whole accumulated paragraph; when it's a
                // table/list/etc. (which RenderBlock parses non-incrementally)
                // fall back to the normal per-block prewarm for it.
                if (it.size > 1) MarkdownParseCaches.prewarm(it.dropLast(1), mdColors)
                if (it.lastOrNull() is MdBlock.Paragraph) {
                    MarkdownParseCaches.prewarmLiveTail(it, mdColors)
                } else {
                    it.lastOrNull()?.let { last -> MarkdownParseCaches.prewarm(listOf(last), mdColors) }
                }
                // [T-android-review-p1-fixes] F2(a): deposit the live parse
                // into the blocks cache so the freeze edge (isStreaming →
                // false recomposes into the frozen branch with this exact
                // text) HITs synchronously — no plain-text preview flash, no
                // off-main re-parse. Only for segments big enough to take
                // the off-main MISS path at freeze; small ones parse sub-ms
                // synchronously anyway, and skipping them keeps live ticks
                // from churning the LRU.
                if (displayContent.length > COLD_PARSE_OFFMAIN_THRESHOLD_CHARS) {
                    MarkdownParseCaches.putBlocks(displayContent, it)
                }
            }
        }
        coroutineContext.ensureActive()
        StreamRenderProfiler.recordParse(displayContent.length, (System.nanoTime() - parseStartNs) / 1_000_000.0)
        blocks = computed
    }
    // [T-android-stream-grow-anim] No height/scroll animation here. We tried
    // animateContentSize to ease the bottom-pinned item's exposed height into a
    // smooth viewport follow, but diagnostics showed the live fragment's
    // composable identity is NOT stable across parse ticks (markdown re-blocks
    // every tick — blocks.size flips 1↔2, last-block position churns), so the
    // animation reset to initialH=0 almost every tick and "popped from zero"
    // instead of gliding, AND dragged single-frame cost to ~750–950ms (Davey).
    // Net regression. The smooth feel comes from reverseLayout's native bottom
    // pin (no jump, no extra layout cost) plus the per-word fade. A genuine
    // iOS-style continuous flow would require token-incremental rendering of the
    // streaming tail, not a height animation on an unstable item.
    Column(modifier = modifier) {
        // Wrap the last block in LocalAppendOnlyFade=true so MdText fades in
        // newly-appended words. [T-android-streaming-incremental-inline] Also
        // flag it as the LIVE tail so its Paragraph inline/math parse goes
        // through the incremental (frozen-prefix + fresh-suffix) path — this is
        // the only block whose raw grows every tick.
        val lastIdx = blocks.size - 1
        blocks.forEachIndexed { idx, block ->
            if (idx == lastIdx) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalAppendOnlyFade provides true,
                    LocalLiveIncremental provides true,
                ) { RenderBlock(block) }
            } else {
                RenderBlock(block)
            }
        }
    }
}

/**
 * [T-android-live-block-degrade] A LIVE fragment larger than this renders as a
 * bounded plain-text tail until it freezes. 8KB of markdown in one unsplit
 * block is far beyond normal prose paragraphs — only giant tables/fences get
 * here, and those were the per-tick full-re-parse ANR load.
 */
internal const val LIVE_FRAGMENT_DEGRADE_CHARS = 8_000
internal const val LIVE_FRAGMENT_TAIL_CHARS = 3_000

/**
 * [T-android-coldload-offmain-parse] A FROZEN fragment above this size whose
 * block parse would be a cache MISS parses off-main (with a plain-text
 * preview in the meantime) instead of synchronously in composition. Below
 * it the parse is sub-ms and the placeholder swap would flicker for nothing.
 */
internal const val COLD_PARSE_OFFMAIN_THRESHOLD_CHARS = 2_000
internal const val COLD_PARSE_PREVIEW_CHARS = 4_000

/**
 * [T-android-coldload-offmain-parse] Composition-snapshot prewarmer for the
 * chat flatten pipeline: returns a thread-safe lambda that block-parses each
 * raw fragment AND prewarms the inline/math caches with the palette captured
 * here. Lets ChatScreen (which cannot see the file-private MdBlock/MdColors
 * types) warm the exact keys RenderBlock will look up, off-main, before the
 * viewport rows first compose.
 */
@Composable
internal fun rememberMarkdownPrewarmer(): (List<String>) -> Unit {
    val mdColors = currentMdColors()
    return remember(mdColors) {
        { raws: List<String> ->
            for (raw in raws) {
                MarkdownParseCaches.prewarm(MarkdownParseCaches.blocks(raw), mdColors)
            }
        }
    }
}

