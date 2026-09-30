package com.openminis.app.ui.bulletin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.data.BulletinManifestEntry
import com.openminis.app.data.BulletinStackCard
import com.openminis.app.data.BulletinUiState
import com.openminis.app.data.UpdateChecker
import com.openminis.app.ui.markdown.MarkdownText
import novex.android.ui.GhostIconButton
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIconAction
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import novex.android.ui.PillButton
import novex.android.ui.SegmentedTabs

// ---------------------------------------------------------------------------
// 入口：沿用原铃铛图标（用户 2026-09-28：交叠形态只属于跳脸的两张卡），
// 有未读公告/未装更新时右上红点。
// ---------------------------------------------------------------------------
@Composable
internal fun BulletinEntryIcon(hasBadge: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        NovexIconAction(
            icon = R.drawable.ic_phosphor_bell,
            contentDescription = "打开 Novex（诺文）公告",
            onClick = onClick,
        )
        if (hasBadge) {
            // [B/C] 未读红点用语义红而不是主题色——字节系惯例。
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

// ---------------------------------------------------------------------------
// 跳脸：卡片交叠。单新=单卡；双新=后卡微斜右上错位、左下角缩进前卡背后，
// 只露上边和右边（用户样张 2026-09-28）。公告在上、更新在下。
// 关首张：公告=已读；更新=本进程不再跳、红点保留。点蒙层=关首张。
// ---------------------------------------------------------------------------
@Composable
internal fun BulletinStackFace(
    state: BulletinUiState,
    onDismissFront: () -> Unit,
    onUpdateAction: (UpdateChecker.CheckResult.UpdateAvailable) -> Unit,
    onOpenHub: () -> Unit,
) {
    // 独立窗口宿主：从顶栏槽位发出也能全屏盖住页面
    Dialog(
        onDismissRequest = onDismissFront,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissFront,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val front = state.stack.firstOrNull() ?: return@Box
            val back = state.stack.getOrNull(1)
            // 外层 Box 只随主卡定尺寸；后卡 matchParentSize 同尺寸、微顺
            // 时针 4°、右上错位——斜角刚好让左下角缩进前卡背后。
            Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                if (back != null) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .offset(x = 26.dp, y = (-34).dp)
                            .rotate(4f)
                            .clip(RoundedCornerShape(NovexDimensions.DialogRadius))
                            .background(NovexColors.SurfaceMuted),
                    )
                }
                StackedSheetCard {
                    when (front) {
                        is BulletinStackCard.Announcements -> AnnouncementFaceCard(
                            entries = front.entries,
                            bodies = state.bodies,
                            onClose = onDismissFront,
                            onOpenHub = { onDismissFront(); onOpenHub() },
                        )
                        is BulletinStackCard.Update -> UpdateFaceCard(
                            front = front,
                            onLater = onDismissFront,
                            onUpdate = { onUpdateAction(front.available) },
                        )
                    }
                }
            }
        }
    }
}

/** 一张跳脸卡：Surface 底、体系圆角、内边距，内容交给调用方。 */
@Composable
private fun StackedSheetCard(
    modifier: Modifier = Modifier,
    container: Color = NovexColors.Surface,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NovexDimensions.DialogRadius))
            .background(container)
            .padding(20.dp)
            .heightIn(max = 520.dp),
    ) { content() }
}

@Composable
private fun FaceHeader(title: String, onClose: () -> Unit, closeDescription: String) {
    Box(Modifier.fillMaxWidth()) {
        Text(title, style = NovexType.PageTitle, color = NovexColors.Text, modifier = Modifier.align(Alignment.CenterStart))
        GhostIconButton(
            icon = NovexIcons.Close,
            contentDescription = closeDescription,
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun FaceActions(secondary: String, primary: String, onSecondary: () -> Unit, onPrimary: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton(label = secondary, filled = false, onClick = onSecondary)
        Spacer(Modifier.size(10.dp))
        PillButton(label = primary, onClick = onPrimary)
    }
}

@Composable
private fun AnnouncementFaceCard(
    entries: List<BulletinManifestEntry>,
    bodies: Map<String, String>,
    onClose: () -> Unit,
    onOpenHub: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FaceHeader(title = "新公告", onClose = onClose, closeDescription = "关闭")
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            entries.forEach { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.title, style = NovexType.ItemTitle, color = NovexColors.Text)
                    MetaRow(entry.date)
                    bodies[entry.id]?.let { MarkdownText(markdown = it, style = NovexType.Body) }
                }
            }
        }
        FaceActions(secondary = "查看全部", primary = "关闭", onSecondary = onOpenHub, onPrimary = onClose)
    }
}

