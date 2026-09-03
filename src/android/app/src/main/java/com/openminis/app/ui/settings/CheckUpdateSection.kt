package com.openminis.app.ui.settings

import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.ClickableText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.UpdateChannel
import com.openminis.app.data.UpdateChecker
import com.openminis.app.data.NovexUpdateMonitor
import com.openminis.app.ui.markdown.MarkdownText
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

internal object NovexUpdateAnnouncementStore {
    private const val PREFS = "novex_update_announcement"
    private const val LAST_SHOWN = "last_shown_release"

    fun shouldShow(context: Context, update: UpdateChecker.CheckResult.UpdateAvailable): Boolean {
        val releaseKey = "${update.channel.name}:${update.versionName}"
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_SHOWN, null) != releaseKey
    }

    fun markShown(context: Context, update: UpdateChecker.CheckResult.UpdateAvailable) {
        val releaseKey = "${update.channel.name}:${update.versionName}"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_SHOWN, releaseKey)
            .apply()
    }
}

internal object CurrentNovexAnnouncement {
    const val title = "特别致哀"
    val paragraphs = listOf(
        "今年以来，台风、暴雨、洪涝与地质灾害侵袭祖国多地。每一则伤亡消息背后，都是一个家庭难以承受的离别。",
        "在西藏吉隆泥石流灾害发生之际，我们也一并向今年所有灾害中的遇难者致以沉痛哀悼，向遇难者家属和受灾群众致以深切慰问，向所有奋战在抢险救援一线的人们致以崇高敬意。",
        "愿逝者安息，愿伤者康复，愿失联者早日归来，愿所有受灾群众平安渡过难关，重建家园。",
    )
    const val closing = "愿山河无恙，愿人间皆安"
    val markdown: String
        get() = paragraphs.joinToString("\n\n") + "\n\n**$closing**"
}

internal enum class NovexHomeAction { ANNOUNCEMENT, UPDATE }

internal fun resolveNovexHomeAction(
    detectedVersion: String?,
    dismissedVersion: String?,
): NovexHomeAction = if (detectedVersion != null && detectedVersion != dismissedVersion) {
    NovexHomeAction.UPDATE
} else {
    NovexHomeAction.ANNOUNCEMENT
}

/**
 * Settings section that talks to [UpdateChecker] to surface a "Check for
 * Updates" affordance. Drop in anywhere — typically the bottom of an About
 * screen — and it owns its own state, dialogs, and download UI.
 *
 * The section is no-op visible: a button + transient status text. When an
 * update is found we open a modal AlertDialog showing the changelog and a
 * Download button; the dialog stays open through the download so the user
 * can watch progress.
 */
