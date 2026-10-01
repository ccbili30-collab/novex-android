package com.openminis.app.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

// 选区手势层：长按命中已注册 TextShard → 词选择 → 拖拽扩展 end 端点；
// SelectionDragTracker 把 dragIntent 翻成实际选区更新并驱动边缘自动滚动
// （帧率 tick，与指针事件解耦）。
/**
 * Long-press + drag selection gesture for MinisTextKit. Mounted on the
 * scroll surface (the LazyColumn modifier) so the gesture handler receives
 * raw pointer events before LazyColumn's own scroll handler.
 *
 * Behavior:
 *  - Long-press over a registered text shard: clear existing selection,
 *    begin a new one collapsed at the hit-tested character.
 *  - Drag after long-press: continuously extend the selection's `end`
 *    endpoint to the current finger position. Near the viewport edge, the
 *    handler also nudges [listState] so the user can extend selection past
 *    the visible content.
 *  - Tap (down → up without long-press / drag) anywhere over a registered
 *    shard: clear the selection. Tap on non-shard area: ignore (the gesture
 *    propagates to children — LazyColumn scroll continues working normally).
 *
 * The handler intentionally does NOT consume the initial Down event: that
 * lets LazyColumn's flingable still receive scroll gestures that don't turn
 * into a long-press. Only once the long-press fires do we begin consuming.
 */
fun Modifier.minisTextKitSelectionGesture(
    controller: SelectionController,
    listState: LazyListState,
    rootCoordinates: () -> LayoutCoordinates?,
    /** Set true when the LazyColumn uses `reverseLayout = true` (chat lists). */
    reverseLayout: Boolean = false,
    onLongPressEngaged: () -> Unit = {},
): Modifier = composed {
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    pointerInput(controller, listState, reverseLayout) {
    val longPressTimeoutMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
    val touchSlopPx = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val downLocal = down.position
        var lastWindowPoint = localToWindow(downLocal, rootCoordinates)

        // ── Handle-drag short-circuit ────────────────────────────────────
        // If a selection already exists and the user pressed within reach
        // of one of its endpoint handles, skip the long-press wait and go
        // straight to handle-drag mode. This is what makes the existing
        // selection feel "grippable" — without it the user would have to
        // long-press inside the selection again before they could resize.
        // Handle grabs are detected by the handle Popups themselves (their
        // own pointerInput consumes the DOWN before it propagates here, so
        // a press inside a popup never reaches the LazyColumn). No
        // short-circuit needed in this gesture.

        // ── Phase 1: wait for the long-press timeout ─────────────────────
        val abortReason = withTimeoutOrNull(longPressTimeoutMs) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "lost"
                val pos = change.position
                lastWindowPoint = localToWindow(pos, rootCoordinates)
                if (!change.pressed) {
                    // Tap — clear selection if it exists and the tap landed
                    // directly inside a registered shard. Use STRICT so a
                    // tap on a user bubble / padding doesn't dismiss the
                    // active selection on an adjacent assistant message.
                    if (controller.selection.value != null &&
                        controller.hitTestStrict(lastWindowPoint) != null
                    ) {
                        controller.clearSelection()
                    }
                    return@withTimeoutOrNull "tap"
                }
                if ((pos - downLocal).getDistance() >= touchSlopPx) {
                    return@withTimeoutOrNull "scroll"
                }
            }
            @Suppress("UNREACHABLE_CODE") "unreached"
        }
        if (abortReason != null) return@awaitEachGesture

        // ── Engage selection: snap to the word under the finger ─────────
        // Use the STRICT hit-test (no nearest-shard fallback) so a long-
        // press inside a non-registered region — a user message bubble,
        // a tool call pill, an empty padding area — does NOT erroneously
        // snap to whatever assistant shard happens to be closest. The
        // press still flows through to other gesture handlers (e.g. the
        // user bubble's own long-press → action menu).
        val hit = controller.hitTestStrict(lastWindowPoint) ?: return@awaitEachGesture
        controller.beginSelectionWord(hit)
        // Fired HERE, not at the long-press timeout: the strict hit-test above
        // returns null over a non-selectable region, and buzzing before it
        // would give feedback for a selection that never happened. This is
        // also why it lives in the gesture rather than at the call site —
        // `onLongPressEngaged` has a no-op default that every caller was
        // taking, so text selection had no haptic at all while every other
        // long-press menu in the app did.
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        onLongPressEngaged()
        // Take ownership so LazyColumn doesn't reinterpret subsequent motion
        // as a scroll.
        down.consume()

        // ── Phase 2: drag extends the END endpoint past the word ────────
        // Don't publish dragIntent until the finger actually moves past
        // touchSlop. The initial position equals the long-press point,
        // which we already used to snap to a word — if we published it
        // immediately the DragTracker would re-hit-test at that point and
        // collapse the selection back onto the original character offset.
        var dragStarted = false
        try {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed) return@awaitEachGesture
                val movedPx = (change.position - downLocal).getDistance()
                if (!dragStarted && movedPx < touchSlopPx) {
                    // Still stationary — leave the word selection alone.
                    change.consume()
                    continue
                }
                dragStarted = true
                lastWindowPoint = localToWindow(change.position, rootCoordinates)
                controller.dragIntent.value = SelectionController.DragIntent(
                    point = lastWindowPoint,
                    handle = SelectionController.Handle.End,
                )
                // [T-android-text-selection-scroll-jitter] Edge auto-scroll
                // is now driven exclusively by the [SelectionDragTracker]
                // tick loop (frame-rate ticker). Calling it from here on
                // every pointer event stacked two nudges per frame and
                // amplified the feedback loop that made the top-edge handle
                // drag jitter and stall on the upward direction.
                change.consume()
            }
        } finally {
            controller.dragIntent.value = null
        }
    }
    }
}
/**
 * Edge-distance touch slop, px. Bigger → handles are easier to grab.
 * Pixel 4a is ~2.625x density; 96 px ≈ 36 dp, a generous thumb pad. The
 * grabHandleAt() check further multiplies by 1.5x so the effective grab
 * radius is ~54 dp around the dot center.
 */
