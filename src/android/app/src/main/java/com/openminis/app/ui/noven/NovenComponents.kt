package com.openminis.app.ui.noven

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.R
import novex.android.CardSessionModel
import novex.android.CardThumbnail
import novex.content.CardKind
import novex.content.ContentRef
import java.io.File

// ── 底栏 ─────────────────────────────────────────────────────────────────

@Composable
private fun NovenBottomBarItem(
    tab: NovenTab,
    icon: Int,
    label: String,
    selected: NovenTab,
    badged: Boolean,
    onSelect: (NovenTab) -> Unit,
) {
    val active = selected == tab
    val tint = if (active) NovenColors.Text else NovenColors.Secondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = { onSelect(tab) })
            .padding(top = 6.dp, bottom = 4.dp),
    ) {
        // 所有项统一 44dp 图标槽（与中间创作方块同高），五个标签同一基线。
        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            if (badged) Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = 8.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(NovenColors.Badge),
            )
        }
        Text(label, fontSize = 11.sp, color = tint)
    }
}

@Composable
internal fun NovenBottomBar(
    selected: NovenTab,
    onSelect: (NovenTab) -> Unit,
    meBadged: Boolean,
) {
    Column(Modifier.fillMaxWidth().background(NovenColors.Surface)) {
        Box(Modifier.fillMaxWidth().height(NovenDimens.Hairline).background(NovenColors.Divider))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            @Composable
            fun RowScope.slot(content: @Composable () -> Unit) =
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
            slot { NovenBottomBarItem(NovenTab.HOME, R.drawable.ic_phosphor_house, "首页", selected, false, onSelect) }
            slot { NovenBottomBarItem(NovenTab.SESSIONS, R.drawable.ic_phosphor_chat_circle, "会话", selected, false, onSelect) }
            slot {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(onClick = { onSelect(NovenTab.CREATE) })
                        .padding(top = 6.dp, bottom = 4.dp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NovenColors.Mint),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_phosphor_plus),
                            contentDescription = "创作",
                            tint = NovenColors.OnMint,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        "创作",
                        fontSize = 11.sp,
                        color = if (selected == NovenTab.CREATE) NovenColors.Text else NovenColors.Secondary,
                    )
                }
            }
            slot { NovenBottomBarItem(NovenTab.MESSAGES, R.drawable.ic_phosphor_bell, "消息", selected, false, onSelect) }
            slot { NovenBottomBarItem(NovenTab.ME, R.drawable.ic_phosphor_user, "我的", selected, meBadged, onSelect) }
        }
    }
}

// ── 频道 tab（选中半粗 + 18×3 薄荷绿下划线）─────────────────────────────────

@Composable
internal fun NovenChannelTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(onClick = { onSelect(index) }),
            ) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) NovenColors.Text else NovenColors.Secondary,
                )
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .width(18.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) NovenColors.Mint else Color.Transparent),
                )
            }
        }
    }
}

// ── 筛选胶囊 ─────────────────────────────────────────────────────────────

@Composable
internal fun NovenFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) NovenColors.ChipSelectedBg else NovenColors.Muted)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = if (selected) NovenColors.ChipSelectedText else NovenColors.Secondary,
        )
    }
}

// ── 搜索框 ────────────────────────────────────────────────────────────────

@Composable
internal fun NovenSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(NovenColors.Muted)
            .padding(horizontal = 10.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_phosphor_search),
            contentDescription = null,
            tint = NovenColors.Secondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(NovenColors.Mint),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = NovenColors.Text,
            ),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = NovenColors.Secondary)
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

// ── 类型标签（图上：黑色 45% 底 + 白字 11sp；卡内：Muted 底）────────────────

@Composable
internal fun NovenTypeTag(label: String, onImage: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (onImage) Color.Black.copy(alpha = 0.45f) else NovenColors.Muted)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 11.sp, color = if (onImage) Color.White else NovenColors.Secondary)
    }
}

@Composable
internal fun NovenWorldDot(modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(NovenColors.Mint))
}

// ── 头像与作者行 ──────────────────────────────────────────────────────────

@Composable
internal fun NovenAvatar(name: String, avatarPath: String?, size: Dp, onClick: (() -> Unit)? = null) {
    val shape = CircleShape
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(NovenColors.Mint.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarPath != null) {
            AsyncImage(
                model = File(avatarPath),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(shape),
            )
        } else {
            Text(
                name.firstOrNull()?.toString() ?: "我",
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.SemiBold,
                color = NovenColors.Mint,
            )
        }
    }
}

