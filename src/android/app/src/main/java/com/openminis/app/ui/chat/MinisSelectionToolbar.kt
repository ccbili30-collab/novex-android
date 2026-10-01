package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.R

// 选区浮动工具条：吸附在选区可视部分上方，行内放常用动作，其余进「⋯」
// 溢出菜单；水平中心跟随「正在拖/上次拖过」的手柄。
/**
 * Floating action toolbar rendered above the active selection. Mirrors
 * [MinisMarkdownTextToolbarHost] (which is driven by Compose's
 * SelectionContainer) but consumes our SelectionController state directly.
 *
 * Empty selection → not rendered. Whenever the selection's bounding rect is
 * available (both endpoints' shards are currently composed), the toolbar
 * floats just above it; if the selection collapses or moves off-screen the
 * toolbar disappears.
 */
/**
 * Bundle of side-effecting callbacks the toolbar surfaces as buttons.
 * Pass null to hide a button (e.g. "Add to Input" is hidden when the
 * caller has no composer to receive the text). Mirrors the action set of
 * the older [MinisMarkdownTextToolbar] so the per-button UX stays the
 * same across both selection systems.
 */
data class SelectionToolbarActions(
    /** Resolve the markdown source of the message that owns the active selection, or null on cross-message selections. */
    val resolveSelectionMarkdown: () -> String?,
    /** Append the currently-selected plain text to the chat composer. Null hides the button. */
    val onAddToInput: ((String) -> Unit)? = null,
    /** Share selected text into another Novex conversation. */
    val onShare: ((String) -> Unit)? = null,
    // [P3.3 裁军] onReadAloud（选区朗读）随语音全家退役删除。
)

