package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.openminis.app.R
import com.openminis.app.diagnostics.CONTENT_DIAG_MIN_CHARS
import com.openminis.app.diagnostics.ContentDiag
import com.openminis.app.diagnostics.HangDetector
import com.openminis.app.logging.AppLogger
import java.io.File
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import novex.android.ui.TextButton

/**
 * 超大消息体的渲染护栏。
 *
 * 冻结消息超过 [LARGE_MESSAGE_THRESHOLD_CHARS] 默认不喂给 markdown 解析器，
 * 改出一个折叠徽标（预览 + 大小 + 展开/导出），避免重开旧会话时 200KB+
 * 的 tool_result 行触发 GC 风暴卡死主线程。流式中的消息永不折叠。
 *
 * 流式另有主动降级：缓冲超过 [STREAM_DEGRADE_CHARS] 时每帧不再对全文跑
 * markdown 正则，改成只排一个纯文本尾部窗口，回合结束再整段补渲染一次。
 */

internal const val LARGE_MESSAGE_THRESHOLD_CHARS = 32_000
internal const val LARGE_MESSAGE_PREVIEW_CHARS = 8_000

/**
 * 流式降级阈值：流式成本是「每 tick 全文扫一遍」而非一次，所以远低于
 * 冻结阈值。与 StreamingMarkdownText 的 LIVE_FRAGMENT_DEGRADE_CHARS 对齐。
 */
internal const val STREAM_DEGRADE_CHARS = 8_000

private const val DEGRADED_TAIL_CHARS = 4_000

/** 冻结且超阈值 → 走折叠路径。流中内容一律内联。 */
internal fun shouldCollapse(content: String, isStreaming: Boolean): Boolean =
    !isStreaming && content.length > LARGE_MESSAGE_THRESHOLD_CHARS

@Composable
internal fun LargeContentGuard(
    content: String,
    isStreaming: Boolean,
    stableKey: String,
    renderer: @Composable () -> Unit,
) {
    // 标记本块是否经历过流式降级，用于回合结束时记一次"整段补渲染"日志。
    val streamedDegraded = remember(stableKey) { mutableStateOf(false) }

    if (isStreaming) {
        val breakerActive by HangDetector.renderBreakerActive.collectAsState()
        // 渲染断路器已跳闸时无视大小直接降级（兜底：阈值下仍有巨型表格）。
        if (breakerActive || content.length > STREAM_DEGRADE_CHARS) {
            streamedDegraded.value = true
            remember(content.length / 2000) {
                val summary = ContentDiag.summarize(content)
                AppLogger.info(
                    "Perf",
                    "[Perf][ContentDiag] stream-degrade key=$stableKey breaker=$breakerActive " +
                        "threshold=$STREAM_DEGRADE_CHARS ${summary.asLogFields()}",
                )
                Unit
            }
            StreamingTailView(content)
            return
        }
    }

    if (!shouldCollapse(content, isStreaming)) {
        // 流式降级过的块在回合结束这一刻跑完整 markdown——把形状记下来，
        // 它正是降级时防着的那段解析。
        if (!isStreaming && streamedDegraded.value) {
            streamedDegraded.value = false
            remember(stableKey, content.length) {
                AppLogger.info(
                    "Perf",
                    "[Perf][ContentDiag] stream-end-swap key=$stableKey " +
                        ContentDiag.summarize(content).asLogFields(),
                )
                Unit
            }
        }
        // 大消息注册成"正在渲染"，HangDetector 采样时能对上号。
        if (content.length >= CONTENT_DIAG_MIN_CHARS) {
            val messageId = remember(stableKey) {
                stableKey.substringAfter(':').substringBefore(':')
            }
            DisposableEffect(stableKey, content.length) {
                ContentDiag.setCurrentRender(
                    sessionId = "",
                    messageId = messageId,
                    summary = ContentDiag.summarize(content),
                )
                onDispose { ContentDiag.clearCurrentRender(messageId) }
            }
        }
        renderer()
        return
    }

    var expanded by rememberSaveable(stableKey) { mutableStateOf(false) }
    remember(stableKey) {
        AppLogger.info(
            "LargeContentGuard",
            "collapse key=$stableKey chars=${content.length} threshold=$LARGE_MESSAGE_THRESHOLD_CHARS",
        )
        Unit
    }
    if (expanded) renderer()
    else CollapsedLargeContent(content = content, stableKey = stableKey) { expanded = true }
}

