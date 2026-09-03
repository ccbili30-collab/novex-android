package com.openminis.app.ui.novex

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

internal data class NovexMenuAction(
    val label: String,
    @DrawableRes val icon: Int,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Novex 的唯一弹出操作菜单；页面只提交动作，不自行绘制菜单行。 */
@Composable
internal fun NovexActionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<NovexMenuAction>,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(NovexDimensions.PopupRadius),
        containerColor = NovexColors.Surface,
        tonalElevation = 2.dp,
        shadowElevation = 12.dp,
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0 && (action.destructive || actions[index - 1].destructive)) {
                HorizontalDivider(
                    color = NovexColors.Divider,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
            NovexActionMenuRow(action) {
                onDismissRequest()
                action.onClick()
            }
        }
    }
}

@Composable
private fun NovexActionMenuRow(action: NovexMenuAction, onClick: () -> Unit) {
    val tint = if (action.destructive) NovexColors.Danger else NovexColors.Primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(NovexDimensions.ActionIconTile)
                .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
                .background(
                    if (action.destructive) {
                        NovexColors.Danger.copy(alpha = 0.12f)
                    } else {
                        NovexColors.PrimarySoft
                    },
                ),
        ) {
            Icon(
                painter = painterResource(action.icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = action.label,
            color = if (action.destructive) NovexColors.Danger else NovexColors.Text,
            style = NovexType.Body,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}