@Composable
fun MinisSelectionToolbarHost(
    controller: SelectionController,
    actions: SelectionToolbarActions? = null,
    /**
     * Window-space rect inside which the toolbar's vertical position should
     * be clamped (typically the LazyColumn's window bounds). When provided,
     * the toolbar will never float above the rect's top or below its bottom
     * — keeps the menu inside the chat content area instead of hovering
     * over the top bar / composer / navigation gesture pill.
     */
    contentViewportBounds: () -> androidx.compose.ui.geometry.Rect? = { null },
) {
    val sel by controller.selection
    if (sel == null) return
    // Anchor the toolbar above the VISIBLE portion of the selection — the
    // union of every shard's drawn rectangle in window coords (computed by
    // visibleSelectionWindowRect). This way the menu tracks the highlight
    // as the user scrolls: when the top of the selection is visible, the
    // menu hovers above it; when only the bottom is visible, the menu
    // moves down to hug what's still on screen; when nothing's on screen,
    // the position provider falls back to pinning the menu to the top of
    // the window (sentinel anchor (0,0,0,0)).
    //
    // Frame-tick recomposition: shard positionInWindow() changes inside
    // LazyColumn's layout pass without invalidating this composable, so
    // we drive a per-frame tick so the menu re-positions every scroll
    // frame instead of locking to a stale rect.
    val tick = remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(controller) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { /* no-op */ }
            tick.value = tick.value + 1
        }
    }
    @Suppress("UNUSED_VARIABLE") val t = tick.value
    // Anchor choice:
    //  - During a handle drag, follow the GRABBED handle so the menu
    //    shadows whichever endpoint the user is moving.
    //  - After the drag ends, KEEP the menu over that same handle until
    //    something else happens (new long-press, the other handle gets
    //    grabbed, selection cleared). Otherwise the menu would snap back
    //    to the end-line default the instant the user lifts a finger,
    //    which feels jumpy — the user just dragged the start handle,
    //    they want the menu to keep tracking it.
    //  - Idle with no last-dragged handle (fresh long-press) → anchor at
    //    the end-line of the highlight.
    val activeDragHandle = controller.dragIntent.value?.handle
    val lastDraggedHandle = remember { androidx.compose.runtime.mutableStateOf<SelectionController.Handle?>(null) }
    LaunchedEffect(controller) {
        androidx.compose.runtime.snapshotFlow { controller.dragIntent.value?.handle }
            .collect { h -> if (h != null) lastDraggedHandle.value = h }
    }
    // Clear sticky preference when the selection changes via long-press
    // (i.e. selection.start == selection.end on word-begin) — the user
    // started a fresh selection so the menu should revert to its default
    // anchor.
    LaunchedEffect(controller) {
        androidx.compose.runtime.snapshotFlow { controller.selection.value }
            .collect { sel ->
                if (sel != null && sel.start == sel.end) lastDraggedHandle.value = null
            }
    }
    val anchorHandle = activeDragHandle ?: lastDraggedHandle.value
    val rect = when (anchorHandle) {
        SelectionController.Handle.Start -> controller.draggedHandleLineRect(SelectionController.Handle.Start)
        SelectionController.Handle.End -> controller.draggedHandleLineRect(SelectionController.Handle.End)
        else -> null
    } ?: controller.visibleSelectionEndLineRect()
        ?: controller.visibleSelectionWindowRect()
    if (rect != null && rect.width <= 0f && rect.height <= 0f) return
    val anchor = remember(rect) {
        if (rect != null) IntRect(
            left = rect.left.toInt(),
            top = rect.top.toInt(),
            right = rect.right.toInt(),
            bottom = rect.bottom.toInt(),
        ) else IntRect(0, 0, 0, 0) // FloatingSelectionToolbarPositionProvider
        // treats (0,0,0,0) as "pin to top of the window" — see below.
    }
    val viewportBounds = contentViewportBounds()
    // Horizontal center matches whichever handle we're tracking (same
    // rule as the vertical anchor above): the actively-dragged handle
    // takes priority, then the last-dragged handle, then the midpoint
    // between both handles as the idle default.
    val centerX = when (anchorHandle) {
        SelectionController.Handle.Start ->
            controller.handleAnchor(SelectionController.Handle.Start)?.x
        SelectionController.Handle.End ->
            controller.handleAnchor(SelectionController.Handle.End)?.x
        else -> null
    } ?: controller.handlesCenterX()
    val positionProvider = remember(anchor, viewportBounds, centerX) {
        FloatingSelectionToolbarPositionProvider(anchor, viewportBounds, centerX)
    }
    val clipboard = LocalClipboardManager.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = { /* let user tap-clear via gesture */ },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        // [T-android-text-toolbar-dark-visibility] Elevated container colour +
        // border + stronger shadow so the bar pops out of the chat background
        // in dark mode (bare `surface` was nearly invisible there).
        val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
        // [T-android-table-toolbar-overflow] Cap the bar to the screen width so
        // it can't spill past the edge. A table selection appends Copy Table /
        // Copy Table Image to an already-long row (Copy · Add to Input · Read
        // Aloud · Copy Markdown · Copy Rich Text), which overran the screen —
        // the horizontalScroll below clips the overflow, but without this cap
        // the Popup sized the Surface to full content width and the extra
        // buttons rendered off the right edge, unreachable.
        val ctxForWidth = androidx.compose.ui.platform.LocalContext.current
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxBarWidth = with(density) {
            ctxForWidth.resources.displayMetrics.widthPixels.toFloat().toDp() - 16.dp
        }
        Surface(
            // Cap the Surface width; the scrollable Row inside then clips to it.
            modifier = Modifier.widthIn(max = maxBarWidth),
            shape = RoundedCornerShape(10.dp),
            color = barColor,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        ) {
            // Make the action row horizontally scrollable so it survives
            // narrow viewports + many actions ("Copy" + "Add to input box" +
            // "Copy Markdown" + "Copy Rich Text" + table actions can easily
            // exceed the screen width). horizontalScroll lets the user swipe
            // to reach buttons past the capped Surface width.
            val toolbarScroll = androidx.compose.foundation.rememberScrollState()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(barColor)
                    .height(40.dp)
                    .horizontalScroll(toolbarScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fun toast(msg: String) {
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                fun preview(text: String): String =
                    if (text.length > 40) text.take(37) + "…" else text

                val labelCopy = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copy)
                val labelAddToInput = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_add_to_chat_input)
                val labelCopyMarkdown = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copy_markdown)
                val labelCopyRichText = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copy_rich_text)
                val toastCopiedAsMarkdown = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copied_as_markdown_toast)
                val toastCopiedAsRichText = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.selection_copied_as_rich_text_toast)
                // [T-android-markdown-table-copy-actions] Copy Table / Copy
                // Table Image, when the selected message contains a table.
                //
                // This is the toolbar that ACTUALLY shows for a MinisTextKit
                // selection (a table-cell long-press). The table actions were
                // previously wired only into MinisMarkdownTextToolbar — the
                // Compose-SelectionContainer toolbar that this MinisTextKit
                // selection never triggers — so the buttons registered fine
                // (debug.selectionState: tableActionsHit=true) but appeared in
                // a toolbar the user never sees.
                val tableActions = controller.selectionTableActions()
                val labelCopyTable = androidx.compose.ui.res.stringResource(
                    com.openminis.app.R.string.markdown_table_copy_table)
                val labelCopyTableImage = androidx.compose.ui.res.stringResource(
                    com.openminis.app.R.string.markdown_table_copy_table_image)

                // Actions are collected into a list first so the bar can show a
                // few and push the rest into an overflow menu. Ordered by how
                // often they are wanted: Copy leads, the two markdown variants
                // trail, table actions last (they apply to the whole table, not
                // to what the user just selected).
                val items = buildList {
                    add(SelectionAction(labelCopy) {
                        val text = controller.selectedPlainText()
                        if (text.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(text))
                            haptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            toast(context.getString(
                                com.openminis.app.R.string.selection_copied_toast, preview(text)))
                        }
                        controller.clearSelection()
                    })
                    if (actions?.onAddToInput != null) {
                        add(SelectionAction(labelAddToInput) {
                            val text = controller.selectedPlainText()
                            if (text.isNotEmpty()) actions.onAddToInput.invoke(text)
                            controller.clearSelection()
                        })
                    }
                    if (actions?.onShare != null) {
                        add(SelectionAction("分享到其他文游") {
                            val text = controller.selectedPlainText()
                            if (text.isNotEmpty()) actions.onShare.invoke(text)
                            controller.clearSelection()
                        })
                    }
                    // [P3.3 裁军] 「朗读」动作随选区朗读（TTS）退役删除。
                    // Copy Markdown / Copy Rich Text are ALWAYS offered when the
                    // selection contains rendered text. Earlier we gated them on
                    // resolveSelectionMarkdown() returning non-null, but that hid
                    // them in common cases (user bubbles before SideEffect
                    // publish, the recompose race after a previous click, cross-
                    // shard selections where the cache briefly hadn't populated).
                    // Resolve lazily at click time and fall back to plain text.
                    add(SelectionAction(labelCopyMarkdown) {
                        val source = actions?.resolveSelectionMarkdown?.invoke()
                            ?: controller.selectedPlainText()
                        if (source.isNotEmpty()) {
                            MarkdownClipboard.copyMarkdown(context, source)
                            toast(toastCopiedAsMarkdown)
                        }
                        controller.clearSelection()
                    })
                    add(SelectionAction(labelCopyRichText) {
                        val source = actions?.resolveSelectionMarkdown?.invoke()
                            ?: controller.selectedPlainText()
                        if (source.isNotEmpty()) {
                            MarkdownClipboard.copyRichText(context, source)
                            toast(toastCopiedAsRichText)
                        }
                        controller.clearSelection()
                    })
                    if (tableActions != null) {
                        add(SelectionAction(labelCopyTable) {
                            tableActions.copyTableMarkdown()
                            controller.clearSelection()
                        })
                        add(SelectionAction(labelCopyTableImage) {
                            tableActions.copyTableImage()
                            controller.clearSelection()
                        })
                    }
                }

                // Mirror iOS's edit menu: a few actions inline, the rest behind
                // a chevron. On iOS UIEditMenuInteraction provides that overflow
                // for free; this bar is hand-built, so it is implemented here.
                //
                // The horizontalScroll above still exists as a backstop for very
                // narrow screens, but it was never a good primary answer — an
                // action reachable only by swiping a 40dp bar is an action most
                // users never find.
                val inlineCount = MAX_INLINE_SELECTION_ACTIONS
                val inlineItems = items.take(inlineCount)
                val overflowItems = items.drop(inlineCount)

                inlineItems.forEachIndexed { index, item ->
                    if (index > 0) MinisToolbarDivider()
                    MinisToolbarButton(label = item.label, onClick = item.onClick)
                }
                if (overflowItems.isNotEmpty()) {
                    MinisToolbarDivider()
                    var overflowOpen by remember(items.size) { mutableStateOf(false) }
                    Box {
                        MinisToolbarButton(label = "⋯") { overflowOpen = true }
                        novex.android.ui.DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            for (item in overflowItems) {
                                novex.android.ui.DropdownMenuItem(
                                    text = { Text(item.label) },
                                    onClick = {
                                        overflowOpen = false
                                        item.onClick()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One entry in the selection toolbar — inline button or overflow menu row. */
private class SelectionAction(val label: String, val onClick: () -> Unit)

/**
 * How many actions stay on the bar before the rest move into the overflow menu.
 *
 * Three, matching what iOS's edit menu shows before its own chevron. The bar is
 * anchored to a selection the user is looking at, so it has to stay narrow
 * enough not to cover the text it belongs to.
 */
private const val MAX_INLINE_SELECTION_ACTIONS = 3

@Composable
private fun MinisToolbarDivider() {
    Box(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
    )
}

@Composable
private fun MinisToolbarButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

private class FloatingSelectionToolbarPositionProvider(
    private val anchor: IntRect,
    private val viewport: androidx.compose.ui.geometry.Rect?,
    private val preferredCenterX: Float?,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // The viewport defines the vertical band inside which the toolbar
        // may be placed (typically the LazyColumn's window bounds — keeps
        // the menu off the top app bar / composer / gesture navigation
        // pill at the screen edges). Fall back to the whole window when
        // no viewport was supplied.
        val viewTop = viewport?.top?.toInt() ?: 0
        val viewBottom = viewport?.bottom?.toInt() ?: windowSize.height
        val viewLeft = viewport?.left?.toInt() ?: 0
        val viewRight = viewport?.right?.toInt() ?: windowSize.width
        val maxY = (viewBottom - popupContentSize.height).coerceAtLeast(viewTop)
        val maxX = (viewRight - popupContentSize.width).coerceAtLeast(viewLeft)

        // Empty anchor (0,0,0,0): selection completely off-screen — pin to
        // top center of the viewport.
        if (anchor.left == 0 && anchor.top == 0 && anchor.right == 0 && anchor.bottom == 0) {
            val centerX = (viewLeft + viewRight) / 2
            val x = (centerX - popupContentSize.width / 2).coerceIn(viewLeft, maxX)
            val y = (viewTop + 12).coerceIn(viewTop, maxY)
            return IntOffset(x, y)
        }
        // Gap above the anchor — large enough to clear the selection
        // handle dot (HANDLE_DOT_SIZE_DP = 14dp ≈ 37px on Pixel 4a) plus
        // a bit of breathing room so the menu doesn't crowd the text.
        val gap = 48
        // Prefer the explicit "midpoint between handles" when supplied —
        // otherwise the anchor's own horizontal center.
        val centerX = preferredCenterX?.toInt() ?: (anchor.left + anchor.width / 2)
        val x = (centerX - popupContentSize.width / 2).coerceIn(viewLeft, maxX)
        // Prefer placing the menu ABOVE the anchor; if no room there, drop
        // BELOW (with the same gap). In both branches clamp to
        // [viewTop, maxY] so the menu can't escape the chat content area.
        val aboveY = anchor.top - popupContentSize.height - gap
        val y = if (aboveY >= viewTop) {
            aboveY
        } else {
            (anchor.bottom + gap).coerceIn(viewTop, maxY)
        }
        return IntOffset(x, y.coerceIn(viewTop, maxY))
    }
}
