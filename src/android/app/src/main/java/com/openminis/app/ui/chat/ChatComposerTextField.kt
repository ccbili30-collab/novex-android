package com.openminis.app.ui.chat

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import kotlinx.coroutines.delay

/** Owns the input field's presentation and keyboard connection, never conversation state or storage. */
@Composable
internal fun ChatComposerTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onKeyEvent: (KeyEvent) -> Boolean,
    fontScale: Float,
    sendOnEnter: Boolean,
    onSend: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hints = stringArrayResource(R.array.novex_composer_hints)
    var hintIndex by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(hints.size) {
        while (hints.isNotEmpty()) {
            delay(120_000L)
            hintIndex = (hintIndex + 1) % hints.size
        }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 25.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .onKeyEvent(onKeyEvent),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 16.5.sp * fontScale,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = 6,
        keyboardOptions = KeyboardOptions(imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.Default),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                if (value.text.isEmpty()) Text(
                    hints.getOrElse(hintIndex) { "" },
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    fontSize = 16.5.sp * fontScale,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                innerTextField()
            }
        },
    )
}
