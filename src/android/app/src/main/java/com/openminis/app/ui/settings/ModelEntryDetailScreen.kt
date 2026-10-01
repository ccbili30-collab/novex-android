package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelOverrides
import novex.android.data.model.normalizeModalityName
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType

/**
 * 单个 ModelEntry 的详情/编辑页：Identity、Capabilities、Visibility、
 * Input/Output Modality、Quick Test、Reset 七段。保存时只把与 baseModel
 * 有分歧的字段写进 overrides（null override = 跟随 base，便于后续
 * provider 元数据更新自动生效）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEntryDetailScreen(
    instanceId: String,
    entryId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val entry = config.modelEntries.find { it.id == entryId && it.providerInstanceId == instanceId }
        ?: run { onBack(); return }
    val instance = config.instances.find { it.id == instanceId }
    val baseModel = entry.baseModel
    val overrides = entry.overrides

    val form = remember {
        ModelEntryForm(
            baseModel = baseModel,
            overrides = overrides,
            isHidden = entry.isHidden,
        )
    }
    var showQuickTest by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.model_entry_model_detail),
        // iOS 模态约定：leading 文本 Cancel、居中标题、trailing 实心 Save；
        // 不设返回箭头，Cancel 与系统返回都是放弃并退出。
        onBack = null,
        centerTitle = true,
        navigation = {
            MinisTextButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = NovexColors.SecondaryText),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
        actions = {
            MinisButton(
                onClick = {
                    val updated = form.buildUpdatedEntry(entry, baseModel)
                    providerRepository.updateEntry(updated)
                    onBack()
                },
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(stringResource(R.string.common_save)) }
        },
    ) {
        IdentitySection(entry, instance?.label ?: instanceId, form)
        CapabilitiesSection(baseModel, form)
        VisibilitySection(form)
        ModalitySection(
            header = R.string.modeldetail_section_input_modality,
            footer = R.string.modeldetail_input_modality_footer,
            toggles = form.inputToggles,
        )
        ModalitySection(
            header = R.string.modeldetail_section_output_modality,
            footer = R.string.modeldetail_output_modality_footer,
            toggles = form.outputToggles,
        )
        SettingsSection(footer = stringResource(R.string.quicktest_footer)) {
            SettingsRow(
                title = stringResource(R.string.quicktest_button),
                icon = NovexIcons.Bolt,
                showChevron = false,
                showDivider = false,
                onClick = { showQuickTest = true },
            )
        }
        if (!entry.isCustom && entry.isUserModified) {
            SettingsSection(
                footer = stringResource(R.string.model_entry_restores_the_provider_reported_display_n),
            ) {
                SettingsRow(
                    title = stringResource(R.string.model_entry_reset_to_default),
                    titleColor = NovexColors.Danger,
                    showChevron = false,
                    showDivider = false,
                    onClick = {
                        providerRepository.updateEntry(
                            entry.copy(overrides = ModelOverrides(), isHidden = false),
                        )
                        onBack()
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showQuickTest) {
        com.openminis.app.ui.components.QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { showQuickTest = false },
        )
    }
}

// ── 表单状态 ─────────────────────────────────────────────────────────────────

/** 一次编辑会话的全部可变字段。模态开关按 (模态名 → state) 收纳，避免六颗散布尔。 */
private class ModelEntryForm(
    baseModel: LLMModel,
    overrides: ModelOverrides,
    isHidden: Boolean,
) {
    var modelId by mutableStateOf(baseModel.id)
    var displayName by mutableStateOf(overrides.displayName ?: baseModel.displayName)
    var maxOutputTokensText by mutableStateOf(overrides.maxOutputTokens?.toString() ?: "")
    var contextWindowText by mutableStateOf(overrides.contextWindow?.toString() ?: "")
    var thinkingEnabled by mutableStateOf(overrides.supportsReasoning ?: baseModel.supportsReasoning ?: false)
    var hidden by mutableStateOf(isHidden)

    private val inheritedInput = (overrides.inputModalities ?: baseModel.inputModalities ?: emptyList())
        .map { it.normalizeModalityName() }
    private val inheritedOutput = (overrides.outputModalities ?: baseModel.outputModalities ?: emptyList())
        .map { it.normalizeModalityName() }

    // 输入模态四档 + 输出模态两档，顺序即 UI 行序。
    val inputToggles: List<Pair<Int, MutableState<Boolean>>> = listOf(
        R.string.modeldetail_image_input to mutableStateOf("image" in inheritedInput),
        R.string.modeldetail_pdf_input to mutableStateOf("pdf" in inheritedInput),
        R.string.modeldetail_audio_input to mutableStateOf("audio" in inheritedInput),
        R.string.modeldetail_video_input to mutableStateOf("video" in inheritedInput),
    )
    val outputToggles: List<Pair<Int, MutableState<Boolean>>> = listOf(
        R.string.modeldetail_image_output to mutableStateOf("image" in inheritedOutput),
        R.string.modeldetail_audio_output to mutableStateOf("audio" in inheritedOutput),
    )

    private companion object {
        val INPUT_NAMES = listOf("image", "pdf", "audio", "video")
        val OUTPUT_NAMES = listOf("image", "audio")
    }

    fun buildUpdatedEntry(
        entry: ModelEntry,
        baseModel: LLMModel,
    ): ModelEntry {
        val newInputs = INPUT_NAMES.filterIndexed { i, _ -> inputToggles[i].second.value }
        val newOutputs = OUTPUT_NAMES.filterIndexed { i, _ -> outputToggles[i].second.value }
        val baseInputs = baseModel.inputModalities ?: emptyList()
        val baseOutputs = baseModel.outputModalities ?: emptyList()

        val newOverrides = ModelOverrides(
            displayName = displayName.trim().takeIf { it.isNotEmpty() && it != baseModel.displayName },
            maxOutputTokens = maxOutputTokensText.trim().toIntOrNull()?.takeIf { it > 0 },
            contextWindow = contextWindowText.trim().toIntOrNull()?.takeIf { it > 0 },
            supportsReasoning = thinkingEnabled.takeIf { it != (baseModel.supportsReasoning ?: false) },
            // 模态集与 base 一致就不落 override，让 entry 跟随 provider 后续更新。
            inputModalities = if (newInputs.toSet() != baseInputs.toSet()) newInputs else null,
            outputModalities = if (newOutputs.toSet() != baseOutputs.toSet()) newOutputs else null,
        )
        return if (entry.isCustom) {
            entry.copy(baseModel = baseModel.copy(id = modelId), overrides = newOverrides, isHidden = hidden)
        } else {
            entry.copy(overrides = newOverrides, isHidden = hidden)
        }
    }
}

