package com.openminis.app.ui.sandbox

import com.openminis.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// 文本族预览：plain/markdown/json/csv 共享「带截断上限的 UTF-8 读取 →
// 等宽滚动展示」管线。

/** 读取结果：内容 + 是否被 PREVIEW_TEXT_CAP_BYTES 截断。 */
private class CappedText(val text: String, val truncated: Boolean)

private fun File.readCappedUtf8(): CappedText {
    val bytes = readBytes()
    return if (bytes.size > PREVIEW_TEXT_CAP_BYTES) {
        CappedText(String(bytes, 0, PREVIEW_TEXT_CAP_BYTES, Charsets.UTF_8), true)
    } else {
        CappedText(String(bytes, Charsets.UTF_8), false)
    }
}

/** 截断提示条：展示前 N KB / 实际大小。 */
@Composable
private fun TruncationBanner(item: FileItem, shown: String = "${PREVIEW_TEXT_CAP_BYTES / 1000} KB") {
    Text(
        text = "Showing first $shown of ${item.formattedSize}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    HorizontalDivider()
}

/** 等宽双轴滚动文本体。 */
@Composable
private fun MonoScrollText(text: String) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                ),
                softWrap = false,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

/** 通用「异步产出内容 → 三态渲染」的预览骨架。 */
@Composable
private fun <T> AsyncFileBody(
    item: FileItem,
    load: suspend () -> T,
    content: @Composable (T) -> Unit,
) {
    var result by remember(item.file) { mutableStateOf<T?>(null) }
    var error by remember(item.file) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.file) {
        withContext(Dispatchers.IO) {
            try {
                result = load()
            } catch (e: Exception) {
                error = e.message ?: "Failed to read file"
            }
        }
    }

    when {
        error != null -> PreviewStatusMessage(error!!, isError = true)
        result != null -> content(result!!)
        else -> LoadingStatus()
    }
}

// ── 纯文本 / 代码 ────────────────────────────────────────────────────────────

@Composable
internal fun PlainTextFileBody(item: FileItem) {
    AsyncFileBody(item, load = { item.file.readCappedUtf8() }) { capped ->
        Column(Modifier.fillMaxSize()) {
            if (capped.truncated) TruncationBanner(item)
            MonoScrollText(capped.text)
        }
    }
}

// ── Markdown ─────────────────────────────────────────────────────────────────

@Composable
internal fun MarkdownFileBody(item: FileItem) {
    AsyncFileBody(item, load = { item.file.readCappedUtf8().text }) { text ->
        // T285-md: MarkdownDocument 用 LazyColumn 只组可视块——首个帧不跑
        // 全文 parseInline，会话→预览过渡不被主线程扫描卡住。
        com.openminis.app.ui.chat.MarkdownDocument(
            content = text,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

// ── JSON pretty-print ────────────────────────────────────────────────────────

@Composable
internal fun JsonFileBody(item: FileItem) {
    AsyncFileBody(item, load = {
        val capped = item.file.readCappedUtf8()
        val pretty = try {
            when (capped.text.trimStart().firstOrNull()) {
                '{' -> org.json.JSONObject(capped.text).toString(2)
                '[' -> org.json.JSONArray(capped.text).toString(2)
                else -> capped.text
            }
        } catch (_: Exception) {
            capped.text
        }
        CappedText(pretty, capped.truncated)
    }) { capped ->
        Column(Modifier.fillMaxSize()) {
            if (capped.truncated) TruncationBanner(item)
            MonoScrollText(capped.text)
        }
    }
}

// ── CSV / TSV ────────────────────────────────────────────────────────────────

private const val CSV_ROW_LIMIT = 200
private const val CSV_CELL_WIDTH_DP = 140

@Composable
internal fun CsvFileBody(item: FileItem) {
    AsyncFileBody(item, load = {
        val sep = if (item.file.extension.equals("tsv", true)) '\t' else ','
        val parsed = mutableListOf<List<String>>()
        var hitLimit = false
        item.file.bufferedReader(Charsets.UTF_8).use { br ->
            while (parsed.size < CSV_ROW_LIMIT) {
                val line = br.readLine() ?: break
                parsed.add(splitDelimitedLine(line, sep))
            }
            hitLimit = br.readLine() != null
        }
        CsvGrid(parsed, hitLimit)
    }) { grid ->
        Column(Modifier.fillMaxSize()) {
            if (grid.truncated) {
                Text(
                    text = "Showing first $CSV_ROW_LIMIT rows of ${item.formattedSize}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                HorizontalDivider()
            }
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                items(grid.rows.size) { rowIdx ->
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        for (cell in grid.rows[rowIdx]) {
                            Text(
                                text = cell,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = if (rowIdx == 0) FontWeight.Bold else FontWeight.Normal,
                                ),
                                modifier = Modifier.padding(end = 16.dp).width(CSV_CELL_WIDTH_DP.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    if (rowIdx == 0) HorizontalDivider()
                }
            }
        }
    }
}

private class CsvGrid(val rows: List<List<String>>, val truncated: Boolean)

private fun splitDelimitedLine(line: String, sep: Char): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            // RFC4180 的 "" 转义成字面引号。
            quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                field.append('"')
                i += 2
                continue
            }
            c == '"' -> quoted = !quoted
            c == sep && !quoted -> {
                fields.add(field.toString())
                field.clear()
            }
            else -> field.append(c)
        }
        i++
    }
    fields.add(field.toString())
    return fields
}