@Composable
private fun UpdateFaceCard(
    front: BulletinStackCard.Update,
    onLater: () -> Unit,
    onUpdate: () -> Unit,
) {
    val available = front.available
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FaceHeader(title = "发现新版本 ${available.versionName}", onClose = onLater, closeDescription = "稍后")
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            (available.releaseNotes.firstOrNull()?.changelog ?: available.changelog)?.let {
                if (it.isNotBlank()) MarkdownText(markdown = it, style = NovexType.Body)
            }
            Text("关闭后不再弹窗，入口红点保留到安装完成。", style = NovexType.Metadata, color = NovexColors.SecondaryText)
        }
        FaceActions(secondary = "稍后", primary = "更新", onSecondary = onLater, onPrimary = onUpdate)
    }
}

@Composable
private fun MetaRow(date: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            NovexIcons.Schedule,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = NovexColors.SecondaryText,
        )
        Spacer(Modifier.size(5.dp))
        Text(date, style = NovexType.Metadata, color = NovexColors.SecondaryText)
    }
}

// ---------------------------------------------------------------------------
// 公告中心：弹窗卡片（用户 2026-09-28 样张：居中卡+蒙层+卡内滚动，
// 与跳脸卡同一语言，不是全屏页）。打开不联网；刷新才联网且绝不弹窗。
// ---------------------------------------------------------------------------
@Composable
internal fun BulletinHubPage(
    state: BulletinUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdateAction: (UpdateChecker.CheckResult.UpdateAvailable?) -> Unit,
    downloadState: com.openminis.app.data.NovexUpdateDownload.State,
    onInstallAction: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(NovexDimensions.DialogRadius))
                .background(NovexColors.Surface)
                .padding(20.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text("公告", style = NovexType.PageTitle, color = NovexColors.Text, modifier = Modifier.align(Alignment.CenterStart))
                if (state.refreshing) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.CenterEnd).size(20.dp),
                        strokeWidth = 2.dp,
                        color = NovexColors.Primary,
                    )
                } else {
                    GhostIconButton(
                        icon = NovexIcons.Refresh,
                        contentDescription = "刷新",
                        onClick = onRefresh,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            var tab by rememberSaveable { mutableIntStateOf(0) }
            SegmentedTabs(tabs = listOf("公告", "更新"), selected = tab, onSelect = { tab = it })
            Spacer(Modifier.size(14.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (tab == 0) {
                    AnnouncementsTab(state, onToggle, onRetry)
                } else {
                    UpdatesTab(state, onToggle, onRetry, onCheckUpdate, onUpdateAction, downloadState, onInstallAction)
                }
            }
            state.refreshNotice?.let {
                Text(
                    it,
                    Modifier.padding(top = 8.dp),
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                )
            }
        }
    }
}

