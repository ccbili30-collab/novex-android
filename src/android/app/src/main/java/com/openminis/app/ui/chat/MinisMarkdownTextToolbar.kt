package com.openminis.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.R

/**
 * 聊天选区的自定义浮动工具条，替换 SelectionContainer 默认弹出的系统
 * 复制/粘贴条。是平台接口 [TextToolbar]（非组合环境），所以状态由
 * [state] 持有、浮层由配套的 [MinisMarkdownTextToolbarHost] 在组合里画。
 *
 * 动作：复制 / 加入输入框 / 分享 / 复制 Markdown / 复制富文本 / 表格动作
 * （后三者仅在选区落在单条已知消息内时出现——跨消息选区不知道取谁的源文）。
 */
internal class MinisMarkdownTextToolbar(
    private val context: Context,
    private val registry: MessageBoundsRegistry,
    /** 选中文本追加进聊天输入框；null 则不显示该动作。 */
    private val onAddToInput: ((String) -> Unit)? = null,
    private val onShare: ((String) -> Unit)? = null,
    /**
     * MinisTextKit 选区控制器：表格单元格长按也走它，这里把表格的
     * 「复制表格/复制表格图」并进同一条，不再弹第二个菜单。
     */
    internal val selectionController: SelectionController? = null,
) : TextToolbar {

    internal var state by mutableStateOf(ToolbarState())
        private set

    internal val canAddToInput: Boolean get() = onAddToInput != null
    internal val canShare: Boolean get() = onShare != null

    override val status: TextToolbarStatus
        get() = if (state.visible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        state = ToolbarState(
            visible = true,
            rect = rect,
            onCopyRequested = onCopyRequested,
            originatingMarkdown = registry.markdownAt(rect),
            lastShownAtMs = android.os.SystemClock.uptimeMillis(),
        )
    }

    override fun hide() {
        state = state.copy(visible = false)
    }

    internal fun addSelectionToInput() = forwardSelection(onAddToInput)
    internal fun shareSelection() = forwardSelection(onShare)

    /**
     * 拿当前选中文本交给 [sink]。Compose 的 TextToolbar 不直接给选区文本，
     * 只能借系统剪贴板中转：先备份用户原剪贴板 → 调 onCopyRequested 让
     * Compose 把选区写进剪贴板 → 读出 → 还原原剪贴板 → 交给 sink。
     * 任一步失败都是 no-op，不抛错也不弄脏剪贴板。
     */
    private fun forwardSelection(sink: ((String) -> Unit)?) {
        val sinkFn = sink ?: return
        val copy = state.onCopyRequested ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val backup = runCatching { clipboard.primaryClip }.getOrNull()
        try {
            copy()
            val text = runCatching {
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) sinkFn(text)
        } finally {
            if (backup != null) runCatching { clipboard.setPrimaryClip(backup) }
        }
    }

    internal fun copyMarkdown() {
        state.originatingMarkdown?.let {
            MarkdownClipboard.copyMarkdown(context, it)
            Toast.makeText(context, "Copied as Markdown", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun copyRichText() {
        state.originatingMarkdown?.let {
            MarkdownClipboard.copyRichText(context, it)
            Toast.makeText(context, "Copied as Rich Text", Toast.LENGTH_SHORT).show()
        }
    }

    internal data class ToolbarState(
        val visible: Boolean = false,
        val rect: Rect = Rect.Zero,
        val onCopyRequested: (() -> Unit)? = null,
        /** 选区所属消息的完整 markdown 源文；跨消息/界外为 null。 */
        val originatingMarkdown: String? = null,
        /**
         * 最近一次 [showMenu] 的 uptime。hide 不清它——消费方靠它区分
         * 「拖动中短暂隐藏」和「很久没动过选区」。
         */
        val lastShownAtMs: Long = 0L,
    )
}

private class ToolbarAction(val label: String, val onClick: () -> Unit)

/** 渲染 [MinisMarkdownTextToolbar] 控制的浮条；放在提供 toolbar 的同一棵组合树下。 */
@Composable
internal fun MinisMarkdownTextToolbarHost(toolbar: MinisMarkdownTextToolbar) {
    val state = toolbar.state
    if (!state.visible) return

    val anchor = remember(state.rect) {
        with(state.rect) { IntRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt()) }
    }

    // 动作按可见性收成一条列表，行与行之间夹细分隔线。
    val actions = buildList {
        add(ToolbarAction("Copy") { state.onCopyRequested?.invoke(); toolbar.hide() })
        if (toolbar.canAddToInput) {
            add(ToolbarAction(stringResource(R.string.selection_add_to_chat_input)) {
                toolbar.addSelectionToInput(); toolbar.hide()
            })
        }
        if (toolbar.canShare) {
            add(ToolbarAction("分享到其他文游") { toolbar.shareSelection(); toolbar.hide() })
        }
        if (state.originatingMarkdown != null) {
            add(ToolbarAction("Copy Markdown") { toolbar.copyMarkdown(); toolbar.hide() })
            add(ToolbarAction("Copy Rich Text") { toolbar.copyRichText(); toolbar.hide() })
        }
        toolbar.selectionController?.selectionTableActions()?.let { table ->
            add(ToolbarAction(stringResource(R.string.markdown_table_copy_table)) {
                table.copyTableMarkdown(); toolbar.hide()
            })
            add(ToolbarAction(stringResource(R.string.markdown_table_copy_table_image)) {
                table.copyTableImage(); toolbar.hide()
            })
        }
    }

    Popup(
        popupPositionProvider = remember(anchor) { SelectionToolbarPlacement(anchor) },
        onDismissRequest = { toolbar.hide() },
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        // 深色模式下 surface 几乎糊进聊天底：用 elevated 面 + 描边 + 更重的影子
        // 让浮条读起来是"浮起的一层"。
        val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = barColor,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            border = androidx.compose.foundation.BorderStroke(
                0.5.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            ),
        ) {
            Row(
                Modifier.height(40.dp).background(barColor),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEachIndexed { i, action ->
                    if (i > 0) {
                        Box(
                            Modifier
                                .width(0.5.dp)
                                .fillMaxHeight()
                                .padding(vertical = 8.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        )
                    }
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        // maxLines=1 + softWrap=false 是承重的：40dp 定高里
                        // 折行会只剩第一个词（"Copy Rich Text" 变 "Copy"）。
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clickable(onClick = action.onClick)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** 选区上方放得下就浮在上面，放不下放下面；横向钳在窗口内。 */
private class SelectionToolbarPlacement(private val anchor: IntRect) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchor.left + anchor.width / 2 - popupContentSize.width / 2)
            .coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        val top = anchor.top - popupContentSize.height - 8
        val y = when {
            top >= 0 -> top
            else -> minOf(anchor.bottom + 8, maxOf(0, windowSize.height - popupContentSize.height))
        }
        return IntOffset(x, y)
    }
}
