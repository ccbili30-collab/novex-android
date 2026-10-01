package com.openminis.app.ui.sessions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * 搜索命中高亮：把 [text] 里所有不区分大小写的 [query] 命中段刷上主题
 * 高亮色。空查询原样返回。供会话列表搜索结果的标题与消息摘要使用。
 */
@Composable
fun highlightedAnnotatedString(text: String, query: String): AnnotatedString {
    val bg = MaterialTheme.colorScheme.tertiaryContainer
    val fg = MaterialTheme.colorScheme.onTertiaryContainer
    return remember(text, query, bg, fg) {
        buildHighlightedAnnotatedString(text, query, bg, fg)
    }
}

/** 纯函数实现，脱离组合环境以便单测覆盖命中区间。 */
internal fun buildHighlightedAnnotatedString(
    text: String,
    query: String,
    highlightBg: Color,
    highlightFg: Color,
): AnnotatedString {
    if (text.isEmpty() || query.isBlank()) return AnnotatedString(text)
    val needle = query.lowercase()
    val haystack = text.lowercase()
    val hits = mutableListOf<Int>()
    var scan = haystack.indexOf(needle)
    while (scan >= 0) {
        hits += scan
        scan = haystack.indexOf(needle, scan + needle.length)
    }
    return buildAnnotatedString {
        var cursor = 0
        for (hit in hits) {
            append(text.substring(cursor, hit))
            val end = hit + needle.length
            withStyle(SpanStyle(background = highlightBg, color = highlightFg)) {
                append(text.substring(hit, end))
            }
            cursor = end
        }
        append(text.substring(cursor))
    }
}

/**
 * 在 [text] 里找第一个不区分大小写的命中，返回以命中为中心、约
 * [radius] 字符宽的摘要片段；换行压成空格，截断处补省略号。无命中返 null。
 */
fun snippetAround(text: String, query: String, radius: Int = 50): String? {
    if (text.isEmpty() || query.isBlank()) return null
    val pos = text.lowercase().indexOf(query.lowercase())
    if (pos < 0) return null
    val start = maxOf(0, pos - radius)
    val end = minOf(text.length, pos + query.length + radius)
    return buildString {
        if (start > 0) append('…')
        append(text.substring(start, end).replace('\n', ' ').replace('\r', ' '))
        if (end < text.length) append('…')
    }
}
