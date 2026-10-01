package com.openminis.app.ui.settings

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.openminis.app.R
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.android.ui.AlertDialog
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import novex.android.ui.Scaffold
import novex.android.ui.TopAppBar
import java.io.File
import java.io.RandomAccessFile

// ── 列表页 ──────────────────────────────────────────────────────────────────

private class LogScan {
    val daily = mutableListOf<AppLogger.LogFileMeta>()
    val crashes = mutableListOf<AppLogger.LogFileMeta>()
    var totalBytes = 0L
}

/** IO 线程里做一次目录扫描：日志上限各 100 条，crash（Java+native）合并按名倒序。 */
private fun scanLogs(): LogScan {
    val scan = LogScan()
    scan.daily += AppLogger.listLogFileMetas(prefix = "minis-", limit = 100)
    scan.crashes += (AppLogger.listLogFileMetas(prefix = "crash-", limit = 100) +
        AppLogger.listLogFileMetas(prefix = "native-crash-", limit = 100))
        .sortedByDescending { it.name }
        .take(100)
    // 总大小只统计展示行（超过 cap 的老文件仍在盘上但不在这个视图里）。
    scan.totalBytes = scan.daily.sumOf { it.sizeBytes } + scan.crashes.sumOf { it.sizeBytes }
    return scan
}

