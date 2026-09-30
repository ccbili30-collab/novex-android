package com.openminis.app.ui.settings

import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.NovexDimensions
import com.openminis.app.ui.novex.NovexIconAction
import com.openminis.app.ui.novex.NovexIcons
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
import androidx.compose.ui.res.painterResource
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
import com.openminis.app.data.NovexAnnouncement
import com.openminis.app.data.NovexBulletin
import com.openminis.app.data.UpdateChannel
import com.openminis.app.data.UpdateChecker
import com.openminis.app.data.NovexUpdateMonitor
import com.openminis.app.ui.markdown.MarkdownText
import androidx.compose.foundation.shape.CircleShape
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.bulletin.BulletinStackFace
import com.openminis.app.ui.bulletin.BulletinHubPage
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
    // 下载态来自进程级 NovexUpdateDownload——离开本页/关弹窗不取消下载。
    val downloadState by com.openminis.app.data.NovexUpdateDownload.state.collectAsState()
    val downloadProgress = (downloadState as? com.openminis.app.data.NovexUpdateDownload.State.Downloading)?.progress
    var installError by remember { mutableStateOf<String?>(null) }
    val downloadError = installError
        ?: (downloadState as? com.openminis.app.data.NovexUpdateDownload.State.Failed)?.message
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
                installError = null
                com.openminis.app.data.NovexUpdateDownload.reset()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    // 下载完成 → 自动续接安装：有权直接拉起安装器，没权弹权限提示。
    LaunchedEffect(downloadState) {
        if (downloadState !is com.openminis.app.data.NovexUpdateDownload.State.Downloaded) return@LaunchedEffect
        if (update == null) return@LaunchedEffect
        if (com.openminis.app.data.NovexUpdateDownload.install(context)) {
            update = null
            installError = null
        } else if (!UpdateChecker.canInstall(context)) {
            awaitingInstallPerm = true
        } else {
            installError = context.getString(R.string.check_update_install_launch_failed)
        }
    }

    SettingsSection(
        header = stringResource(R.string.check_update_section_header),
        footer = stringResource(
            R.string.check_update_current_version_channel,
            BuildConfig.VERSION_NAME,
            updateChannelLabel(UpdateChecker.currentChannel),
        ),
    ) {
        // [T-dual-update-source] 更新源切换（默认 Gitee/国内；GitHub/海外走
        // 既有链路）。切换即清除本轮检查状态，下一次检查从新源取。
        var updateSource by remember { mutableStateOf(com.openminis.app.data.UpdateSourceStore.current()) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UpdateSourcePill(
                selected = updateSource == com.openminis.app.data.UpdateSource.GITEE,
                label = stringResource(R.string.update_source_gitee),
                iconRes = R.drawable.ic_gitee,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (updateSource != com.openminis.app.data.UpdateSource.GITEE) {
                        com.openminis.app.data.UpdateSourceStore.set(context, com.openminis.app.data.UpdateSource.GITEE)
                        updateSource = com.openminis.app.data.UpdateSource.GITEE
                        statusMessage = null; update = null; showReleasesLink = false
                    }
                },
            )
            UpdateSourcePill(
                selected = updateSource == com.openminis.app.data.UpdateSource.GITHUB,
                label = stringResource(R.string.update_source_github),
                iconRes = R.drawable.ic_github,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (updateSource != com.openminis.app.data.UpdateSource.GITHUB) {
                        com.openminis.app.data.UpdateSourceStore.set(context, com.openminis.app.data.UpdateSource.GITHUB)
                        updateSource = com.openminis.app.data.UpdateSource.GITHUB
                        statusMessage = null; update = null; showReleasesLink = false
                    }
                },
            )
        }
        SettingsRow(
            icon = com.openminis.app.ui.novex.NovexIcons.SystemUpdate,
            iconColor = NovexColors.Text,
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
                installError = null
                com.openminis.app.data.NovexUpdateDownload.start(context, u)
            },
            onOpenSettings = { UpdateChecker.openInstallPermissionSettings(context) },
            onDismiss = {
                if (downloadProgress == null) {
                    update = null
                    installError = null
                    awaitingInstallPerm = false
                }
            },
        )
    }
}

/** 公告中心打开状态：入口图标（我的页）与宿主（根页面）共享同一个持有者。 */
internal class NovexUpdateHub {
    var hubOpen by mutableStateOf(false)
}

/**
 * Compact home-toolbar variant used by Novex.
 * [T-bulletin-v3] 入口=叠卡图形（带红点）；跳脸=卡片交叠（公告在上、更新在下，
 * 单新单卡）；点开=公告中心全屏页。下载/安装流程沿用既有 UpdateDialog。
 *
 * 新根导航拆分后由 [NovexUpdateHost] + [NovexUpdateEntry] 承担；此处保留组合
 * 版本供尚未迁移的旧入口（embedded 根等）继续用。
 */
@Composable
fun NovexUpdateAction() {
    val hub = remember { NovexUpdateHub() }
    NovexUpdateEntry(hub)
    NovexUpdateHost(hub)
}

