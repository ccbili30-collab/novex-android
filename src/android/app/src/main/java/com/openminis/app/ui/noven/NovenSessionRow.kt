package com.openminis.app.ui.noven

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexIcons
import com.openminis.app.ui.sessions.NovexConversationThumbnail
import com.openminis.app.ui.sessions.SpinningRing
import com.openminis.app.ui.sessions.highlightedAnnotatedString
import com.openminis.app.ui.sessions.resolveConversationThumbnail
import com.openminis.app.ui.settings.existingMediaFile
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

internal data class CategoryStyle(val icon: ImageVector, val color: Color)

internal fun categoryStyle(category: String?): CategoryStyle {
    return when (category?.lowercase()) {
        "code"         -> CategoryStyle(NovexIcons.Code, Color(0xFFF09A37))
        "writing"      -> CategoryStyle(NovexIcons.Description, Color(0xFF3478F6))
        "research"     -> CategoryStyle(NovexIcons.Language, Color(0xFF30B0C7))
        "analysis"     -> CategoryStyle(NovexIcons.BarChart, Color(0xFF5856D6))
        "creative"     -> CategoryStyle(NovexIcons.Brush, Color(0xFFFF2D55))
        "chat"         -> CategoryStyle(NovexIcons.Forum, Color(0xFF34C759))
        "math"         -> CategoryStyle(NovexIcons.Calculate, Color(0xFF9B59B6))
        "translation"  -> CategoryStyle(NovexIcons.Translate, Color(0xFF00BCD4))
        "health"       -> CategoryStyle(NovexIcons.Favorite, Color(0xFFFF3B30))
        "finance"      -> CategoryStyle(NovexIcons.Payments, Color(0xFF00C7BE))
        "travel"       -> CategoryStyle(NovexIcons.Map, Color(0xFFF09A37))
        "education"    -> CategoryStyle(NovexIcons.Book, Color(0xFF3478F6))
        "design"       -> CategoryStyle(NovexIcons.Palette, Color(0xFFFF2D55))
        "productivity" -> CategoryStyle(NovexIcons.CalendarMonth, Color(0xFFFFCC00))
        "support"      -> CategoryStyle(NovexIcons.Settings, Color(0xFF8B6914))
        "other"        -> CategoryStyle(NovexIcons.GridView, Color(0xFF8E8E93))
        else           -> CategoryStyle(NovexIcons.Forum, Color(0xFF8E8E93))
    }
}

internal fun relativeDate(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)

    if (seconds < 60) return context.getString(R.string.time_just_now)
    if (minutes < 60) return context.getString(R.string.time_minutes_ago, minutes.toInt())
    if (hours < 24) return context.getString(R.string.time_hours_ago, hours.toInt())

    val dateCal = Calendar.getInstance().apply { time = Date(timestamp) }
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (dateCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        dateCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return context.getString(R.string.time_yesterday)
    }

    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) {
        // T172: device-locale weekday names via java.text.DateFormatSymbols.
        val dayNames = java.text.DateFormatSymbols(java.util.Locale.getDefault()).weekdays
        return dayNames[dateCal.get(Calendar.DAY_OF_WEEK)]
    }

    val month = dateCal.get(Calendar.MONTH) + 1
    val day = dateCal.get(Calendar.DAY_OF_MONTH)
    return "$month/$day"
}

internal val SessionInitialPalette = listOf(
    Color(0xFFE86B7A),
    Color(0xFFDA8B45),
    Color(0xFFB29A42),
    Color(0xFF52A875),
    Color(0xFF4D9BB7),
    Color(0xFF5D7FD1),
    Color(0xFF8A70C8),
    Color(0xFFB8679A),
)

