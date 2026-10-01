package com.openminis.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import novex.android.ui.Button as NovexButtonControl
import novex.android.ui.OutlinedButton as NovexOutlinedButtonControl
import novex.android.ui.TextButton as NovexTextButtonControl

// Material3 的 40dp 最小高度对手机太小：主按钮统一到 48dp；
// 区块卡内嵌的次级动作（登录/设 token）用 32dp 的小按钮系。
val MinisButtonHeight = 48.dp
val MinisSmallButtonHeight = 32.dp

private val SmallPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
private val DefaultShape = RoundedCornerShape(8.dp)

private enum class MinisButtonKind { Filled, Outlined, Text }

@Composable
private fun MinisButtonImpl(
    kind: MinisButtonKind,
    minHeight: Dp,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    colors: ButtonColors,
    elevation: ButtonElevation?,
    border: BorderStroke?,
    contentPadding: PaddingValues,
    interactionSource: MutableInteractionSource?,
    content: @Composable RowScope.() -> Unit,
) {
    // 小按钮要同步钉 defaultMinSize，否则 Material3 内部 40dp 地板会盖过 heightIn。
    val sized = modifier.heightIn(min = minHeight).let {
        if (minHeight < 40.dp) it.defaultMinSize(minHeight = minHeight) else it
    }
    val source = interactionSource ?: remember { MutableInteractionSource() }
    when (kind) {
        MinisButtonKind.Filled -> NovexButtonControl(
            onClick = onClick, modifier = sized, enabled = enabled, shape = shape,
            colors = colors, elevation = elevation, border = border,
            contentPadding = contentPadding, interactionSource = source, content = content,
        )
        MinisButtonKind.Outlined -> NovexOutlinedButtonControl(
            onClick = onClick, modifier = sized, enabled = enabled, shape = shape,
            colors = colors, elevation = elevation, border = border,
            contentPadding = contentPadding, interactionSource = source, content = content,
        )
        MinisButtonKind.Text -> NovexTextButtonControl(
            onClick = onClick, modifier = sized, enabled = enabled, shape = shape,
            colors = colors, elevation = elevation, border = border,
            contentPadding = contentPadding, interactionSource = source, content = content,
        )
    }
}

// ── 48dp 标准按钮 ───────────────────────────────────────────────────────────

@Composable
fun MinisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DefaultShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisButtonImpl(
    MinisButtonKind.Filled, MinisButtonHeight, onClick, modifier, enabled, shape,
    colors, elevation, border, contentPadding, interactionSource, content,
)

@Composable
fun MinisOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DefaultShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisButtonImpl(
    MinisButtonKind.Outlined, MinisButtonHeight, onClick, modifier, enabled, shape,
    colors, elevation, border, contentPadding, interactionSource, content,
)

@Composable
fun MinisTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DefaultShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisButtonImpl(
    MinisButtonKind.Text, MinisButtonHeight, onClick, modifier, enabled, shape,
    colors, elevation, border, contentPadding, interactionSource, content,
)

// ── 32dp 小按钮 ─────────────────────────────────────────────────────────────

@Composable
fun MinisSmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DefaultShape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisButtonImpl(
    MinisButtonKind.Filled, MinisSmallButtonHeight, onClick, modifier, enabled, shape,
    colors, elevation, border, contentPadding, interactionSource, content,
)

@Composable
fun MinisSmallOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DefaultShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = SmallPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisButtonImpl(
    MinisButtonKind.Outlined, MinisSmallButtonHeight, onClick, modifier, enabled, shape,
    colors, elevation, border, contentPadding, interactionSource, content,
)

@Composable
fun MinisSmallTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = DefaultShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MinisButtonImpl(
    MinisButtonKind.Text, MinisSmallButtonHeight, onClick, modifier, enabled, shape,
    colors, elevation, border, contentPadding, interactionSource, content,
)
