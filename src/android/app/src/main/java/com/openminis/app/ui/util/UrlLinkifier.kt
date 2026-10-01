package com.openminis.app.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * 纯文本 → 带链接 [AnnotatedString]（血统清剿 P3.7 就地真重写；WEB_URL
 * + scheme 过滤口径与默认色为行为冻结面）：http(s) URL 包进
 * [LinkAnnotation.Url]，点击经 [onClick] 路由（聊天屏的应用内网页预览处
 * 理器可截胡，对齐 iOS MinisOpenURLBroker 行为）。
 *
 * 工具结果渲染器用（`shell_execute` 输出、通用文本态工具输出等）——上游
 * 内容不是 Markdown，`MarkdownText` 的链接路径够不着。对齐 iOS
 * `TerminalCanvasView.addURLLinks`：仅 http/https，无其他 scheme。
 *
 * 检测用 [android.util.Patterns.WEB_URL]（平台钦定正则，TextView 的
 * Linkify 同源），但只保留真的带 scheme 开头的条目——裸主机名不给可点。
 * iOS 终端画布那种软换行 URL 重组这里不做——Compose `Text` 排逻辑串、不
 * 排网格单元，URL 不会像定宽终端那样被拆到两行。
 *
 * @param text     要链接化的纯文本
 * @param onClick  用户点中链接时以命中的 URL 串回调；典型实现转交聊天的
 *                 `urlClickHandler`（应用内 `UrlPreviewSheet`）。
 * @param linkColor 链接 span 的强调色（默认 iOS systemBlue）。
 */
fun linkifyUrls(
    text: String,
    onClick: (String) -> Unit,
    linkColor: Color = Color(0xFF0A84FF),
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString(text)
    val hits = android.util.Patterns.WEB_URL.matcher(text)

    return buildAnnotatedString {
        var copiedUpTo = 0
        while (hits.find()) {
            val start = hits.start()
            val end = hits.end()
            val candidate = text.substring(start, end)
            // WEB_URL 也匹配裸主机名（"example.com"）——跳过，贴紧 iOS
            // 口径：只有应用内预览确定渲染得了的 scheme 才给下划线。
            val scheme = candidate.lowercase()
            if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) continue

            // 命中之前的非链接空隙先补上。
            if (start > copiedUpTo) append(text, copiedUpTo, start)
            withLink(
                LinkAnnotation.Url(
                    url = candidate,
                    styles = TextLinkStyles(
                        style = SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ),
                    linkInteractionListener = { onClick(candidate) },
                ),
            ) {
                append(candidate)
            }
            copiedUpTo = end
        }
        if (copiedUpTo < text.length) append(text, copiedUpTo, text.length)
    }
}