private const val HANDLE_TOUCH_SLOP_PX = 96f

/** Edge auto-scroll zone, px. Within this many px of the viewport edge a drag triggers a nudge. */
private const val EDGE_AUTO_SCROLL_ZONE_PX = 80f

/** Max nudge per pointer event when finger is at the very edge. */
private const val EDGE_AUTO_SCROLL_MAX_PX = 14f

private fun edgeAutoScrollNudge(
    localY: Float,
    viewportH: Int,
    listState: LazyListState,
    reverseLayout: Boolean,
) {
    // Sign meaning here is "viewport reveals content in the direction the
    // finger is pulling": at the BOTTOM edge the user wants to see content
    // BELOW the viewport, at the TOP edge content ABOVE.
    //
    // For a normal LazyColumn that maps to:
    //   bottom edge → scrollBy(+) (move viewport down)
    //   top edge    → scrollBy(-)
    // For a reverseLayout=true LazyColumn (chat, index 0 at the bottom),
    // dispatchRawDelta's sign is FLIPPED relative to user intent:
    //   bottom edge → scrollBy(-) (reveal newer / lower-index items)
    //   top edge    → scrollBy(+)
    val rawMagnitude = when {
        localY < EDGE_AUTO_SCROLL_ZONE_PX ->
            -((EDGE_AUTO_SCROLL_ZONE_PX - localY) / EDGE_AUTO_SCROLL_ZONE_PX) * EDGE_AUTO_SCROLL_MAX_PX
        localY > viewportH - EDGE_AUTO_SCROLL_ZONE_PX ->
            ((localY - (viewportH - EDGE_AUTO_SCROLL_ZONE_PX)) / EDGE_AUTO_SCROLL_ZONE_PX) * EDGE_AUTO_SCROLL_MAX_PX
        else -> 0f
    }
    if (rawMagnitude == 0f) return
    val signed = if (reverseLayout) -rawMagnitude else rawMagnitude
    listState.dispatchRawDelta(signed)
}

