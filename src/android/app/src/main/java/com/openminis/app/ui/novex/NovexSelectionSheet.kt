package com.openminis.app.ui.novex

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.R

internal data class NovexSelectionAction(
    val label: String,
    @DrawableRes val icon: Int = R.drawable.ic_phosphor_plus,
    val onClick: () -> Unit,
)

/** Shared one-level picker for module types and other short creation choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovexSelectionSheet(
    title: String,
    actions: List<NovexSelectionAction>,
    onDismissRequest: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = NovexColors.Surface,
        contentColor = NovexColors.Text,
        tonalElevation = 0.dp,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = NovexDimensions.SheetRadius,
            topEnd = NovexDimensions.SheetRadius,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = title,
                color = NovexColors.Text,
                style = NovexType.SectionTitle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            actions.forEach { action ->
                NovexTextActionRow(
                    label = action.label,
                    icon = action.icon,
                    onClick = {
                        onDismissRequest()
                        action.onClick()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}
