package com.openminis.app.ui.novex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** All confirmation and form overlays use this surface, including legacy call sites. */
@Composable
internal fun NovexDialogSurface(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    title: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
    contentScrollsItself: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = properties.decorFitsSystemWindows,
        ),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().safeDrawingPadding().imePadding()) {
            val shape = RoundedCornerShape(NovexDimensions.DialogRadius)
            Surface(
                modifier = modifier
                    .align(Alignment.Center)
                    .padding(horizontal = NovexDimensions.OverlayHorizontal)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .border(NovexDimensions.Hairline, NovexColors.Divider, shape),
                shape = shape,
                color = NovexColors.Surface,
                contentColor = NovexColors.Text,
                shadowElevation = 8.dp,
            ) {
                Column {
                    Column(
                        Modifier.weight(1f, fill = false)
                            .then(if (contentScrollsItself) Modifier else Modifier.verticalScroll(rememberScrollState()))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CompositionLocalProvider(LocalTextStyle provides NovexType.SectionTitle) { title?.invoke() }
                        CompositionLocalProvider(LocalTextStyle provides NovexType.Body) { content?.invoke() }
                    }
                    NovexDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        CompositionLocalProvider(LocalNovexDialogAction provides true) { actions() }
                    }
                }
            }
        }
    }
}

internal val LocalNovexDialogAction = compositionLocalOf { false }

/** One button renderer: dialog actions use full-width quiet rows, never nested pills. */
@Composable
internal fun NovexButtonSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    shape: Shape,
    background: Color,
    foreground: Color,
    border: BorderStroke?,
    contentPadding: PaddingValues,
    interactionSource: MutableInteractionSource?,
    content: @Composable RowScope.() -> Unit,
) {
    val dialogAction = LocalNovexDialogAction.current
    val actualShape = if (dialogAction) RoundedCornerShape(NovexDimensions.SmallRadius) else shape
    Row(
        modifier = modifier
            .then(if (dialogAction) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = NovexDimensions.MinimumTouch)
            .clip(actualShape)
            .background(if (dialogAction) Color.Transparent else background)
            .then(if (border != null && !dialogAction) Modifier.border(border, actualShape) else Modifier)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource ?: remember { MutableInteractionSource() },
                indication = androidx.compose.foundation.LocalIndication.current,
                onClick = onClick,
            )
            .padding(if (dialogAction) PaddingValues(horizontal = 12.dp, vertical = 10.dp) else contentPadding),
        horizontalArrangement = if (dialogAction) Arrangement.Start else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (dialogAction) NovexColors.Primary.copy(alpha = if (enabled) 1f else .4f) else foreground,
            LocalTextStyle provides NovexType.Body,
        ) { content() }
    }
}

/** Labels never float over input; IME composition remains owned by BasicTextField. */
@Composable
internal fun NovexInputSurface(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = NovexType.Body,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    fieldModifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(NovexDimensions.SmallRadius)
    // The owning draft stores text; a shared field must not duplicate long documents
    // into the activity saved-state bundle. Only small cursor coordinates survive recreation.
    var selectionStart by rememberSaveable { mutableIntStateOf(value.length) }
    var selectionEnd by rememberSaveable { mutableIntStateOf(value.length) }
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, TextRange(
            selectionStart.coerceIn(0, value.length), selectionEnd.coerceIn(0, value.length),
        )))
    }
    if (fieldValue.text != value) fieldValue = TextFieldValue(value, TextRange(value.length))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CompositionLocalProvider(LocalTextStyle provides NovexType.Metadata, LocalContentColor provides NovexColors.SecondaryText) {
            label?.invoke()
        }
        BasicTextField(
            value = fieldValue,
            onValueChange = { updated ->
                fieldValue = updated
                selectionStart = updated.selection.start
                selectionEnd = updated.selection.end
                if (updated.text != value) onValueChange(updated.text)
            },
            enabled = enabled, readOnly = readOnly,
            textStyle = textStyle.copy(color = if (textStyle.color == Color.Unspecified) NovexColors.Text else textStyle.color),
            cursorBrush = SolidColor(NovexColors.Primary), visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
            singleLine = singleLine, maxLines = maxLines, minLines = minLines,
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth().then(fieldModifier).heightIn(min = NovexDimensions.MinimumTouch)
                .semantics { if (isError) error("输入有误，请检查提示") }
                .background(if (enabled) NovexColors.Surface else NovexColors.SurfaceMuted, shape)
                .border(NovexDimensions.Hairline, if (isError) NovexColors.Danger else NovexColors.Divider, shape)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            decorationBox = { input ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    leadingIcon?.invoke()
                    prefix?.invoke()
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) CompositionLocalProvider(LocalContentColor provides NovexColors.TertiaryText, LocalTextStyle provides textStyle) {
                            placeholder?.invoke()
                        }
                        input()
                    }
                    suffix?.invoke()
                    trailingIcon?.invoke()
                }
            },
        )
        CompositionLocalProvider(LocalTextStyle provides NovexType.Metadata, LocalContentColor provides if (isError) NovexColors.Danger else NovexColors.SecondaryText) {
            supportingText?.invoke()
        }
    }
}