/**
 * Side-effect that translates [SelectionController.dragIntent] into actual
 * selection updates. Runs in a coroutine OUTSIDE the restricted pointerInput
 * scope so it can compose hit-test reads with listState scroll observation:
 * whenever EITHER the finger position changes OR the LazyColumn scrolls,
 * we re-hit-test and update the corresponding selection endpoint.
 *
 * Without this indirection a stationary finger inside the auto-scroll edge
 * zone would "lose" the selection — pointer events stop firing, so the
 * inline hit-test in the gesture loop never re-runs against shards that
 * just scrolled into view. Mount this once near the top of the screen
 * that owns the SelectionController.
 */
@Composable
fun SelectionDragTracker(
    controller: SelectionController,
    listState: LazyListState,
    listRootCoordinates: () -> LayoutCoordinates?,
    reverseLayout: Boolean = false,
) {
    // [T-android-text-selection-scroll-jitter] Two responsibilities split
    // into two LaunchedEffects:
    //
    // 1) Hit-test re-run on every dragIntent change so the grabbed
    //    endpoint stays under the finger as it moves OR as auto-scroll
    //    shifts content underneath. Triggered by snapshotFlow on
    //    dragIntent.value.
    //
    // 2) Edge auto-scroll driven by a frame-rate tick loop while a drag
    //    is active. Pre-fix, the nudge was called both from the gesture
    //    handlers (every pointer event) AND from inside the hit-test
    //    collect block above. That stacked two nudges per pointer event
    //    AND coupled selection layout recompose to scroll dispatch, which
    //    created a feedback loop on the upward-direction drag: scroll
    //    shifted content under the handle → next pointer event hit-tested
    //    against a new shard → dragIntent updated → another nudge piled
    //    on → handle position visually jumped → user micro-corrected →
    //    new pointer event → repeat. Symptom was "constant jitter + cannot scroll up"
    //    (TG35696-35699 LeeeSe/𝙓𝙄𝙉).
    //
    // The tick loop reads the latest dragIntent point each frame
    // independently of pointer events, so a stationary finger inside the
    // edge zone still drives the list AND the nudge cadence is fixed at
    // ~60Hz regardless of how often the OS delivers MOVE events.
    LaunchedEffect(controller, listState, reverseLayout) {
        androidx.compose.runtime.snapshotFlow { controller.dragIntent.value }
            .collect { intent ->
                if (intent == null) return@collect
                val target = controller.hitTest(intent.point) ?: return@collect
                when (intent.handle) {
                    SelectionController.Handle.Start -> controller.replaceStart(target)
                    SelectionController.Handle.End -> controller.replaceEnd(target)
                }
            }
    }

    // Frame-rate edge auto-scroll ticker. Activated while dragIntent is
    // non-null and finger is inside the top/bottom edge zone.
    LaunchedEffect(controller, listState, reverseLayout) {
        androidx.compose.runtime.snapshotFlow { controller.dragIntent.value != null }
            .collect { dragActive ->
                if (!dragActive) return@collect
                // While the drag is active, tick at ~60Hz and re-evaluate
                // the edge condition. The inner loop exits as soon as
                // dragIntent becomes null (gesture handler clears it on
                // pointer-up).
                while (controller.dragIntent.value != null) {
                    val intent = controller.dragIntent.value
                    val rootCoords = listRootCoordinates()
                    if (intent != null && rootCoords != null && rootCoords.isAttached) {
                        val origin = rootCoords.positionInWindow()
                        val localY = intent.point.y - origin.y
                        val viewportH = rootCoords.size.height
                        edgeAutoScrollNudge(localY, viewportH, listState, reverseLayout)
                    }
                    kotlinx.coroutines.delay(16)
                }
            }
    }
}
/** Convert a local-space pointer point into window-space coordinates. */
private fun localToWindow(
    local: Offset,
    rootCoordinates: () -> LayoutCoordinates?,
): Offset {
    val coords = rootCoordinates() ?: return local
    if (!coords.isAttached) return local
    val origin = coords.positionInWindow()
    return Offset(local.x + origin.x, local.y + origin.y)
}
