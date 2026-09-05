package com.openminis.app.ui.novex

/** Center within the screen where possible, never across either action slot. */
internal fun novexHeaderTitleOffset(width: Int, leadingWidth: Int, trailingWidth: Int, titleWidth: Int): Int {
    val start = leadingWidth.coerceIn(0, width)
    val end = (width - trailingWidth - titleWidth).coerceAtLeast(start)
    return ((width - titleWidth) / 2).coerceIn(start, end)
}
