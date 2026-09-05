package com.openminis.app.ui.novex

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
internal fun NovexPopupMenu(
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

    if (!expanded) return
    val density = LocalDensity.current
    val offsetXPx = with(density) { offset.x.roundToPx() }
    val offsetYPx = with(density) { offset.y.roundToPx() }
    Popup(
        onDismissRequest = onDismissRequest,
        properties = properties,
        popupPositionProvider = remember(offsetXPx, offsetYPx, alignEnd) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {

                    val x = if (alignEnd) {
                        (anchorBounds.right - popupContentSize.width + offsetXPx)
                    } else {
                        (anchorBounds.left + offsetXPx)
                    }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))

                    val belowY = anchorBounds.bottom + offsetYPx
                    val aboveY = anchorBounds.top - popupContentSize.height - offsetYPx
                    val fitsBelow = belowY + popupContentSize.height <= windowSize.height
                    val y = if (fitsBelow || aboveY < 0) belowY else aboveY
                    return IntOffset(
                        x,
                        y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                    )
                }
            }
        },
    ) {

        val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
        val origin = if (alignEnd) {
            androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
        } else {
            androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
        }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = scaleIn(
                animationSpec = tween(200),
                initialScale = 0.85f,
                transformOrigin = origin,
            ) + fadeIn(animationSpec = tween(200)),
            exit = scaleOut(animationSpec = tween(120)) + fadeOut(animationSpec = tween(120)),
        ) {
            Surface(

                modifier = modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = minWidth, max = 280.dp)

                    .shadow(
                        elevation = shadowElevation,
                        shape = shape,
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.35f),
                        spotColor = Color.Black.copy(alpha = 0.35f),
                    ),
                shape = shape,
                color = containerColor,
                tonalElevation = tonalElevation,
                shadowElevation = 0.dp,
                border = border,
            ) {

                Column(
                    modifier = Modifier.verticalScroll(scrollState),
                    content = content,
                )
            }
        }
    }
}
