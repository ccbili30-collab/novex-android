package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexType

/** 给某个 Provider 实例手填一个自定义模型：Model ID + 可选显示名。 */
@Composable
fun AddCustomModelScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val instance = providerRepository.instance(instanceId) ?: run {
        onBack()
        return
    }

    var modelId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    fun save() {
        providerRepository.addEntry(
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = LLMModel(
                    id = modelId.trim(),
                    displayName = displayName.ifBlank { modelId }.trim(),
                    provider = instance.providerType.displayName,
                ),
                isCustom = true,
            ),
        )
        onBack()
    }

    SettingsScaffold(
        title = stringResource(R.string.provider_detail_add_custom_model),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = stringResource(R.string.add_custom_model_id_footer, instance.providerType.displayName),
        ) {
            SettingsCardBlock {
                LabeledField(
                    label = stringResource(R.string.add_custom_model_model_id),
                    value = modelId,
                    onValueChange = { modelId = it },
                    placeholder = stringResource(R.string.add_custom_model_eg_id_placeholder),
                    monoStyle = true,
                )
                LabeledField(
                    label = stringResource(R.string.add_custom_model_display_name_optional),
                    value = displayName,
                    onValueChange = { displayName = it },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        MinisButton(
            onClick = ::save,
            enabled = modelId.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NovexDimensions.PageHorizontal),
        ) {
            Text(stringResource(R.string.common_save))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    monoStyle: Boolean = false,
) {
    Column {
        RowLabel(text = label)
        SectionTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = true,
            textStyle = if (monoStyle) NovexType.Body.copy(fontFamily = FontFamily.Monospace) else TextStyle.Default,
        )
        Spacer(Modifier.height(12.dp))
    }
}