/**
 * home-v2 会话行：48dp 圆角方形缩略图（primary 卡封面/头像 → Nova「N」→
 * 会话首字/头像回退）+ 标题 + 一行预览 + 卡片标签行 + 右侧时间。
 * SessionListScreen 行实现；两条路径（主路径 + 轻量启动面）共用，各自的
 * 选择/菜单交互由调用方挂在参数上
 * 交互经 onLongClick / leadingIcon / trailing / modifier 槽接入。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NovenSessionRow(
    session: ChatSessionEntity,
    onClick: () -> Unit,
    onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /** See SessionItemContent — Transparent inside a folder container. */
    rowBackground: Color? = null,
    /** home-v2：卡片标签与 primary 缩略图（由 SessionCardTags 解析）。 */
    cardFace: SessionCardFace? = null,
    cardReader: novex.android.CardSessionModel? = null,
    modifier: Modifier = Modifier,
) {
    val style = remember(session.category) { categoryStyle(session.category) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val timeText = remember(session.updatedAt, ctx) { relativeDate(ctx, session.updatedAt) }
    val titleText = session.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.new_chat)
    val rowHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // [T-android-sessionrow-press-indication] The long-press path uses raw
    // detectTapGestures (it needs the press OFFSET to anchor the context
    // menu), which — unlike clickable — carries no indication, so rows gave
    // zero visual feedback on tap/long-press. Drive the standard ripple by
    // hand: emit Press/Release/Cancel into an InteractionSource from
    // onPress, and mount it with Modifier.indication.
    //
    // Highlight SHAPE mirrors the folder card (user request): ungrouped rows
    // clip the indication to the same 6dp-inset, 16dp-radius rounded rect the
    // group card uses — 6dp outside the clip + 10dp inside keeps the total
    // 16dp content lead, so nothing moves. Folder members skip this: their
    // wrapper Box already clips to the welded container's segment shape
    // (square middles / bottom-rounded last), and a rounded ripple mid-weld
    // would break the one-container illusion.
    val pressInteractions = remember { MutableInteractionSource() }
    val inFolder = rowBackground != null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBackground ?: MaterialTheme.colorScheme.surface)
            .then(
                if (!inFolder) {
                    Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
            )
            .then(
                if (onLongClick != null) {
                    Modifier
                        .indication(pressInteractions, LocalIndication.current)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val press = PressInteraction.Press(offset)
                                    pressInteractions.emit(press)
                                    val released = tryAwaitRelease()
                                    pressInteractions.emit(
                                        if (released) PressInteraction.Release(press)
                                        else PressInteraction.Cancel(press),
                                    )
                                },
                                onTap = {
                                    com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                                    onClick()
                                },
                                onLongPress = { offset ->
                                    // Same reason the ripple is driven by hand
                                    // above: raw detectTapGestures carries no
                                    // built-in feedback, so the haptic that
                                    // `combinedClickable` gives for free has to
                                    // be fired explicitly here.
                                    rowHaptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                    onLongClick(offset)
                                },
                            )
                        }
                } else {
                    Modifier.clickable {
                        com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                        onClick()
                    }
                }
            )
            .padding(
                // 10dp in BOTH branches. Ungrouped: 6dp highlight-clip inset
                // + 10 = 16dp lead. In-folder: the wrapper Box already adds
                // the container's 6dp inset, so 16dp here pushed member icons
                // to 22dp — 6dp right of the folder card's own icon (6 outer
                // + 10 inner = 16). 10dp restores one shared 16dp icon grid
                // for the card, its members, and ungrouped rows alike.
                horizontal = 10.dp,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }

        // home-v2：48dp 圆角方形缩略图 — 优先 primary 卡封面/头像；绑定为
        // Nova 时 Muted 底 + 薄荷绿 N；否则回退会话首字/头像。
        val activeSessions by SessionActivityTracker.activeSessions.collectAsState()
        val isActive = session.id in activeSessions
        // [T-android-session-paused-badge] Head of this session's badge queue
        // — null for the common case. Renders as an overlay in the icon's
        // bottom-right corner, mirroring where iOS's iCloud badge sits so
        // future ICLOUD_SYNCING uses the same anchor.
        val badgeMap by com.openminis.app.service.SessionBadgeStore.byId.collectAsState()
        val badgeHead = badgeMap[session.id]?.firstOrNull()
        Box(
            modifier = Modifier.size(48.dp),
        ) {
            val cardThumb = cardFace?.thumbnail
            val isNova = cardFace?.tags?.singleOrNull() == NOVEN_NOVA_TAG
            when {
                cardThumb != null && cardReader != null -> novex.android.CardThumbnail(
                    ref = cardThumb,
                    model = cardReader,
                    description = "$titleText 缩略图",
                    modifier = Modifier.fillMaxSize(),
                    maxEdge = 192,
                )
                isNova -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NovenColors.Muted),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "N",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = NovenColors.Mint,
                    )
                }
                else -> {
                    val fallback = resolveConversationThumbnail(
                        conversationId = session.id,
                        title = titleText,
                        characterAvatarPath = session.assistantAvatarPath
                            .existingMediaFile()?.absolutePath,
                        worldImagePath = null,
                    )
                    when (fallback) {
                        is NovexConversationThumbnail.Image -> NovexArtwork(
                            kind = NovexArtworkKind.CHARACTER,
                            seed = fallback.path,
                            imageModel = fallback.path.existingMediaFile(),
                            contentDescription = "$titleText 缩略图",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        is NovexConversationThumbnail.Initial -> Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SessionInitialPalette[fallback.colorIndex]),
                        ) {
                            Text(
                                fallback.text,
                                color = Color.White,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    SpinningRing(
                        color = style.color,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }
            if (badgeHead != null) {
                SessionBadgeOverlay(
                    state = badgeHead,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }

        // Title + last message (or highlighted snippet during search) + card tags
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (searchQuery.isNotBlank()) {
                Text(
                    text = highlightedAnnotatedString(titleText, searchQuery),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NovenColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NovenColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // During an active search, prefer the matched message snippet
            // (content hit) over the generic lastMessage preview. Falls back
            // to lastMessage when match was title-only (snippet is null).
            if (searchQuery.isNotBlank() && searchSnippet != null) {
                Text(
                    text = highlightedAnnotatedString(searchSnippet, searchQuery),
                    fontSize = 13.sp,
                    color = NovenColors.Secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = session.lastMessage ?: "No messages yet",
                    fontSize = 13.sp,
                    color = NovenColors.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val (tags, moreTags) = trimSessionTags(cardFace?.tags.orEmpty())
            if (tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    tags.forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(NovenColors.Muted)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                tag,
                                fontSize = 11.sp,
                                color = NovenColors.Secondary,
                                maxLines = 1,
                            )
                        }
                    }
                    if (moreTags > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(NovenColors.Muted)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                "+$moreTags",
                                fontSize = 11.sp,
                                color = NovenColors.Secondary,
                            )
                        }
                    }
                }
            }
        }

        // Relative timestamp
        Text(
            text = timeText,
            fontSize = 12.sp,
            color = NovenColors.Secondary,
        )
        trailing?.invoke()
    }
}

/**
 * Badge dot anchored to the session icon's bottom-right corner. The 2dp
 * offset puts half the badge over the icon edge so the badge reads as
 * attached to the icon, not to the row padding.
 */
@Composable
internal fun SessionBadgeOverlay(
    state: com.openminis.app.service.SessionBadgeStore.SessionBadgeState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED -> {
            Box(
                modifier = modifier
                    .offset(x = 2.dp, y = 2.dp)
                    .size(14.dp)
                    .background(
                        // Solid system-orange. Picked over yellow so the
                        // alert reads as "attention" rather than "info".
                        color = Color(0xFFFF9500),
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Pause glyph (⏸) — mirrors iOS's "pause.fill" badge so the
                // cross-platform "this task was paused" affordance matches.
                Icon(
                    imageVector = NovexIcons.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.ICLOUD_SYNCING -> {
            // Reserved for the upcoming iCloud-equivalent sync surface;
            // not produced yet. Render nothing rather than a placeholder
            // so a stray persisted entry from a future build doesn't
            // surface a debug-looking icon on the current build.
        }
    }
}
