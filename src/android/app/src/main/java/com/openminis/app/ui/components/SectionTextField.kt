package com.openminis.app.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import com.openminis.app.ui.novex.NovexInputSurface
import com.openminis.app.ui.novex.NovexType

/** Compatibility contract; all input rendering and caret ownership live in Novex. */
@Composable
fun SectionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = NovexType.Body,
    fieldModifier: Modifier = Modifier,
    containerColor: Color? = null,
) {
    NovexInputSurface(
        value = value, onValueChange = onValueChange, modifier = modifier,
        placeholder = placeholder?.let { { Text(it) } }, isError = isError,
        singleLine = singleLine, readOnly = readOnly, enabled = enabled, maxLines = maxLines,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = visualTransformation, trailingIcon = trailingIcon,
        textStyle = textStyle, fieldModifier = fieldModifier,
    )
}