@Composable
fun LogManagementScreen(
    onBack: () -> Unit,
    onLogFileClick: (fileName: String) -> Unit = {},
) {
    val context = LocalContext.current

    var scan by remember { mutableStateOf(LogScan()) }
    var loading by remember { mutableStateOf(true) }
    var confirmingDeleteAll by remember { mutableStateOf(false) }
    var loggingEnabled by remember { mutableStateOf(AppLogger.isEnabled(context)) }
    var reloadTick by remember { mutableIntStateOf(0) }

    // ?tab= 深链参数随 minis-config 退役后只消费不响应。
    remember { DeepLinkCoordinator.consumePendingLogsTab() }

    LaunchedEffect(reloadTick) {
        loading = true
        scan = withContext(Dispatchers.IO) { scanLogs() }
        loading = false
    }

    SettingsScaffold(
        title = stringResource(R.string.log_title),
        onBack = onBack,
        scrollable = false,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            SettingsSection(
                header = stringResource(R.string.log_section_logging),
                footer = stringResource(R.string.log_section_logging_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.log_enable_switch),
                    checked = loggingEnabled,
                    onCheckedChange = {
                        loggingEnabled = it
                        AppLogger.setEnabled(context, it)
                    },
                    showDivider = false,
                )
            }

            // 目录扫描还在跑的时候居中 spinner，避免闪出「暂无日志」。
            if (loading) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                LogFileSection(
                    title = stringResource(R.string.log_section_files),
                    footer = stringResource(R.string.log_section_files_footer),
                    files = scan.daily,
                    emptyLabel = stringResource(R.string.log_no_files),
                    context = context,
                    onFileClick = onLogFileClick,
                )
                if (scan.crashes.isNotEmpty()) {
                    LogFileSection(
                        title = "Crash Logs",
                        footer = "Most recent ${scan.crashes.size} crash report(s) (Java/Kotlin + native).",
                        files = scan.crashes,
                        emptyLabel = null,
                        context = context,
                        onFileClick = onLogFileClick,
                    )
                }
                if (scan.daily.isNotEmpty() || scan.crashes.isNotEmpty()) {
                    SettingsSection(
                        header = stringResource(R.string.log_section_storage),
                        footer = stringResource(
                            R.string.log_section_storage_footer,
                            Formatter.formatFileSize(context, scan.totalBytes),
                        ),
                    ) {
                        SettingsRow(
                            title = stringResource(R.string.log_delete_all_title),
                            titleColor = NovexColors.Danger,
                            onClick = { confirmingDeleteAll = true },
                            showChevron = false,
                            showDivider = false,
                            trailing = {
                                Text(
                                    Formatter.formatFileSize(context, scan.totalBytes),
                                    style = NovexType.Body,
                                    color = NovexColors.SecondaryText,
                                )
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmingDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmingDeleteAll = false },
            title = { Text(stringResource(R.string.log_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.log_delete_confirm_text,
                        Formatter.formatFileSize(context, scan.totalBytes),
                    ),
                )
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    AppLogger.clearLogs()
                    reloadTick++
                    confirmingDeleteAll = false
                }) {
                    Text(stringResource(R.string.log_delete_confirm), color = NovexColors.Danger)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { confirmingDeleteAll = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun LogFileSection(
    title: String,
    footer: String,
    files: List<AppLogger.LogFileMeta>,
    emptyLabel: String?,
    context: Context,
    onFileClick: (String) -> Unit,
) {
    SettingsSection(header = title, footer = footer) {
        if (files.isEmpty()) {
            emptyLabel?.let {
                SettingsValueRow(
                    title = it,
                    value = "",
                    valueColor = NovexColors.SecondaryText,
                    showDivider = false,
                )
            }
        } else {
            files.forEachIndexed { i, meta ->
                SettingsValueRow(
                    title = meta.name,
                    value = Formatter.formatFileSize(context, meta.sizeBytes),
                    onClick = { onFileClick(meta.name) },
                    showDivider = i < files.lastIndex,
                )
            }
        }
    }
}

// ── 详情页：行偏移索引 + 按需读取 ─────────────────────────────────────────────

/**
 * 日志详情按需读取器：整文件 readText() 在 33MB 日志上直接 OOM/ANR。
 * 过一遍文件记下每个换行的字节偏移，readLine 用 RAF 定点 seek，
 * LazyColumn 只渲染可视行；离开页面时 DisposableEffect 关掉句柄。
 */
private class LogLineIndex private constructor(
    private val raf: RandomAccessFile,
    private val offsets: LongArray,
) {
    val lineCount: Int get() = offsets.size

    @Synchronized
    fun lineAt(index: Int): String {
        if (index !in offsets.indices) return ""
        return runCatching {
            raf.seek(offsets[index])
            // RAF.readLine 按字节读会切碎 UTF-8；手工攒到下一个 \n 再解码。
            val chunk = ByteArray(8 * 1024)
            val out = StringBuilder()
            outer@ while (true) {
                val n = raf.read(chunk)
                if (n <= 0) break@outer
                val nl = (0 until n).firstOrNull { chunk[it] == '\n'.code.toByte() }
                if (nl != null) {
                    out.append(String(chunk, 0, nl, Charsets.UTF_8))
                    break@outer
                }
                out.append(String(chunk, 0, n, Charsets.UTF_8))
            }
            out.toString()
        }.getOrDefault("")
    }

    fun close() = runCatching { raf.close() }

    companion object {
        fun open(file: File): LogLineIndex {
            val raf = RandomAccessFile(file, "r")
            val marks = mutableListOf(0L)
            val chunk = ByteArray(64 * 1024)
            var cursor = 0L
            while (true) {
                val n = raf.read(chunk)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (chunk[i] == '\n'.code.toByte()) marks += cursor + i + 1
                }
                cursor += n
            }
            // 文件以 \n 结尾时丢掉末尾那个指向 EOF 的空行偏移。
            val trimmed = if (marks.size > 1 && marks.last() >= raf.length()) {
                marks.dropLast(1)
            } else marks
            raf.seek(0)
            return LogLineIndex(raf, trimmed.toLongArray())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDetailScreen(
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var index by remember(fileName) { mutableStateOf<LogLineIndex?>(null) }
    var error by remember(fileName) { mutableStateOf<String?>(null) }
    var loading by remember(fileName) { mutableStateOf(true) }

    LaunchedEffect(fileName) {
        loading = true
        error = null
        index = withContext(Dispatchers.IO) {
            val file = File(File(context.filesDir, "logs"), fileName)
            when {
                !file.exists() -> {
                    error = context.getString(R.string.log_not_found)
                    null
                }
                else -> runCatching { LogLineIndex.open(file) }
                    .onFailure { error = context.getString(R.string.log_error_reading, it.message ?: "") }
                    .getOrNull()
            }
        }
        loading = false
    }

    DisposableEffect(fileName) {
        onDispose { index?.close() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NovexIcons.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val file = File(File(context.filesDir, "logs"), fileName)
                        if (file.exists()) shareLogFile(context, file)
                    }) {
                        Icon(NovexIcons.Share, stringResource(R.string.common_share))
                    }
                },
            )
        },
    ) { padding ->
        val log = index
        when {
            loading -> LogDetailPlaceholder(padding) { CircularProgressIndicator() }
            error != null -> LogDetailPlaceholder(padding) {
                Text(
                    error.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = NovexColors.Danger,
                    modifier = Modifier.padding(12.dp),
                )
            }
            log != null -> LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
            ) {
                items(log.lineCount) { i ->
                    Text(
                        log.lineAt(i),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = NovexColors.Text,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LogDetailPlaceholder(
    padding: androidx.compose.foundation.layout.PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ── 分享 ────────────────────────────────────────────────────────────────────

private fun shareLogFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData 里再带一次 URI：部分 OEM 的选择器宿主只看 clip 授权。
            clipData = android.content.ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.log_share_chooser))
        // 授权旗标在外层 chooser 上也要再打一遍，否则部分系统收不到。
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }.onFailure { e ->
        // FileProvider 失败时降级成正文文本（截断 100k），并留日志可查因。
        AppLogger.warning(
            "LogShare",
            "FileProvider share failed for ${file.name}: ${e.message} — falling back to EXTRA_TEXT",
        )
        val text = runCatching { file.readText().take(100_000) }.getOrNull() ?: return@onFailure
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.log_share_chooser)))
    }
}