// ── 分段 ────────────────────────────────────────────────────────────────────

@Composable
private fun IdentitySection(entry: ModelEntry, providerLabel: String, form: ModelEntryForm) {
    SettingsSection(
        header = stringResource(R.string.add_provider_identity),
        footer = stringResource(
            if (entry.isCustom) R.string.modeldetail_identity_footer_custom
            else R.string.modeldetail_identity_footer_builtin,
        ),
    ) {
        SettingsCardBlock {
            RowLabel(text = stringResource(R.string.add_custom_model_model_id))
            SectionTextField(
                value = form.modelId,
                onValueChange = { if (entry.isCustom) form.modelId = it },
                singleLine = true,
                readOnly = !entry.isCustom,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(12.dp))
            RowLabel(text = stringResource(R.string.model_entry_display_name))
            SectionTextField(
                value = form.displayName,
                onValueChange = { form.displayName = it },
                singleLine = true,
            )
        }
        SettingsValueRow(
            title = stringResource(R.string.model_entry_provider),
            value = providerLabel,
            showDivider = false,
        )
    }
}

@Composable
private fun CapabilitiesSection(
    baseModel: LLMModel,
    form: ModelEntryForm,
) {
    SettingsSection(
        header = stringResource(R.string.modeldetail_section_capabilities),
        footer = stringResource(R.string.modeldetail_capabilities_footer),
    ) {
        SettingsCardBlock {
            RowLabel(text = stringResource(R.string.modeldetail_context_window))
            SectionTextField(
                value = form.contextWindowText,
                onValueChange = { form.contextWindowText = it.filter(Char::isDigit) },
                placeholder = baseModel.contextWindow?.toString()
                    ?: stringResource(R.string.modeldetail_provider_default),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(12.dp))
            RowLabel(text = stringResource(R.string.model_entry_max_output_tokens))
            SectionTextField(
                value = form.maxOutputTokensText,
                onValueChange = { form.maxOutputTokensText = it.filter(Char::isDigit) },
                placeholder = baseModel.maxOutputTokens?.toString()
                    ?: stringResource(R.string.modeldetail_provider_default),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            val entered = form.maxOutputTokensText.trim().toIntOrNull()
            val baseLimit = baseModel.maxOutputTokens
            // 超过 provider 上报上限时行内提示；不拦保存（高级用户探测真实上限）。
            if (entered != null && baseLimit != null && entered > baseLimit) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.modeldetail_max_tokens_exceeds_warning, baseLimit),
                    style = NovexType.Metadata,
                    color = NovexColors.Danger,
                )
            }
        }
        SettingsSwitchRow(
            title = stringResource(R.string.modeldetail_thinking),
            checked = form.thinkingEnabled,
            onCheckedChange = { form.thinkingEnabled = it },
            showDivider = false,
        )
    }
}

@Composable
private fun VisibilitySection(form: ModelEntryForm) {
    SettingsSection(
        header = stringResource(R.string.model_entry_visibility),
        footer = stringResource(R.string.model_entry_hidden_models_won_t_appear_in_the_model_),
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.model_entry_hidden),
            checked = form.hidden,
            onCheckedChange = { form.hidden = it },
            showDivider = false,
        )
    }
}

@Composable
private fun ModalitySection(
    header: Int,
    footer: Int,
    toggles: List<Pair<Int, MutableState<Boolean>>>,
) {
    SettingsSection(header = stringResource(header), footer = stringResource(footer)) {
        toggles.forEachIndexed { index, (labelRes, state) ->
            SettingsSwitchRow(
                title = stringResource(labelRes),
                checked = state.value,
                onCheckedChange = { state.value = it },
                showDivider = index < toggles.lastIndex,
            )
        }
    }
}
