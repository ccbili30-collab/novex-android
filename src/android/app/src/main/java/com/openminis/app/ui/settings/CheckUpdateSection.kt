package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.NovexBulletinMonitor
import com.openminis.app.data.NovexUpdateDownload
import com.openminis.app.data.NovexUpdateMonitor
import com.openminis.app.data.UpdateChannel
import com.openminis.app.data.UpdateChecker
import com.openminis.app.data.UpdateSource
import com.openminis.app.data.UpdateSourceStore
import com.openminis.app.ui.bulletin.BulletinHubPage
import novex.android.ui.NovexColors
import novex.android.ui.NovexIconAction
import novex.android.ui.NovexIcons

/**
 * 更新只有一个界面：公告中心「更新」页签。这里不再弹任何对话框——
 * 点「检查更新」直接打开公告中心并触发检查，进度/安装/错误都在页内。
 * （用户 2026-09-30：叠卡弹窗与 UpdateDialog 全部退役，只留这一个页面。）
 */
@Composable
fun CheckUpdateSection() {
    val context = LocalContext.current

    SettingsSection(
        header = stringResource(R.string.check_update_section_header),
        footer = stringResource(
            R.string.check_update_current_version_channel,
            BuildConfig.VERSION_NAME,
            updateChannelLabel(UpdateChecker.currentChannel),
        ),
    ) {
        // [T-dual-update-source] 更新源切换（默认 Gitee/国内；GitHub/海外走
        // 既有链路）。切换即清空已检测到的更新，下一次检查从新源取。
        var updateSource by remember { mutableStateOf(UpdateSourceStore.current()) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UpdateSourcePill(
                selected = updateSource == UpdateSource.GITEE,
                label = stringResource(R.string.update_source_gitee),
                iconRes = R.drawable.ic_gitee,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (updateSource != UpdateSource.GITEE) {
                        UpdateSourceStore.set(context, UpdateSource.GITEE)
                        updateSource = UpdateSource.GITEE
                        NovexUpdateMonitor.clearAvailable()
                        NovexBulletinMonitor.clearUpdate()
                    }
                },
            )
            UpdateSourcePill(
                selected = updateSource == UpdateSource.GITHUB,
                label = stringResource(R.string.update_source_github),
                iconRes = R.drawable.ic_github,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (updateSource != UpdateSource.GITHUB) {
                        UpdateSourceStore.set(context, UpdateSource.GITHUB)
                        updateSource = UpdateSource.GITHUB
                        NovexUpdateMonitor.clearAvailable()
                        NovexBulletinMonitor.clearUpdate()
                    }
                },
            )
        }
        SettingsRow(
            icon = NovexIcons.SystemUpdate,
            iconColor = NovexColors.Text,
            title = stringResource(R.string.check_update_check_button),
            subtitle = stringResource(R.string.check_update_open_bulletin_hint),
            showDivider = false,
            onClick = {
                NovexUpdateHub.open(tab = 1)
                NovexBulletinMonitor.manualCheckUpdate()
            },
        )
    }
}

/** 公告中心打开状态：入口图标（我的页）、宿主（根页面）、关于页共享同一个持有者。 */
internal object NovexUpdateHub {
    var hubOpen by mutableStateOf(false)

    /** 打开落在哪个页签：0=公告 1=更新。打开时一次性消费，不订阅。 */
    var pendingTab = 0

    fun open(tab: Int = 0) {
        pendingTab = tab
        hubOpen = true
    }
}

/**
 * 入口：公告用 megaphone 图标（与底栏「消息」的铃铛区分），点击打开公告
 * 中心。角标=未读公告或检测到更新；不再有任何自动弹窗。
 */
@Composable
internal fun NovexUpdateEntry() {
    val detectedUpdate by NovexUpdateMonitor.available.collectAsState()
    val bulletin by NovexBulletinMonitor.state.collectAsState()
    val hasBadge = bulletin.hasBadge || detectedUpdate != null
    Box(contentAlignment = Alignment.Center) {
        NovexIconAction(
            icon = R.drawable.ic_phosphor_megaphone,
            contentDescription = "打开 Novex（诺文）公告",
            onClick = { NovexUpdateHub.open() },
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
 * 宿主：始终挂在根页面组合中，负责公告中心页面与 ON_RESUME 安装续接。
 * 不绘制任何常驻可见内容，也不再弹窗。
 */
@Composable
internal fun NovexUpdateHost() {
    val context = LocalContext.current
    val bulletin by NovexBulletinMonitor.state.collectAsState()
    // 应用级下载态：关公告不取消、重开可见「安装」。
    val downloadState by NovexUpdateDownload.state.collectAsState()

    // 冷启动恢复：上轮已下完未装的 APK → 公告中心重开直接显示「安装」。
    LaunchedEffect(Unit) {
        NovexUpdateDownload.hydrateFromPending(context)
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
                    NovexUpdateDownload.reset()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    if (NovexUpdateHub.hubOpen) {
        BulletinHubPage(
            state = bulletin,
            initialTab = NovexUpdateHub.pendingTab,
            onDismiss = { NovexUpdateHub.hubOpen = false },
            onRefresh = { NovexBulletinMonitor.refresh() },
            onToggle = { NovexBulletinMonitor.toggleExpand(it) },
            onRetry = { NovexBulletinMonitor.ensureBody(it) },
            onCheckUpdate = { NovexBulletinMonitor.manualCheckUpdate() },
            onUpdateAction = { available ->
                available?.let { NovexUpdateDownload.start(context, it) }
            },
            downloadState = downloadState,
            onInstallAction = {
                // 未授权才引导去开权限（返回后 ON_RESUME 观察器自动拉起安装器）；
                // 签名不一致等其它失败由 NovexUpdateDownload 落 Failed 态文案呈现。
                if (!UpdateChecker.canInstall(context)) {
                    UpdateChecker.openInstallPermissionSettings(context)
                } else {
                    NovexUpdateDownload.install(context)
                }
            },
        )
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
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp),
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
