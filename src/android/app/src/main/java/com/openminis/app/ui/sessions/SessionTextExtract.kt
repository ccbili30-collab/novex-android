package com.openminis.app.ui.sessions

import com.openminis.app.data.attachments.stripAgentAttachmentMetadata

/**
 * 从消息的 partsJson 提取纯文本：只取 type=="text" 的分段，剥掉
 * <user-attached-files> 附件清单 XML（[T-android-retry-attachment-loss]
 * 起附件清单作为 text part 持久化给模型看，但列表预览/搜索摘要绝不能
 * 把它当正文露出——iOS 在 ChatStore.toChatMessage 里同样剥除）。
 * 解析失败时原样返回 partsJson。
 */
internal fun extractMessageText(partsJson: String): String = try {
    val arr = org.json.JSONArray(partsJson)
    (0 until arr.length()).mapNotNull { i ->
        val obj = arr.getJSONObject(i)
        if (obj.optString("type") != "text") return@mapNotNull null
        stripAgentAttachmentMetadata(obj.optString("value")).ifEmpty { null }
    }.joinToString("\n")
} catch (_: Exception) {
    partsJson
}

/** 以命中位置 [pos]（[matchLen] 为命中长度）为中心截 ~100 字摘要，
 *  换行压成空格，两端截断处补省略号。 */
internal fun snippetAround(text: String, pos: Int, matchLen: Int): String {
    val radius = 50
    val start = (pos - radius).coerceAtLeast(0)
    val end = (pos + matchLen + radius).coerceAtMost(text.length)
    val core = text.substring(start, end).replace('\n', ' ').replace('\r', ' ')
    return (if (start > 0) "…" else "") + core + (if (end < text.length) "…" else "")
}
