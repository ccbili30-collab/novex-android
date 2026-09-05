package com.openminis.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.openminis.app.ui.novex.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexDimensions

@Composable
fun MinisMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),

    shape: Shape = RoundedCornerShape(NovexDimensions.PopupRadius),

    containerColor: Color = NovexColors.Surface,
    tonalElevation: Dp = 0.dp,

    shadowElevation: Dp = 8.dp,

    border: BorderStroke? = BorderStroke(0.5.dp, NovexColors.Divider),
    minWidth: Dp = 180.dp,

    alignEnd: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    com.openminis.app.ui.novex.NovexPopupMenu(
        expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier,
        offset = offset, scrollState = scrollState, properties = properties,
        shape = shape, containerColor = containerColor, tonalElevation = tonalElevation,
        shadowElevation = shadowElevation, border = border, minWidth = minWidth,
        alignEnd = alignEnd, content = content,
    )
}

@Composable
fun MinisMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        thickness = 1.dp,
        color = NovexColors.Divider,
    )
}

object MinisMenuDefaults {
    val ItemPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

    @Composable
    fun itemColors(
        textColor: Color = MaterialTheme.colorScheme.onSurface,
        leadingIconColor: Color = MaterialTheme.colorScheme.primary,
        trailingIconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    ): MenuItemColors = MenuDefaults.itemColors(
        textColor = textColor,
        leadingIconColor = leadingIconColor,
        trailingIconColor = trailingIconColor,
    )

    @Composable
    fun destructiveItemColors(): MenuItemColors = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.error,
        leadingIconColor = MaterialTheme.colorScheme.error,
        trailingIconColor = MaterialTheme.colorScheme.error,
    )
}
