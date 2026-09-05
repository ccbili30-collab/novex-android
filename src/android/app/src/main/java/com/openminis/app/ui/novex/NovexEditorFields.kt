package com.openminis.app.ui.novex

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/** Shared compact field used by both world and character editors. */
@Composable
internal fun NovexInlineField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = NovexDimensions.PageHorizontal),
    ) {
        Text(label, color = NovexColors.Text, style = NovexType.Body, modifier = Modifier.weight(0.36f))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = NovexType.Body.copy(color = NovexColors.Text, textAlign = TextAlign.End),
            cursorBrush = SolidColor(NovexColors.Primary),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (value.isBlank()) {
                        Text(placeholder, color = NovexColors.TertiaryText, style = NovexType.Body)
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(0.64f),
        )
    }
}

/** Shared text document field. The surface is deliberately flatter than Material's outlined form. */
@Composable
internal fun NovexTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    placeholder: String = "可留空",
) {
    NovexInputSurface(
        value = value, onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = NovexDimensions.PageHorizontal, vertical = 7.dp),
        label = { Text(label) }, placeholder = { Text(placeholder) }, minLines = minLines,
    )
}

@Composable
internal fun NovexPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = NovexColors.Primary,
) {
    Button(
        onClick = onClick, modifier = modifier, enabled = enabled,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = Color.White),
    ) { Text(label) }
}

@Composable
internal fun NovexOutlineButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = if (danger) NovexColors.Danger else NovexColors.Text
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        icon?.let {
            Icon(painterResource(it), null, Modifier.size(18.dp), tint = tint)
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = tint)
    }
}

@Composable
internal fun NovexDivider(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(NovexColors.Divider))
}
