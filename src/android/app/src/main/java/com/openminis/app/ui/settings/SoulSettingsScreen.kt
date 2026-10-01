package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.SoulBodyLimitCheck
import com.openminis.app.agent.SoulFile
import com.openminis.app.agent.SoulMDParser
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import novex.android.ui.AlertDialog
import novex.android.ui.Button
import novex.android.ui.NovexColors
import novex.android.ui.NovexType
import novex.android.ui.OutlinedButton
import novex.android.ui.OutlinedTextField
import novex.android.ui.novexScaledSp

/**
 * SOUL.md 编辑页：预览卡（✨ + 名称 + 风格）、身份字段、人格正文
 * （软警告/硬截断长度提示）、恢复默认、保存走 [SoulStore.save]。
 * emoji 字段不回显也不可编辑，但保存时原样回写盘上值，避免清掉用户
 * 在别处设的自定义 emoji。
 */
@Composable
fun SoulSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var form by remember { mutableStateOf(SoulForm(SoulMetadata.DEFAULT)) }
    var loaded by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            SoulStore.ensureExists(context)
            val parsed = SoulStore.load(context) ?: SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
            form = SoulForm(parsed.metadata, parsed.body)
        }
        loaded = true
    }

    val limitCheck by remember(form.body) { derivedStateOf { SoulStore.isOverLimit(form.body) } }

    fun restoreDefaults() {
        val parsed = SoulMDParser.parse(SoulStore.DEFAULT_CONTENT)
        form = SoulForm(parsed.metadata, parsed.body)
        confirmRestore = false
    }

    fun save() {
        scope.launch {
            try {
                val file = SoulFile(
                    metadata = SoulMetadata(
                        name = form.name.ifBlank { SoulMetadata.DEFAULT.name },
                        emoji = form.emoji.ifBlank { SoulMetadata.DEFAULT.emoji },
                        style = form.style,
                        lang = form.lang.ifBlank { SoulMetadata.DEFAULT.lang },
                    ),
                    body = form.body,
                )
                withContext(Dispatchers.IO) { SoulStore.save(context, file) }
                onBack()
            } catch (t: Throwable) {
                saveError = t.message ?: "save failed"
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.soul_settings_title),
        onBack = onBack,
    ) {
        SettingsSection(header = stringResource(R.string.soul_section_preview)) {
            SoulPreviewCard(form)
        }

        SettingsSection(header = stringResource(R.string.soul_section_identity)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { form = form.copy(name = it) },
                    label = { Text(stringResource(R.string.soul_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = form.style,
                    onValueChange = { form = form.copy(style = it) },
                    label = { Text(stringResource(R.string.soul_field_style)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SoulLangSelector(lang = form.lang, onSelect = { form = form.copy(lang = it) })
            }
        }

        SettingsSection(
            header = stringResource(R.string.soul_section_personality),
            footer = stringResource(R.string.soul_personality_footer),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = form.body,
                    onValueChange = { form = form.copy(body = it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp),
                    textStyle = NovexType.Body.copy(fontFamily = FontFamily.Monospace),
                    placeholder = { Text(stringResource(R.string.soul_body_placeholder)) },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    soulLimitIndicator(limitCheck, form.body),
                    fontSize = novexScaledSp(12),
                    color = if (limitCheck.isOverLimit) NovexColors.Danger else NovexColors.SecondaryText,
                )
            }
        }

        SettingsSection {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { confirmRestore = true },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.soul_restore_default)) }
                Button(
                    onClick = ::save,
                    enabled = loaded && !limitCheck.isOverLimit,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.soul_save)) }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.soul_restore_confirm_title)) },
            text = { Text(stringResource(R.string.soul_restore_confirm_body)) },
            confirmButton = {
                Button(onClick = ::restoreDefaults) { Text(stringResource(R.string.soul_restore_default)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmRestore = false }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text(stringResource(R.string.soul_save_error_title)) },
            text = { Text(err) },
            confirmButton = {
                Button(onClick = { saveError = null }) { Text(stringResource(R.string.soul_ok)) }
            },
        )
    }
}

/** 表单态：emoji 只回写不展示（锁 ✨，但保留盘上原值）。 */
private data class SoulForm(
    val name: String,
    val emoji: String,
    val style: String,
    val lang: String,
    val body: String,
) {
    constructor(meta: SoulMetadata, body: String = "") :
        this(meta.name, meta.emoji, meta.style, meta.lang, body)
}

@Composable
private fun SoulPreviewCard(form: SoulForm) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SoulMetadata.DISPLAY_EMOJI, fontSize = novexScaledSp(28), modifier = Modifier.width(48.dp))
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                form.name.ifBlank { "Nova" },
                fontSize = novexScaledSp(17),
                fontWeight = FontWeight.SemiBold,
                color = NovexColors.Text,
            )
            if (form.style.isNotBlank()) {
                Text(form.style, fontSize = novexScaledSp(13), color = NovexColors.SecondaryText)
            }
        }
    }
}

/** 计数提示：未超限时按当前命中的规则显示字数/字符数。 */
@Composable
private fun soulLimitIndicator(check: SoulBodyLimitCheck, body: String): String = when (check) {
    is SoulBodyLimitCheck.Ok -> soulBodyCountText(body)
    is SoulBodyLimitCheck.OverLimitChinese -> stringResource(
        R.string.soul_over_limit_chinese, check.chars, check.cap, SoulStore.ENGLISH_WORD_LIMIT,
    )
    is SoulBodyLimitCheck.OverLimitEnglish -> stringResource(
        R.string.soul_over_limit_english, check.words, check.cap, SoulStore.CHINESE_CHAR_LIMIT,
    )
}

@Composable
private fun soulBodyCountText(body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return stringResource(R.string.soul_count_zero)
    // 与超限判定同一套 CJK 占比规则：CJK 为主按字符数，否则按词数。
    var cjk = 0
    var total = 0
    var i = 0
    while (i < trimmed.length) {
        val cp = trimmed.codePointAt(i)
        total += 1
        if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
            cp in 0x3040..0x309F || cp in 0x30A0..0x30FF || cp in 0xAC00..0xD7AF
        ) cjk += 1
        i += Character.charCount(cp)
    }
    return if (total > 0 && cjk.toDouble() / total > SoulStore.CJK_RATIO_THRESHOLD) {
        stringResource(
            R.string.soul_count_chars,
            trimmed.codePointCount(0, trimmed.length),
            SoulStore.CHINESE_CHAR_LIMIT,
        )
    } else {
        val words = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
        stringResource(R.string.soul_count_words, words, SoulStore.ENGLISH_WORD_LIMIT)
    }
}

/** 三选一分段按钮（auto/中/英），不依赖 ExposedDropdownMenu。 */
@Composable
private fun SoulLangSelector(lang: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "auto" to stringResource(R.string.soul_lang_auto),
        "zh" to stringResource(R.string.soul_lang_zh),
        "en" to stringResource(R.string.soul_lang_en),
    )
    val current = options.firstOrNull { it.first == lang }?.first ?: "auto"
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.soul_field_lang),
            fontSize = novexScaledSp(13),
            color = NovexColors.SecondaryText,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                if (key == current) {
                    Button(onClick = { onSelect(key) }, modifier = Modifier.weight(1f)) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onSelect(key) }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
        }
    }
}
