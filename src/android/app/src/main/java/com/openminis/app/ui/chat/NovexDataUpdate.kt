package com.openminis.app.ui.chat

import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.novex.domain.PlaythroughValue

/** A software-generated notice describing a state change made by a tool. */
internal data class NovexDataChange(
    val key: String,
    val before: String?,
    val after: String,
)

internal data class NovexDataUpdateEvent(
    val branchId: String,
    val changes: List<NovexDataChange>,
    val sourceLabel: String = "AI 数据工具",
    val createdAtMs: Long = System.currentTimeMillis(),
)

internal fun diffNovexPlaythroughState(
    before: PlaythroughState?,
    after: PlaythroughState,
): List<NovexDataChange> {
    val old = before?.values.orEmpty()
    return after.values.keys
        .union(old.keys)
        .sorted()
        .mapNotNull { key ->
            val beforeText = old[key]?.displayValue()
            val afterText = after.values[key]?.displayValue() ?: return@mapNotNull null
            if (beforeText == afterText) null else NovexDataChange(key, beforeText, afterText)
        }
}

internal fun PlaythroughValue.displayValue(): String = when (this) {
    is PlaythroughValue.Text -> value
    is PlaythroughValue.Number -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is PlaythroughValue.Flag -> if (value) "是" else "否"
}