@Composable
fun CheckUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // When the GitHub API returns 403 / 451 we surface a dedicated row with a
    // tappable "Open GitHub Releases" link beneath the row, so users behind a
    // geo-block know what to do without hunting for the URL themselves.
    var showReleasesLink by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.CheckResult.UpdateAvailable?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var awaitingInstallPerm by remember { mutableStateOf(false) }

    // Resume the install flow on every ON_RESUME. There are two cases:
    //
    //  1. Composable state survived — `update` and `awaitingInstallPerm` are
    //     still set. We just need to flip awaitingInstallPerm off (so the
    //     dialog stops showing the "permission required" message) and, if a
    //     persisted APK is intact, fire the installer directly.
    //
    //  2. Activity recreate happened — every `remember{}` slot above is back
    //     to its default. The only thing that knows we were mid-flow is
    //     PendingUpdateStore. We rehydrate by calling resumablePendingFile()
    //     and, when permission is granted, fire the installer. We do NOT
    //     re-open the update dialog in this case because there's no
    //     CheckResult to populate it; the install intent is enough.
    //
    // Either way: if permission is still denied we leave the pending record
    // alone so the next resume can pick it up.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (!UpdateChecker.canInstall(context)) return@LifecycleEventObserver
            awaitingInstallPerm = false
            val pendingFile = UpdateChecker.resumablePendingFile(context) ?: return@LifecycleEventObserver
            val launched = UpdateChecker.installApk(context, pendingFile)
            if (launched) {
                // Dismiss any leftover dialog state; the system installer is
                // now in charge.
                update = null
                downloadProgress = null
                downloadError = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    SettingsSection(
        header = stringResource(R.string.check_update_section_header),
        footer = stringResource(
            R.string.check_update_current_version_channel,
            BuildConfig.VERSION_NAME,
            updateChannelLabel(UpdateChecker.currentChannel),
        ),
    ) {
        SettingsRow(
            icon = Icons.Outlined.SystemUpdate,
            iconColor = Color(0xFF007AFF),
            title = stringResource(
                if (checking) R.string.check_update_checking
                else R.string.check_update_check_button
            ),
            subtitle = statusMessage,
            showDivider = false,
            onClick = if (checking) null else {
                {
                    checking = true
                    statusMessage = null
                    showReleasesLink = false
                    scope.launch {
                        when (val r = UpdateChecker.check()) {
                            is UpdateChecker.CheckResult.UpdateAvailable -> {
                                update = r
                                statusMessage = null
                            }
                            UpdateChecker.CheckResult.UpToDate ->
                                statusMessage = context.getString(R.string.check_update_up_to_date)
                            UpdateChecker.CheckResult.NoReleaseAvailable ->
                                statusMessage = context.getString(R.string.check_update_no_release)
                            is UpdateChecker.CheckResult.NoApkAsset ->
                                statusMessage = context.getString(R.string.check_update_no_apk_asset, r.tagName)
                            UpdateChecker.CheckResult.Forbidden -> {
                                statusMessage = context.getString(R.string.update_error_forbidden_with_link)
                                showReleasesLink = true
                            }
                            UpdateChecker.CheckResult.NetworkUnreachable ->
                                statusMessage = context.getString(R.string.update_error_network_unreachable)
                            is UpdateChecker.CheckResult.Error ->
                                statusMessage = context.getString(R.string.check_update_error, r.message)
                        }
                        checking = false
                    }
                }
            },
        )
        if (showReleasesLink) {
            val linkLabel = stringResource(R.string.update_error_open_releases)
            val annotated = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                ) {
                    append(linkLabel)
                }
                addStringAnnotation(
                    tag = "URL",
                    annotation = UpdateChecker.RELEASES_URL,
                    start = 0,
                    end = length,
                )
            }
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()
                        ?.let { ann ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(ann.item))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                },
            )
        }
    }

    update?.let { u ->
        UpdateDialog(
            update = u,
            downloadProgress = downloadProgress,
            downloadError = downloadError,
            needsInstallPerm = awaitingInstallPerm,
            onDownload = {
                downloadError = null
                downloadProgress = 0f
                scope.launch {
                    val result = UpdateChecker.download(
                        context = context,
                        url = u.apkUrl,
                        versionName = u.versionName,
                    ) { p -> downloadProgress = p }
                    when (result) {
                        is UpdateChecker.DownloadResult.Success -> {
                            downloadProgress = null
                            if (UpdateChecker.canInstall(context)) {
                                val ok = UpdateChecker.installApk(context, result.file)
                                if (ok) {
                                    update = null
                                } else {
                                    downloadError = context.getString(R.string.check_update_install_launch_failed)
                                }
                            } else {
                                awaitingInstallPerm = true
                            }
                        }
                        is UpdateChecker.DownloadResult.Error -> {
                            downloadProgress = null
                            downloadError = result.message
                        }
                    }
                }
            },
            onOpenSettings = { UpdateChecker.openInstallPermissionSettings(context) },
            onDismiss = {
                if (downloadProgress == null) {
                    update = null
                    downloadError = null
                    awaitingInstallPerm = false
                }
            },
        )
    }
}

