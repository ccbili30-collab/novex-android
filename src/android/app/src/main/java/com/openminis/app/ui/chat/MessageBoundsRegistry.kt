package com.openminis.app.ui.chat

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * 会话内「已布局消息边界」登记处（血统清剿 P3.7 就地真重写；查询语义与
 * CompositionLocal 名为消费方依赖面冻结）。
 *
 * 让外层 [androidx.compose.foundation.text.selection.SelectionContainer] 的
 * 自定义 [androidx.compose.ui.platform.TextToolbar] 能查出选区矩形属于哪条
 * 消息，从而提供来源消息完整 markdown 的「复制 Markdown / 复制富文本」。
 *
 * 登记以消息 id 为键，存窗口坐标下的边界 + 该消息拼接 markdown 源的快照。
 * 每个 `AssistantMessageView` 经 `onGloballyPositioned` 写入、离开组合时
 * 清掉自己的条目。
 *
 * 查询取「边界在竖直方向罩住查询矩形 y 中心」的那条消息——选区矩形通常
 * 是短横向区间，但 y 轴上干净地归属唯一消息。没有命中（跨消息选区或矩形
 * 全打空）返回 null，调用方回落系统复制条行为。
 */
class MessageBoundsRegistry {

    /**
     * (messageId, slotKey) 一格：一条消息摊平成多个 LazyColumn 条目（正文
     * 块 + 工具药丸 + …）时各件独立登记。markdown 是消息级整体 markdown，
     * 该消息全部槽位共享。
     */
    private data class Entry(val bounds: Rect, val markdown: String)

    private val slots = mutableMapOf<Pair<String, String>, Entry>()

    fun put(messageId: String, slotKey: String, bounds: Rect, markdown: String) {
        slots[messageId to slotKey] = Entry(bounds, markdown)
    }

    fun remove(messageId: String, slotKey: String) {
        slots.remove(messageId to slotKey)
    }

    /**
     * 找「边界竖直罩住 [rect] y 中心」的消息 markdown；无槽位命中为 null。
     */
    fun markdownAt(rect: Rect): String? {
        val centerY = (rect.top + rect.bottom) / 2f
        return slots.values.firstOrNull { centerY in it.bounds.top..it.bounds.bottom }?.markdown
    }

    /**
     * messageId → markdown 直查，MinisTextKit 选区工具条用——即便选区两端
     * 的分片都已滚出视口，也能解析出父消息源。回落到该消息任意已登记槽位
     * （按 buildFlatChatItems 的构造，任一槽位的 markdown 都是消息级拼接
     * markdown）。
     */
    fun markdownFor(messageId: String): String? =
        slots.entries.firstOrNull { it.key.first == messageId }?.value?.markdown
}

val LocalMessageBoundsRegistry = compositionLocalOf<MessageBoundsRegistry?> { null }
