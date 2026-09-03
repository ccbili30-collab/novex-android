package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.ui.novex.AlertDialog as NovexAlertDialog
import com.openminis.app.ui.novex.NovexType

/**
 * App-wide confirmation dialog. Tighter than the Material 3 default
 * (16 dp radius, titleLarge instead of headlineSmall) so small
 * confirmation prompts don't read as oversized cards. The destructive
 * variant tints the confirm button with `colorScheme.error`, matching
 * the pattern iOS uses for `UIAlertActionStyle.destructive`.
 */
@Composable
fun MinisAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismissText: String = stringResource(R.string.cancel),
    isDestructive: Boolean = false,
    onDismiss: () -> Unit = onDismissRequest,
    /**
     * Optional third action, rendered between dismiss and confirm. When set,
     * the buttons stack vertically instead of sitting in a row: three labels
     * of real length (the pre-send compact prompt's "Compact & Enable
     * Auto-Compact" is 29 chars, and its zh translations are similar) do not
     * fit side by side on a phone, and a Row would either clip them or shrink
     * the text. Callers that pass nothing keep the original two-button row.
     */
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
) {
    val confirmColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val titleContent: @Composable () -> Unit = {
        Text(text = title, style = NovexType.PageTitle)
    }
    val bodyContent: (@Composable () -> Unit)? = text?.let { body ->
        { Text(text = body, style = NovexType.Body) }
    }
    if (neutralText != null && onNeutral != null) {
        NovexAlertDialog(
            onDismissRequest = onDismissRequest,
            title = titleContent,
            text = bodyContent,
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                ) {
                    MinisTextButton(onClick = onDismiss) { Text(dismissText) }
                    MinisTextButton(onClick = onConfirm) {
                        Text(text = confirmText, color = confirmColor)
                    }
                    MinisTextButton(onClick = onNeutral) {
                        Text(text = neutralText, color = confirmColor)
                    }
                }
            },
        )
    } else {
        NovexAlertDialog(
            onDismissRequest = onDismissRequest,
            title = titleContent,
            text = bodyContent,
            confirmButton = {
                MinisTextButton(onClick = onConfirm) {
                    Text(text = confirmText, color = confirmColor)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = onDismiss) { Text(dismissText) }
            },
        )
    }
}
