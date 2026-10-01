package com.openminis.app.ui.settings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.android.data.chat.ChatDao
import novex.android.data.chat.SessionRow
import novex.android.ui.AlertDialog
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import java.io.File

private class SessionStorage(
    val id: String,
    val title: String?,
    val sandboxBytes: Long,
    val mediaBytes: Long,
) {
    val totalBytes: Long get() = sandboxBytes + mediaBytes
}

// ── 磁盘占用采样（IO 线程调用）──────────────────────────────────────────────

private fun dirBytes(dir: File): Long =
    if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

private fun dbBytes(context: Context): Long {
    val db = context.getDatabasePath("minis.db")
    return listOf(db, File(db.path + "-wal"), File(db.path + "-shm"))
        .filter { it.exists() }
        .sumOf { it.length() }
}

private fun mediaBytesBySession(mediaRoot: File, sessionIds: Set<String>): Map<String, Long> {
    if (!mediaRoot.exists()) return emptyMap()
    return mediaRoot.walkTopDown()
        .filter { it.isFile }
        .mapNotNull { f -> f.parentFile?.name?.takeIf(sessionIds::contains)?.let { it to f.length() } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, v) -> v.sum() }
}

private fun purgeSessionMedia(mediaRoot: File, sessionId: String) {
    if (!mediaRoot.exists()) return
    mediaRoot.walkTopDown()
        .filter { it.isDirectory && it.name == sessionId }
        .forEach { it.deleteRecursively() }
}

// ── 总览页 ──────────────────────────────────────────────────────────────────

