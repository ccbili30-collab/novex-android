package com.openminis.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

// iOS 风格选区端点手柄：两个独立 Popup 圆点挂在选区两端下沿（可伸出
// LazyColumn 条目外）。拖拽期间冻结 Popup 锚点，保证「popup 内坐标增量
// == 窗口坐标增量」不变式；拖动时圆点跟随手指实时位置。
/**
 * iOS-style draggable selection handles — a small filled circle below each
 * endpoint of the active selection. The handles are visual only; the
 * actual drag logic lives in [minisTextKitSelectionGesture] which routes
 * pointer events through [SelectionController.grabHandleAt]. Rendering
 * them as separate Popups means they can extend BELOW the LazyColumn item
 * (matching system text-selection handle UX) without being clipped.
 */
/**
 * Total hit area for a handle. The visible dot inside is ~10dp, but the
 * Popup body is much larger to forgive imprecise touches — matches what
 * Android's system text-selection handles do.
 */
private val HANDLE_HIT_SIZE_DP = 56.dp

/** Visible dot diameter. */
private val HANDLE_DOT_SIZE_DP = 14.dp

@Composable
fun MinisSelectionHandlesHost(
    controller: SelectionController,
    listState: LazyListState,
    reverseLayout: Boolean = false,
) {
    val sel by controller.selection
    if (sel == null) return
    // Recompute handle anchors every frame so the Popups follow the
    // underlying shards as the LazyColumn scrolls. The shards' positions
    // change inside their parent's layout pass, which doesn't invalidate
    // this composable on its own — we need the explicit frame tick.
    val tick = remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(controller) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { /* no-op */ }
            tick.value = tick.value + 1
        }
    }
    // Read the tick so the next two anchor reads invalidate every frame.
    @Suppress("UNUSED_VARIABLE") val t = tick.value
    // FREEZE handle positions while a drag is active. Otherwise the popup
    // chases the selection endpoint as the user drags, which keeps moving
    // the popup window's content under the finger — popup-local pointer
    // deltas then no longer equal screen-space deltas and the drag
    // computation diverges (finger goes one way, selection goes the
    // opposite way). Compose's own SelectionManager avoids this because
    // its drag handler lives in the selection container, not inside the
    // popup. Our drag lives in the popup; freezing the popup during drag
    // restores the invariant "popup-local delta == window delta".
    val dragging = controller.dragIntent.value != null
    val frozenStart = remember { androidx.compose.runtime.mutableStateOf<Offset?>(null) }
    val frozenEnd = remember { androidx.compose.runtime.mutableStateOf<Offset?>(null) }
    // Each handle's anchor is computed independently — if ONE endpoint's
    // shard scrolled out of the viewport (off-screen, disposed by
    // LazyColumn), the OTHER end's shard may still be visible and its
    // handle should still render. Previously a single `?: return` aborted
    // the whole host, hiding even the in-viewport handle.
    val liveStart = controller.handleAnchor(SelectionController.Handle.Start)
    val liveEnd = controller.handleAnchor(SelectionController.Handle.End)
    if (dragging) {
        if (frozenStart.value == null && liveStart != null) frozenStart.value = liveStart
        if (frozenEnd.value == null && liveEnd != null) frozenEnd.value = liveEnd
    } else {
        frozenStart.value = null
        frozenEnd.value = null
    }
    val startAnchor = frozenStart.value ?: liveStart
    val endAnchor = frozenEnd.value ?: liveEnd
    if (startAnchor != null) {
        SelectionHandleDot(
            anchorWindow = startAnchor,
            isStart = true,
            controller = controller,
            listState = listState,
            reverseLayout = reverseLayout,
        )
    }
    if (endAnchor != null) {
        SelectionHandleDot(
            anchorWindow = endAnchor,
            isStart = false,
            controller = controller,
            listState = listState,
            reverseLayout = reverseLayout,
        )
    }
}