/** Compact home-toolbar variant used by Novex. */
@Composable
fun NovexUpdateAction() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val detectedUpdate by NovexUpdateMonitor.available.collectAsState()
    var dialogUpdate by remember { mutableStateOf<UpdateChecker.CheckResult.UpdateAvailable?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var awaitingInstallPermission by remember { mutableStateOf(false) }
    var announcementOpen by remember { mutableStateOf(false) }
    var dismissedUpdateVersion by remember { mutableStateOf<String?>(null) }

    fun openDetectedUpdateOrCheck() {
        val available = detectedUpdate
        if (available != null) {
            dismissedUpdateVersion = null
            dialogUpdate = available
            NovexUpdateAnnouncementStore.markShown(context, available)
            return
        }
        checking = true
        scope.launch {
            when (val result = NovexUpdateMonitor.refresh()) {
                is UpdateChecker.CheckResult.UpdateAvailable -> {
                    dialogUpdate = result
                    NovexUpdateAnnouncementStore.markShown(context, result)
                }
                UpdateChecker.CheckResult.UpToDate ->
                    android.widget.Toast.makeText(context, "Novex（诺文）已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
                UpdateChecker.CheckResult.NoReleaseAvailable ->
                    android.widget.Toast.makeText(context, "暂无可用的发布版本", android.widget.Toast.LENGTH_SHORT).show()
                is UpdateChecker.CheckResult.NoApkAsset ->
                    android.widget.Toast.makeText(context, "新版本尚未附带安装包", android.widget.Toast.LENGTH_SHORT).show()
                UpdateChecker.CheckResult.Forbidden,
                UpdateChecker.CheckResult.NetworkUnreachable ->
                    android.widget.Toast.makeText(context, "无法连接 GitHub（代码托管平台），请检查网络后重试", android.widget.Toast.LENGTH_LONG).show()
                is UpdateChecker.CheckResult.Error ->
                    android.widget.Toast.makeText(context, "检查更新失败：${result.message}", android.widget.Toast.LENGTH_LONG).show()
            }
            checking = false
        }
    }

    LaunchedEffect(detectedUpdate?.versionName) {
        if (dismissedUpdateVersion != detectedUpdate?.versionName) {
            dismissedUpdateVersion = null
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME || !UpdateChecker.canInstall(context)) {
                return@LifecycleEventObserver
            }
            awaitingInstallPermission = false
            UpdateChecker.resumablePendingFile(context)?.let { file ->
                if (UpdateChecker.installApk(context, file)) {
                    dialogUpdate = null
                    NovexUpdateMonitor.clearAvailable()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    val homeAction = resolveNovexHomeAction(
        detectedVersion = detectedUpdate?.versionName,
        dismissedVersion = dismissedUpdateVersion,
    )

    Row(
        modifier = Modifier
            .clickable(enabled = !checking) {
                if (homeAction == NovexHomeAction.UPDATE) {
                    dialogUpdate = detectedUpdate
                } else {
                    announcementOpen = true
                }
            }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            checking -> CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
            homeAction == NovexHomeAction.UPDATE -> Icon(
                Icons.Outlined.FileDownload,
                contentDescription = "打开 Novex（诺文）更新",
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            else -> Icon(
                Icons.Outlined.Campaign,
                contentDescription = "打开 Novex（诺文）公告",
                modifier = Modifier.size(30.dp),
            )
        }
    }

    if (announcementOpen) {
        AnnouncementDialog(
            checking = checking,
            onCheckUpdate = {
                announcementOpen = false
                openDetectedUpdateOrCheck()
            },
            onDismiss = { announcementOpen = false },
        )
    }

    dialogUpdate?.let { available ->
        UpdateDialog(
            update = available,
            downloadProgress = downloadProgress,
            downloadError = downloadError,
            needsInstallPerm = awaitingInstallPermission,
            onDownload = {
                downloadError = null
                downloadProgress = 0f
                scope.launch {
                    when (val result = UpdateChecker.download(
                        context = context,
                        url = available.apkUrl,
                        versionName = available.versionName,
                    ) { downloadProgress = it }) {
                        is UpdateChecker.DownloadResult.Success -> {
                            downloadProgress = null
                            if (UpdateChecker.canInstall(context)) {
                                if (UpdateChecker.installApk(context, result.file)) {
                                    dialogUpdate = null
                                    NovexUpdateMonitor.clearAvailable()
                                }
                                else downloadError = "无法打开安装界面"
                            } else {
                                awaitingInstallPermission = true
                            }
                        }
                        is UpdateChecker.DownloadResult.Error -> {
                            downloadProgress = null
                            downloadError = result.message
                        }
                    }
                }
            },
            onOpenSettings = { UpdateChecker.openInstallPermissionSettings(context) },
            onDismiss = {
                if (downloadProgress == null) {
                    dismissedUpdateVersion = available.versionName
                    dialogUpdate = null
                    downloadError = null
                    awaitingInstallPermission = false
                }
            },
        )
    }
}

@Composable
private fun UpdateDialog(
    update: UpdateChecker.CheckResult.UpdateAvailable,
    downloadProgress: Float?,
    downloadError: String?,
    needsInstallPerm: Boolean,
    onDownload: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    stringResource(
                        when {
                            update.channel == UpdateChannel.STABLE ->
                                R.string.check_update_available_title_stable
                            !update.isPrerelease ->
                                R.string.check_update_available_title_preview_baseline
                            else ->
                                R.string.check_update_available_title_preview
                        },
                    ),
                )
                Text(
                    stringResource(
                        R.string.check_update_available_subtitle,
                        BuildConfig.VERSION_NAME,
                        update.versionName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    updateChannelLabel(update.channel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (update.channel == UpdateChannel.PREVIEW) {
                    Text(
                        stringResource(
                            if (update.isPrerelease) {
                                R.string.check_update_preview_notice
                            } else {
                                R.string.check_update_preview_baseline_notice
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.check_update_changelog_header).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReleaseNotesList(
                    notes = update.releaseNotes.ifEmpty {
                        listOf(
                            UpdateChecker.ReleaseNote(
                                versionName = update.versionName,
                                releaseName = update.releaseName,
                                changelog = update.changelog.ifBlank {
                                    "更新说明暂未加载。请稍后重新检查，或前往发布页查看完整公告。"
                                },
                            ),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.check_update_install_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.check_update_downloading, (downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (downloadError != null) {
                    Text(
                        stringResource(R.string.check_update_download_failed, downloadError),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (needsInstallPerm) {
                    Text(
                        stringResource(R.string.check_update_install_perm_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (needsInstallPerm) {
                MinisButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.check_update_open_install_settings))
                }
            } else {
                MinisButton(
                    onClick = onDownload,
                    enabled = downloadProgress == null,
                ) {
                    if (downloadProgress != null && downloadProgress < 1f) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp,
                            )
                            Text(stringResource(R.string.check_update_downloading, (downloadProgress * 100).toInt()))
                        }
                    } else {
                        Text(
                            stringResource(
                                if (update.channel == UpdateChannel.PREVIEW) {
                                    R.string.check_update_download_preview_button
                                } else {
                                    R.string.check_update_download_button
                                },
                            ),
                        )
                    }
                }
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss, enabled = downloadProgress == null) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ReleaseNotesList(
    notes: List<UpdateChecker.ReleaseNote>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notes.forEachIndexed { index, note ->
            if (index == 1) {
                Text(
                    "包含的往期更新",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            ReleaseNoteItem(
                note = note,
                latest = index == 0,
            )
        }
    }
}

@Composable
private fun ReleaseNoteItem(
    note: UpdateChecker.ReleaseNote,
    latest: Boolean,
) {
    var expanded by rememberSaveable(note.versionName) { mutableStateOf(latest) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (latest) "本次更新 · ${note.versionName}" else note.versionName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (latest) FontWeight.SemiBold else FontWeight.Medium,
            )
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "收起 ${note.versionName}" else "展开 ${note.versionName}",
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            MarkdownText(
                markdown = note.changelog,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun AnnouncementDialog(
    checking: Boolean,
    onCheckUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(CurrentNovexAnnouncement.title, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MarkdownText(
                    markdown = CurrentNovexAnnouncement.markdown,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            MinisButton(onClick = onCheckUpdate, enabled = !checking) {
                Text("检查更新")
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss, enabled = !checking) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun updateChannelLabel(channel: UpdateChannel): String = stringResource(
    when (channel) {
        UpdateChannel.STABLE -> R.string.update_channel_stable
        UpdateChannel.PREVIEW -> R.string.update_channel_preview
    },
)