@Composable
internal fun NovenAuthorRow(
    name: String,
    avatarPath: String?,
    favorited: Boolean,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NovenAvatar(name, avatarPath, 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            name,
            fontSize = 12.sp,
            color = NovenColors.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(
                if (favorited) R.drawable.ic_phosphor_bookmark_simple_fill
                else R.drawable.ic_phosphor_bookmark_simple,
            ),
            contentDescription = if (favorited) "取消珍藏" else "珍藏",
            tint = if (favorited) NovenColors.Text else NovenColors.Secondary,
            modifier = Modifier
                .size(28.dp)
                .clickable(onClick = onFavorite)
                .padding(5.dp),
        )
    }
}

// ── 空状态 ────────────────────────────────────────────────────────────────

@Composable
internal fun NovenEmptyState(
    icon: Int?,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = NovenColors.Secondary,
            modifier = Modifier.size(36.dp),
        )
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = NovenColors.Text,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (subtitle != null) Text(
            subtitle,
            fontSize = 13.sp,
            color = NovenColors.Secondary,
            modifier = Modifier.padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actions != null) Row(Modifier.padding(top = 16.dp)) { actions() }
    }
}

// ── 作品卡片 ─────────────────────────────────────────────────────────────

/** 首页/我的共用的卡片展示数据。 */
internal data class NovenCardDisplay(
    val id: String,
    val name: String,
    val kind: CardKind,
    val imageRef: ContentRef?,
    val excerpt: String,
    val tags: List<String>,
    val internalCharacterCount: Int,
)

/**
 * home-v2 作品卡片：14dp 圆角、0.5dp 描边、无阴影。
 * 有图 → 世界 4:3 / 角色 3:4 大图；无图 → 文本型卡片。
 */
@Composable
internal fun NovenWorkCard(
    data: NovenCardDisplay,
    reader: CardSessionModel,
    onClick: () -> Unit,
    onEnterWorld: (() -> Unit)? = null,
    onCreateWith: (() -> Unit)? = null,
    footer: @Composable () -> Unit = {},
) {
    val isWorld = data.kind == CardKind.WORLD
    val typeLabel = if (isWorld) "世界" else "角色"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NovenDimens.CardRadius))
            .background(NovenColors.Surface)
            .border(
                NovenDimens.Hairline,
                NovenColors.Divider,
                RoundedCornerShape(NovenDimens.CardRadius),
            )
            .clickable(onClick = onClick),
    ) {
        val image = data.imageRef
        if (image != null) {
            Box {
                CardThumbnail(
                    ref = image,
                    model = reader,
                    description = data.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(if (isWorld) 4f / 3f else 3f / 4f),
                    maxEdge = 640,
                )
                Box(Modifier.padding(8.dp).align(Alignment.TopStart)) {
                    NovenTypeTag(typeLabel, onImage = true)
                }
                if (isWorld) {
                    // 徽标本身即「进入世界」入口：有回调时可点，否则纯展示。
                    var badgeModifier = Modifier
                        .padding(8.dp)
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NovenColors.Surface.copy(alpha = 0.85f))
                        .border(1.dp, NovenColors.Mint, RoundedCornerShape(10.dp))
                    if (onEnterWorld != null) {
                        badgeModifier = badgeModifier.clickable(onClick = onEnterWorld)
                    }
                    Box(badgeModifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("可进入", fontSize = 10.sp, color = NovenColors.Mint)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    data.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NovenColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                footer()
            }
        } else {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NovenTypeTag(typeLabel, onImage = false)
                    if (isWorld) {
                        Spacer(Modifier.width(6.dp))
                        NovenWorldDot()
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    data.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = NovenColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (data.excerpt.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        data.excerpt,
                        fontSize = 13.sp,
                        color = NovenColors.Secondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (data.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.tags.forEach { tag ->
                            Text("#$tag", fontSize = 12.sp, color = NovenColors.Mint, maxLines = 1)
                        }
                    }
                }
                if (isWorld && (onEnterWorld != null || onCreateWith != null)) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(NovenDimens.Hairline)
                            .background(NovenColors.Divider),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (onEnterWorld != null) Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(onClick = onEnterWorld),
                        ) {
                            Text("进入世界", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = NovenColors.Mint)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_phosphor_arrow_right),
                                contentDescription = null,
                                tint = NovenColors.Mint,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (onCreateWith != null) Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(onClick = onCreateWith),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_phosphor_git_fork),
                                contentDescription = null,
                                tint = NovenColors.Secondary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("以此创作", fontSize = 12.sp, color = NovenColors.Secondary)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                footer()
            }
        }
    }
}

// ── 导入文件选择 ──────────────────────────────────────────────────────────

/** 返回一个「先选类型，再打开文件选择器」的入口函数。 */
@Composable
internal fun rememberNovenImportPicker(onPicked: (Uri, Boolean) -> Unit): (Boolean) -> Unit {
    var pendingWorld = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri, pendingWorld.value)
    }
    return { world ->
        pendingWorld.value = world
        launcher.launch(arrayOf("*/*"))
    }
}
