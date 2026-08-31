package com.openminis.app.ui.chat

import kotlin.math.ceil

/** Height budget for the three-line chat title at the current system font scale. */
internal fun chatTopBarExpandedHeightDp(fontScale: Float): Int {
    val extraForScaledText = (fontScale.coerceAtLeast(1f) - 1f) * 46f
    return ceil(76f + extraForScaledText).toInt().coerceIn(76, 120)
}