@Composable
private fun AnnouncementsTab(
    state: BulletinUiState,
    onToggle: (String) -> Unit,
    onRetry: (String) -> Unit,
) {
    val manifest = state.manifest
    when {
        manifest == null -> EmptyHint("尚无缓存名册，点右上「刷新」。")
        manifest.announcements.isEmpty() -> EmptyHint("暂无公告。")
        else -> Column {
            manifest.announcements.forEachIndexed { index, entry ->
                ExpandableBulletinRow(
                    title = entry.title,
                    meta = listOf(entry.date, "rev${entry.rev}").filter { it.isNotEmpty() }.joinToString(" · "),
                    count = manifest.announcements.size,
                    index = index,
                    expanded = entry.id in state.expandedIds,
                    unread = entry.id !in state.readIds,
                    body = state.bodies[entry.id],
                    loading = entry.id in state.loadingBodies,
                    failed = entry.id in state.failedBodies,
                    onToggle = { onToggle(entry.id) },
                    onRetry = { onRetry(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun UpdatesTab(
    state: BulletinUiState,
    onToggle: (String) -> Unit,
    onRetry: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onUpdateAction: (UpdateChecker.CheckResult.UpdateAvailable?) -> Unit,
    downloadState: com.openminis.app.data.NovexUpdateDownload.State,
    onInstallAction: () -> Unit,
) {
    val manifest = state.manifest
    val past = manifest?.releaseNotes.orEmpty()
    val update = state.update
    val updateRowId = update?.let { "update:${it.versionName}" }
    val count = 2 + (if (update != null) 1 else 0) + past.size
    Column {
        // 当前版本（不可展开）
        GroupCard(count = count, index = 0) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                Text("当前版本 ${com.openminis.app.BuildConfig.VERSION_NAME}", style = NovexType.ItemTitle, color = NovexColors.Text)
                Text(
                    com.openminis.app.data.UpdateChecker.currentChannel.wireName + " 通道",
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                )
            }
        }
        // 检查更新：整行即动作，行尾药丸/进度
        val checkInteraction = remember { MutableInteractionSource() }
        GroupCard(count = count, index = 1, interaction = checkInteraction) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = checkInteraction, indication = LocalIndication.current, onClick = onCheckUpdate)
                    .padding(horizontal = 16.dp, vertical = NovexDimensions.RowVertical),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("检查更新", style = NovexType.ItemTitle, color = NovexColors.Text)
                    Text(
                        state.updateNotice ?: "手动检查 · 结果就地显示，不弹窗",
                        style = NovexType.Metadata,
                        color = NovexColors.SecondaryText,
                    )
                }
                if (state.checkingUpdate) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = NovexColors.Primary)
                    Spacer(Modifier.size(14.dp))
                } else {
                    PillButton(label = "检查", filled = false, onClick = onCheckUpdate)
                }
            }
        }
        var index = 2
        // 新版本：与往期同形态的展开行（展开看 changelog + 去更新）
        if (update != null && updateRowId != null) {
            val rowId = updateRowId
            val available = update
            ExpandableBulletinRow(
                title = "新版本 ${available.versionName}",
                meta = "发现新版本",
                count = count,
                index = index++,
                expanded = rowId in state.expandedIds,
                unread = false,
                body = available.releaseNotes.firstOrNull()?.changelog ?: available.changelog,
                loading = false,
                failed = false,
                onToggle = { onToggle(rowId) },
                onRetry = {},
                trailingContent = {
                    // 行内下载态：点「更新」就地变进度条，下完变「安装」。
                    // 关掉公告中心不取消——下载挂在 NovexUpdateDownload（进程级）。
                    when (val dl = downloadState) {
                        is com.openminis.app.data.NovexUpdateDownload.State.Downloading -> Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { dl.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text(
                                "${(dl.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                                style = NovexType.Metadata,
                                color = NovexColors.SecondaryText,
                            )
                        }
                        is com.openminis.app.data.NovexUpdateDownload.State.Downloaded -> Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            PillButton(label = "安装", onClick = onInstallAction)
                        }
                        else -> Column(
                            Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            PillButton(label = "更新", onClick = { onUpdateAction(available) })
                            (dl as? com.openminis.app.data.NovexUpdateDownload.State.Failed)?.let {
                                Text(
                                    "下载未完成：${it.message}",
                                    style = NovexType.Metadata,
                                    color = NovexColors.Danger,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                },
            )
        }
        // 往期版本：懒取正文，与公告行完全同形态
        past.forEach { entry ->
            ExpandableBulletinRow(
                title = entry.title,
                meta = listOf(entry.date, "rev${entry.rev}").filter { it.isNotEmpty() }.joinToString(" · "),
                count = count,
                index = index++,
                expanded = entry.id in state.expandedIds,
                unread = false,
                body = state.bodies[entry.id],
                loading = entry.id in state.loadingBodies,
                failed = entry.id in state.failedBodies,
                onToggle = { onToggle(entry.id) },
                onRetry = { onRetry(entry.id) },
            )
        }
    }
}

private val GroupCardSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** CardGroup 分组卡：组外角 20dp、内角 4dp，按下时外角收拢（RikkaHub 手法）。 */
@Composable
private fun GroupCard(
    count: Int,
    index: Int,
    interaction: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val source = interaction ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val top by animateDpAsState(
        targetValue = if (pressed || index == 0) NovexDimensions.GroupCardCorner else NovexDimensions.GroupCardInnerCorner,
        animationSpec = GroupCardSpec,
        label = "groupTop",
    )
    val bottom by animateDpAsState(
        targetValue = if (pressed || index == count - 1) NovexDimensions.GroupCardCorner else NovexDimensions.GroupCardInnerCorner,
        animationSpec = GroupCardSpec,
        label = "groupBottom",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(NovexColors.Surface),
        content = { content() },
    )
}

@Composable
private fun ExpandableBulletinRow(
    title: String,
    meta: String,
    count: Int,
    index: Int,
    expanded: Boolean,
    unread: Boolean,
    body: String?,
    loading: Boolean,
    failed: Boolean,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "chevron")
    val rowInteraction = remember { MutableInteractionSource() }
    GroupCard(count = count, index = index, interaction = rowInteraction) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = rowInteraction, indication = LocalIndication.current, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (unread) {
                Box(Modifier.size(8.dp).background(NovexColors.Danger, CircleShape))
                Spacer(Modifier.size(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = NovexType.ItemTitle, color = NovexColors.Text)
                if (meta.isNotEmpty()) {
                    Text(meta, style = NovexType.Metadata, color = NovexColors.SecondaryText)
                }
            }
            Icon(
                NovexIcons.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = NovexColors.SecondaryText,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotation),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                when {
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = NovexColors.Primary)
                        Spacer(Modifier.size(10.dp))
                        Text("正在获取正文…", style = NovexType.Metadata, color = NovexColors.SecondaryText)
                    }
                    failed -> PillButton(label = "加载失败·点击重试", filled = false, onClick = onRetry)
                    body != null -> MarkdownText(markdown = body, style = NovexType.Body)
                }
                trailingContent?.invoke()
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = NovexType.Body, color = NovexColors.SecondaryText)
    }
}
