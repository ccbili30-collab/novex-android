package com.openminis.app.ui.chat

// MoveToSessionSheet 的行件：面板行壳、新建入口行、会话选择行。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import novex.android.data.chat.SessionRow
import com.openminis.app.ui.noven.categoryStyle
import com.openminis.app.ui.noven.relativeDate

/** 面板行壳：40dp 圆底图标 + 标题/副标题两行——新建入口行与会话行共用。 */
@Composable
internal fun PickerRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    subtitleTint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(iconTint.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = subtitleTint,
                maxLines = 1,
            )
        }
    }
}

/** 「新建会话再移过去」入口行——以当前会话的模型配置建新会话。 */
@Composable
internal fun NewSessionTargetRow(enabled: Boolean, onPick: () -> Unit) {
    PickerRow(
        icon = novex.android.ui.NovexIcons.Forum,
        iconTint = MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.move_to_new_play_session),
        subtitle = stringResource(R.string.move_to_new_play_session_hint),
        subtitleTint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(enabled = enabled, onClick = onPick),
    )
}

/** 选择器单行：类别图标 + 标题 + 相对时间（共享 NovenSessionRow
 *  的样式表）。比主列表行紧凑——不带动效环、不带 lastMessage 行。 */
@Composable
internal fun MoveToPickerRow(
    session: SessionRow,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val style = remember(session.category) { categoryStyle(session.category) }
    val timeText = remember(session.updatedAt, context) { relativeDate(context, session.updatedAt) }
    PickerRow(
        icon = style.icon,
        iconTint = style.color,
        title = session.title ?: stringResource(R.string.move_to_sheet_untitled),
        subtitle = timeText,
        subtitleTint = MaterialTheme.colorScheme.outline,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