@Composable
private fun SelectionHandleDot(
    anchorWindow: Offset,
    isStart: Boolean,
    controller: SelectionController,
    listState: LazyListState,
    reverseLayout: Boolean,
) {
    val color = MaterialTheme.colorScheme.primary
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dotPx = with(density) { (HANDLE_DOT_SIZE_DP / 2).toPx() }
    val hitPx = with(density) { HANDLE_HIT_SIZE_DP.toPx() }
    val provider = remember(anchorWindow.x, anchorWindow.y, isStart, hitPx) {
        HandlePositionProvider(anchorWindow, isStart = isStart, hitSizePx = hitPx)
    }
    val handleKind = if (isStart) SelectionController.Handle.Start else SelectionController.Handle.End
    // Live window origin of the popup's content box, updated every layout
    // pass. Used by the drag gesture below to convert popup-local pointer
    // coordinates into window-absolute coordinates without accumulating
    // delta-based drift.
    var boxWindowOrigin by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        // The hit-target box has its OWN pointerInput because pointer events
        // inside a Popup do NOT bubble back into the LazyColumn that owns
        // [minisTextKitSelectionGesture] — popups are separate windows. The
        // gesture here mirrors handleDragLoop's behavior: publish the latest
        // finger window-point through controller.dragIntent, and let
        // SelectionDragTracker convert that into actual selection updates.
        Box(
            modifier = Modifier
                .size(HANDLE_HIT_SIZE_DP)
                // The hit-target box sits inside a Popup that *re-positions*
                // every frame to follow the selection endpoint as the
                // LazyColumn scrolls. If we computed window coords via raw
                // delta accumulation (popup-local position - down position
                // + anchor), we'd get phantom motion: even with a stationary
                // finger the Popup moves under it during scroll, so the
                // popup-local position changes → false drag delta → fake
                // selection updates → "selection shifts on scroll" symptom.
                //
                // Capture the popup body's current windowOrigin via
                // onGloballyPositioned. The drag handler then computes
                // window-absolute = position + windowOrigin **every event**,
                // which is invariant under popup motion: when popup moves
                // by dy, the local position changes by -dy in the same
                // frame, and the sum stays put.
                .pointerInput(controller, handleKind) {
                    // Popup is its OWN window; pointer positions inside the
                    // popup are NOT in the main app window's coordinate
                    // space. positionInWindow() on anything inside the popup
                    // returns popup-local coords (0,0 = popup's top-left).
                    //
                    // We DO know where the popup body sits in main-window
                    // coords: HandlePositionProvider placed it at
                    //   x = (isStart ? anchorWindow.x - popupWidth : anchorWindow.x)
                    //   y = anchorWindow.y
                    // We don't have popupWidth at runtime, so anchor the
                    // gesture against a delta from the long-press / drag
                    // start position. Compose's detectDragGestures gives us
                    // both the absolute popup-local PointerInputChange and
                    // a per-frame delta — use the delta to accumulate a
                    // total drag, then publish (anchorWindow + total).
                    var dragTotal = Offset.Zero
                    var dragStartAnchor = anchorWindow
                    detectDragGestures(
                        onDragStart = {
                            // Snapshot the handle's current anchor at the
                            // moment drag begins. Re-snapshot here (rather
                            // than relying on `anchorWindow` from the
                            // composable's closure) because the surrounding
                            // recomposition cycle has been re-instantiating
                            // anchorWindow every frame — we want the value
                            // AS OF the user's press, then accumulate
                            // deltas from there.
                            dragStartAnchor = controller.handleAnchor(handleKind) ?: anchorWindow
                            dragTotal = Offset.Zero
                            controller.dragIntent.value = SelectionController.DragIntent(
                                point = dragStartAnchor,
                                handle = handleKind,
                            )
                        },
                        onDrag = { _, delta ->
                            dragTotal += delta
                            val windowPos = dragStartAnchor + dragTotal
                            controller.dragIntent.value = SelectionController.DragIntent(
                                point = windowPos,
                                handle = handleKind,
                            )
                        },
                        onDragEnd = { controller.dragIntent.value = null },
                        onDragCancel = { controller.dragIntent.value = null },
                    )
                },
            // Popup box is centered on anchorWindow.x; align the dot horizontally
            // to the center so the visible knob sits exactly under the selection
            // edge. The asymmetric TopEnd/TopStart from earlier was wrong once
            // HandlePositionProvider switched to centering the body.
            contentAlignment = Alignment.TopCenter,
        ) {
            // While THIS handle is being dragged, draw the dot at the
            // finger's live position (offset relative to the popup body,
            // which is itself frozen so popup-local deltas equal window
            // deltas). When not dragging, the dot stays at TopCenter as
            // before, snapping to the selection endpoint anchor.
            val live = controller.dragIntent.value
            val draggingThis = live != null && live.handle == handleKind
            val dragOffsetWindow = if (draggingThis) {
                live!!.point - anchorWindow
            } else Offset.Zero
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .size(HANDLE_DOT_SIZE_DP)
                    .offset {
                        IntOffset(
                            x = dragOffsetWindow.x.toInt(),
                            y = dragOffsetWindow.y.toInt(),
                        )
                    },
            ) {
                drawCircle(color = color, radius = dotPx)
            }
        }
    }
}

/**
 * Position the 40dp hit-target box so the dot inside sits flush against
 * the selection edge in window space. Start handle: box's right edge sits
 * at anchor.x. End handle: box's left edge sits at anchor.x. Vertically
 * the box's top sits at anchor.y (the bottom-of-text line), so the dot
 * hangs just below the line — matching system handles.
 */
private class HandlePositionProvider(
    private val anchorWindow: Offset,
    private val isStart: Boolean,
    private val hitSizePx: Float,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Center the popup body horizontally on anchorWindow.x. The visible
        // dot inside the body is drawn at TopEnd (start handle) or TopStart
        // (end handle), so:
        //   - START handle: dot's right edge lines up with the box's
        //     horizontal center → dot at (anchorWindow.x − dotRadius)
        //   - END handle: dot's left edge lines up with the box's center →
        //     dot at (anchorWindow.x + dotRadius)
        // Both flavours expose roughly the same hit-target area, but the
        // visible knob sits at the selection edge instead of overhanging
        // the line by an entire 56 dp.
        val halfBox = popupContentSize.width / 2
        val x = (anchorWindow.x - halfBox).toInt()
        val y = (anchorWindow.y).toInt()
        return IntOffset(
            x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
