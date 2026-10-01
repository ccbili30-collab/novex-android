package com.openminis.app.share

/**
 * 分享载荷的线上记录（P3.5c 真重写收编；嵌套 Item/Kind 被 ChatScreen
 * 以 `PendingShare.Item.Kind` 形态钉住，无法经 typealias 转发——正典
 * 留此、实现侧反向引用）。
 *
 * 线上形态（冻结面，对齐 iOS Shared/PendingShare.swift，两端分享扩展
 * 产/消同一份盘上 JSON）：`{items: [{kind, value}], timestamp}`；
 * kind ∈ {"inlineText", "attachment"}；inlineText 的 value 是文本本身，
 * attachment 的 value 是暂存目录（filesDir/share_extension/）里的文件名。
 * 编解码在 novex.android.sharekit.ShareWire。
 */
data class PendingShare(val items: List<Item>, val timestampMs: Long) {
    data class Item(val kind: Kind, val value: String) {
        enum class Kind(val wire: String) { INLINE_TEXT("inlineText"), ATTACHMENT("attachment") }
    }
}
