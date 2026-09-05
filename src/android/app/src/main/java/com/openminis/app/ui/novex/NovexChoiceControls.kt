package com.openminis.app.ui.novex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** Compatibility names route to Novex row, choice and container rendering. */
@Composable
internal fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    interactionSource: MutableInteractionSource? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = NovexDimensions.MinimumTouch)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else .4f).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalTextStyle provides NovexType.Body) {
            CompositionLocalProvider(LocalContentColor provides colors.leadingIconColor) { leadingIcon?.invoke() }
            CompositionLocalProvider(LocalContentColor provides colors.textColor) { Box(Modifier.weight(1f)) { text() } }
            CompositionLocalProvider(LocalContentColor provides colors.trailingIconColor) { trailingIcon?.invoke() }
        }
    }
}

@Composable
internal fun RadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    Box(
        modifier.semantics {
            this.selected = selected
            if (!enabled) disabled()
        }.then(if (onClick != null) Modifier.size(NovexDimensions.MinimumTouch)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { NovexCheckIndicator(checked = selected, enabled = enabled) }
}

@Composable
internal fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    Box(
        modifier.semantics {
            toggleableState = ToggleableState(checked)
            if (!enabled) disabled()
        }.then(if (onCheckedChange != null) Modifier.size(NovexDimensions.MinimumTouch)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange) else Modifier),
        contentAlignment = Alignment.Center,
    ) { NovexCheckIndicator(checked, enabled) }
}

@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NovexDimensions.SectionRadius),
    colors: CardColors = CardDefaults.cardColors(containerColor = NovexColors.Surface),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border: BorderStroke? = BorderStroke(NovexDimensions.Hairline, NovexColors.Divider),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, shape = shape, color = colors.containerColor, contentColor = colors.contentColor, border = border) {
        Column(content = content)
    }
}

@Composable
internal fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(NovexDimensions.SectionRadius),
    colors: CardColors = CardDefaults.cardColors(containerColor = NovexColors.Surface),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    border: BorderStroke? = BorderStroke(NovexDimensions.Hairline, NovexColors.Divider),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.clip(shape).clickable(enabled = enabled, onClick = onClick), shape, colors, elevation, border, content)
}

@Composable
internal fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(NovexDimensions.SmallRadius),
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    elevation: SelectableChipElevation? = null,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    Row(
        modifier.heightIn(min = NovexDimensions.MinimumTouch).clip(shape)
            .background(if (selected) NovexColors.PrimarySoft else NovexColors.Surface)
            .border(NovexDimensions.Hairline, if (selected) NovexColors.Primary else NovexColors.Divider, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else .4f).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalTextStyle provides NovexType.Body, LocalContentColor provides if (selected) NovexColors.Primary else NovexColors.Text) {
            leadingIcon?.invoke()
            label()
            trailingIcon?.invoke()
        }
    }
}

@Composable
internal fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(NovexDimensions.SmallRadius),
    colors: ChipColors = AssistChipDefaults.assistChipColors(),
    elevation: ChipElevation? = null,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    OutlinedButton(onClick, modifier, enabled, shape) {
        leadingIcon?.invoke()
        label()
        trailingIcon?.invoke()
    }
}

@Composable
internal fun SingleChoiceSegmentedButtonRow(
    modifier: Modifier = Modifier,
    space: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) = Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

@Composable
internal fun RowScope.SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SegmentedButtonColors = SegmentedButtonDefaults.colors(),
    border: BorderStroke = BorderStroke(NovexDimensions.Hairline, NovexColors.Divider),
    icon: @Composable () -> Unit = {},
    interactionSource: MutableInteractionSource? = null,
    label: @Composable () -> Unit,
) {
    FilterChip(selected, onClick, label, modifier.weight(1f), enabled)
}

@Composable
internal fun ListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
) {
    Row(modifier.fillMaxWidth().heightIn(min = NovexDimensions.SettingsRowMinHeight)
        .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides NovexColors.Primary) { leadingContent?.invoke() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CompositionLocalProvider(LocalTextStyle provides NovexType.Metadata, LocalContentColor provides NovexColors.SecondaryText) { overlineContent?.invoke() }
            CompositionLocalProvider(LocalTextStyle provides NovexType.Body, LocalContentColor provides NovexColors.Text) { headlineContent() }
            CompositionLocalProvider(LocalTextStyle provides NovexType.Metadata, LocalContentColor provides NovexColors.SecondaryText) { supportingContent?.invoke() }
        }
        trailingContent?.invoke()
    }
}

/** Platform range semantics and gestures, with Novex-owned colors and touch bounds. */
@Composable
internal fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(
        thumbColor = NovexColors.Primary, activeTrackColor = NovexColors.Primary,
        inactiveTrackColor = NovexColors.Divider, activeTickColor = NovexColors.Surface,
        inactiveTickColor = NovexColors.SecondaryText,
    ),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    androidx.compose.material3.Slider(value = value, onValueChange = onValueChange,
        modifier = modifier.heightIn(min = NovexDimensions.MinimumTouch), enabled = enabled,
        valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished,
        colors = colors, interactionSource = interactionSource)
}

@Composable
internal fun FloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NovexDimensions.SectionRadius),
    containerColor: Color = NovexColors.Primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    elevation: FloatingActionButtonElevation = FloatingActionButtonDefaults.elevation(0.dp),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    Button(onClick, modifier.size(56.dp), shape = RoundedCornerShape(NovexDimensions.SectionRadius),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        interactionSource = interactionSource) { content() }
}