/**
 * 入口：公告用 megaphone 图标（与底栏「消息」的铃铛区分），点击打开公告
 * 中心。角标逻辑不变（bulletin.hasBadge || 检测到更新）。不再复用
 * BulletinEntryIcon —— 那个是铃铛。
 */
@Composable
internal fun NovexUpdateEntry(hub: NovexUpdateHub) {
    val detectedUpdate by NovexUpdateMonitor.available.collectAsState()
    val bulletin by com.openminis.app.data.NovexBulletinMonitor.state.collectAsState()
    val hasBadge = bulletin.hasBadge || detectedUpdate != null
    Box(contentAlignment = Alignment.Center) {
        NovexIconAction(
            icon = com.openminis.app.R.drawable.ic_phosphor_megaphone,
            contentDescription = "打开 Novex（诺文）公告",
            onClick = { hub.hubOpen = true },
        )
        if (hasBadge) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-10).dp, y = 10.dp)
                    .size(8.dp)
                    .background(NovexColors.Danger, CircleShape),
            )
        }
    }
}

/**
 * 宿主：始终挂在根页面组合中，负责冷启动叠卡弹窗、公告中心、更新对话框与
 * ON_RESUME 安装续接。不绘制任何常驻可见内容。
 */
@Composable
internal fun NovexUpdateHost(hub: NovexUpdateHub) {
    val context = LocalContext.current
    val bulletin by com.openminis.app.data.NovexBulletinMonitor.state.collectAsState()
    // 应用级下载态：关公告不取消、重开可见「安装」。
    val downloadState by com.openminis.app.data.NovexUpdateDownload.state.collectAsState()

    // 冷启动恢复：上轮已下完未装的 APK → 公告中心重开直接显示「安装」。
    LaunchedEffect(Unit) {
        com.openminis.app.data.NovexUpdateDownload.hydrateFromPending(context)
    }

    // Resume the install flow on ON_RESUME (permission granted or pending APK intact).
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME || !UpdateChecker.canInstall(context)) {
                return@LifecycleEventObserver
            }
            UpdateChecker.resumablePendingFile(context)?.let { file ->
                if (UpdateChecker.installApk(context, file)) {
                    NovexUpdateMonitor.clearAvailable()
                    com.openminis.app.data.NovexUpdateDownload.reset()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    if (bulletin.stack.isNotEmpty()) {
        BulletinStackFace(
            state = bulletin,
            onDismissFront = { com.openminis.app.data.NovexBulletinMonitor.dismissFront() },
            onUpdateAction = { available ->
                com.openminis.app.data.NovexBulletinMonitor.dismissFront()
                NovexUpdateAnnouncementStore.markShown(context, available)
                // 跳脸卡「更新」= 直接开下，进度在公告中心「更新」页签行内看。
                com.openminis.app.data.NovexUpdateDownload.start(context, available)
            },
            onOpenHub = { hub.hubOpen = true },
        )
    }

    if (hub.hubOpen) {
        BulletinHubPage(
            state = bulletin,
            onDismiss = { hub.hubOpen = false },
            onRefresh = { com.openminis.app.data.NovexBulletinMonitor.refresh() },
            onToggle = { com.openminis.app.data.NovexBulletinMonitor.toggleExpand(it) },
            onRetry = { com.openminis.app.data.NovexBulletinMonitor.ensureBody(it) },
            onCheckUpdate = { com.openminis.app.data.NovexBulletinMonitor.manualCheckUpdate() },
            onUpdateAction = { available ->
                available?.let {
                    NovexUpdateAnnouncementStore.markShown(context, it)
                    com.openminis.app.data.NovexUpdateDownload.start(context, it)
                }
            },
            downloadState = downloadState,
            onInstallAction = {
                // 未授权时引导去开权限；返回后 ON_RESUME 观察器会自动拉起安装器。
                if (!com.openminis.app.data.NovexUpdateDownload.install(context)) {
                    UpdateChecker.openInstallPermissionSettings(context)
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
            contentScrollsItself = true,
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
                if (update.channel != UpdateChannel.STABLE) {
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
                                if (update.channel != UpdateChannel.STABLE) {
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
    initiallyExpanded: Boolean = latest,
) {
    var expanded by rememberSaveable("release-${note.versionName}") {
        mutableStateOf(initiallyExpanded)
    }
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
                if (expanded) NovexIcons.KeyboardArrowUp else NovexIcons.KeyboardArrowDown,
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
private fun updateChannelLabel(channel: UpdateChannel): String = stringResource(
    when (channel) {
        UpdateChannel.STABLE -> R.string.update_channel_stable
        UpdateChannel.PREVIEW -> R.string.update_channel_preview
    },
)

/**
 * [T-dual-update-source] 更新源切换胶囊：logo + 标签，选中态描边高亮。
 * 纯展示组件，选中逻辑与持久化在调用方。
 */
@Composable
private fun UpdateSourcePill(
    selected: Boolean,
    label: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) Color.Unspecified else Color(0xFF9E9E9E),
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