@Composable
fun StorageManagementScreen(
    chatDao: ChatDao,
    onBack: () -> Unit,
    onSessionClick: (sessionId: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var dbSize by remember { mutableLongStateOf(0L) }
    // 卡片数据拆两桶：revisions/ 每次保存累积无上限，是占用主嫌，单独可见。
    var cardContentSize by remember { mutableLongStateOf(0L) }
    var cardRevisionSize by remember { mutableLongStateOf(0L) }
    var sessionRows by remember { mutableStateOf<List<SessionStorage>>(emptyList()) }

    fun rescan() {
        scope.launch {
            loading = true
            withContext(Dispatchers.IO) {
                dbSize = dbBytes(context)

                val cardDir = File(context.filesDir, "rewrite-content")
                val revisions = dirBytes(File(cardDir, "revisions"))
                cardRevisionSize = revisions
                cardContentSize = (dirBytes(cardDir) - revisions).coerceAtLeast(0L)

                val allSessions = chatDao.primarySessions()
                val mediaBySession = mediaBytesBySession(
                    File(context.filesDir, "media"),
                    allSessions.map { it.id }.toSet(),
                )
                val sandboxRoot = File(context.filesDir, "minis-sessions")
                sessionRows = allSessions
                    .map { s ->
                        SessionStorage(
                            id = s.id,
                            title = s.title,
                            sandboxBytes = dirBytes(File(sandboxRoot, s.id)),
                            mediaBytes = mediaBySession[s.id] ?: 0L,
                        )
                    }
                    .sortedByDescending { it.totalBytes }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { rescan() }

    val sessionTotal = sessionRows.sumOf { it.totalBytes }

    SettingsScaffold(title = stringResource(R.string.storage_title), onBack = onBack) {
        SettingsSection(header = stringResource(R.string.storage_section_overview)) {
            val overview = listOf(
                Triple(Color(0xFF007AFF), R.string.storage_overview_database, dbSize),
                Triple(Color(0xFF5856D6), R.string.storage_overview_sessions, sessionTotal),
                Triple(Color(0xFF34C759), R.string.storage_overview_card_content, cardContentSize),
                Triple(Color(0xFFFF9500), R.string.storage_overview_card_revisions, cardRevisionSize),
            )
            overview.forEachIndexed { i, (color, labelRes, bytes) ->
                OverviewStatRow(
                    swatch = color,
                    label = stringResource(labelRes),
                    value = Formatter.formatFileSize(context, bytes),
                    showDivider = i < overview.lastIndex,
                )
            }
        }

        SettingsSection(header = stringResource(R.string.storage_section_sessions)) {
            when {
                loading -> Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                sessionRows.isEmpty() -> Text(
                    stringResource(R.string.storage_no_sessions),
                    style = NovexType.Body,
                    color = NovexColors.SecondaryText,
                    modifier = Modifier.padding(16.dp),
                )
                else -> sessionRows.forEachIndexed { i, row ->
                    SettingsValueRow(
                        title = row.title ?: "Untitled",
                        value = Formatter.formatFileSize(context, row.totalBytes),
                        onClick = { onSessionClick(row.id) },
                        showDivider = i < sessionRows.lastIndex,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── 单会话详情页 ─────────────────────────────────────────────────────────────

@Composable
fun SessionStorageDetailScreen(
    sessionId: String,
    chatDao: ChatDao,
    onBack: () -> Unit,
    onBrowseFiles: (rootPath: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<SessionRow?>(null) }
    var sandboxSize by remember { mutableLongStateOf(0L) }
    var mediaSize by remember { mutableLongStateOf(0L) }
    var clearing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val sandboxRoot = File(context.filesDir, "minis-sessions")
    val mediaRoot = File(context.filesDir, "media")

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            session = chatDao.sessionById(sessionId)
            sandboxSize = dirBytes(File(sandboxRoot, sessionId))
            mediaSize = mediaBytesBySession(mediaRoot, setOf(sessionId))[sessionId] ?: 0L
        }
    }

    val totalSize = sandboxSize + mediaSize
    val hasFiles = totalSize > 0

    SettingsScaffold(title = session?.title ?: "Session", onBack = onBack) {
        SettingsSection(header = stringResource(R.string.storage_section_minis_files)) {
            if (sandboxSize > 0) {
                SettingsValueRow(
                    title = stringResource(R.string.storage_browse_files),
                    value = Formatter.formatFileSize(context, sandboxSize),
                    onClick = { onBrowseFiles(File(sandboxRoot, sessionId).absolutePath) },
                    valueColor = NovexColors.SecondaryText,
                    showDivider = false,
                )
            } else {
                Text(
                    stringResource(R.string.storage_no_minis_files),
                    style = NovexType.Body,
                    color = NovexColors.SecondaryText,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        SettingsSection(header = stringResource(R.string.storage_section_media)) {
            if (mediaSize > 0) {
                SettingsValueRow(
                    title = "Media",
                    value = Formatter.formatFileSize(context, mediaSize),
                    showDivider = false,
                )
            } else {
                Text(
                    stringResource(R.string.storage_no_media_files),
                    style = NovexType.Body,
                    color = NovexColors.SecondaryText,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        SettingsSection(footer = stringResource(R.string.storage_clear_session_footer)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasFiles && !clearing) { confirmClear = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (clearing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.storage_clearing_status),
                        style = NovexType.ItemTitle,
                        color = NovexColors.Danger,
                    )
                } else {
                    Text(
                        stringResource(R.string.storage_clear_session_button),
                        style = NovexType.ItemTitle,
                        color = if (hasFiles) NovexColors.Danger else NovexColors.TertiaryText,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasFiles) {
                        Text(
                            Formatter.formatFileSize(context, totalSize),
                            style = NovexType.Body,
                            color = NovexColors.SecondaryText,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.storage_clear_confirm_title)) },
            text = {
                Text("This will delete ${Formatter.formatFileSize(context, totalSize)} of files. This action cannot be undone.")
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    confirmClear = false
                    clearing = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            File(sandboxRoot, sessionId).deleteRecursively()
                            purgeSessionMedia(mediaRoot, sessionId)
                        }
                        sandboxSize = 0L
                        mediaSize = 0L
                        clearing = false
                    }
                }) {
                    Text(
                        "Clear ${Formatter.formatFileSize(context, totalSize)}",
                        color = NovexColors.Danger,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun OverviewStatRow(
    swatch: Color,
    label: String,
    value: String,
    showDivider: Boolean,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(21.dp).clip(CircleShape).background(swatch))
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = NovexType.ItemTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(value, style = NovexType.Body, color = NovexColors.SecondaryText)
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(NovexDimensions.Hairline)
                    .background(NovexColors.Divider),
            )
        }
    }
}