// ── 折叠徽标 ────────────────────────────────────────────────────────────────

@Composable
internal fun LargeContentBadge(
    content: String,
    stableKey: String,
    onExpand: () -> Unit,
) = CollapsedLargeContent(content, stableKey, onExpand)

@Composable
private fun CollapsedLargeContent(
    content: String,
    stableKey: String,
    onExpand: () -> Unit,
) {
    val context = LocalContext.current
    val preview = remember(content) {
        if (content.length <= LARGE_MESSAGE_PREVIEW_CHARS) content
        else content.take(LARGE_MESSAGE_PREVIEW_CHARS) + "…"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(NovexColors.SurfaceMuted, RoundedCornerShape(NovexDimensions.SectionRadius))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(preview, style = NovexType.Body, color = NovexColors.Text.copy(alpha = 0.85f))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.chat_message_large_collapsed_title, humanSize(content.length)),
                style = NovexType.Metadata,
                color = NovexColors.SecondaryText,
                modifier = Modifier.padding(end = 8.dp),
            )
            TextButton(
                onClick = {
                    AppLogger.info(
                        "LargeContentGuard",
                        "expand requested key=$stableKey chars=${content.length}",
                    )
                    onExpand()
                },
                modifier = Modifier.padding(0.dp),
            ) {
                Icon(NovexIcons.UnfoldMore, null, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.chat_message_large_expand),
                    style = NovexType.Metadata.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            TextButton(
                onClick = {
                    val ok = exportToShareFile(context, content, stableKey)
                    val text = context.getString(
                        if (ok) R.string.chat_message_large_export_ok
                        else R.string.chat_message_large_export_fail,
                    )
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.padding(0.dp),
            ) {
                Icon(NovexIcons.IosShare, null, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.chat_message_large_export),
                    style = NovexType.Metadata.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

// ── 流式降级视图：单行提示 + 尾部窗口纯文本 ─────────────────────────────────

@Composable
private fun StreamingTailView(content: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.chat_stream_degraded_notice),
            style = NovexType.Metadata,
            color = NovexColors.SecondaryText,
        )
        Text(
            if (content.length > DEGRADED_TAIL_CHARS) "…" + content.takeLast(DEGRADED_TAIL_CHARS)
            else content,
            style = NovexType.Body,
            color = NovexColors.Text,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ── 工具 ────────────────────────────────────────────────────────────────────

/** 字符数当字节数估个大小标签（"32 KB"/"1.2 MB"），够说明"这东西很大"。 */
private fun humanSize(chars: Int): String = when {
    chars >= 1_000_000 -> "%.1f MB".format(java.util.Locale.US, chars / 1_000_000.0)
    chars >= 1_000 -> "${chars / 1_000} KB"
    else -> "$chars B"
}

/** 写到 cacheDir/large-messages 并拉起 ACTION_SEND 分享面板；成功返 true。 */
private fun exportToShareFile(context: Context, content: String, stableKey: String): Boolean =
    runCatching {
        val dir = File(context.cacheDir, "large-messages").apply { mkdirs() }
        val safeKey = stableKey.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
        val file = File(dir, "msg-$safeKey-${System.currentTimeMillis()}.txt")
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(send, context.getString(R.string.chat_message_large_export))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.onFailure {
        AppLogger.warning("LargeContentGuard", "export failed: ${it.message}")
    }.getOrDefault(false)
