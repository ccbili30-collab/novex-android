package com.openminis.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import novex.android.data.chat.ChatDao
import novex.android.data.chat.UsageJoinRow
import novex.android.data.model.LLMModel
import novex.android.data.model.ProviderConfig
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── 聚合管线（纯数据，与 UI 无关）───────────────────────────────────────────

private class UsageBucket(val modelId: String, val displayName: String, val provider: String) {
    var input = 0L
    var output = 0L
    var cacheCreate = 0L
    var cacheRead = 0L
    val days = mutableSetOf<String>()
    val sessions = mutableSetOf<String>()
    val totalInput: Long get() = input + cacheRead + cacheCreate
}

private data class UsageReport(
    val totalInput: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheCreate: Long,
    val groups: List<Pair<String, List<UsageBucket>>>,
) {
    val cacheHitRate: Double?
        get() = if (totalInput <= 0 || cacheRead <= 0) null
        else cacheRead.toDouble() / totalInput * 100
}

/** 会话行丢失（LEFT JOIN）的 usage 记录归入此桶——token 是真实计费的不能丢。 */
private const val UNKNOWN_MODEL_KEY = "(unknown model)"

private fun modelNameLookup(providerConfig: ProviderConfig?): Map<String, Pair<String, String>> {
    val table = LLMModel.allModels.associate { it.id to (it.displayName to it.provider) }
        .toMutableMap()
    providerConfig?.let { config ->
        for (entry in config.modelEntries) {
            if (entry.model.id in table) continue
            val providerName = config.instances
                .find { it.id == entry.providerInstanceId }
                ?.providerType?.displayName
                ?: entry.model.provider
            table[entry.model.id] = entry.model.displayName to providerName
        }
    }
    return table
}

private fun aggregateUsage(
    records: List<UsageJoinRow>,
    nameLookup: Map<String, Pair<String, String>>,
): UsageReport {
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val buckets = mutableMapOf<String, UsageBucket>()

    for (record in records) {
        val usage = runCatching { JSONObject(record.tokenUsage) }.getOrNull() ?: continue
        val modelKey = record.modelId ?: UNKNOWN_MODEL_KEY
        val (displayName, provider) = nameLookup[modelKey] ?: (modelKey to "Unknown")
        val bucket = buckets.getOrPut(modelKey) { UsageBucket(modelKey, displayName, provider) }
        bucket.input += usage.optLong("inputTokens", 0)
        bucket.output += usage.optLong("outputTokens", 0)
        bucket.cacheCreate += usage.optLong("cacheCreationTokens",
            usage.optLong("cacheCreationInputTokens", 0))
        bucket.cacheRead += usage.optLong("cacheReadTokens",
            usage.optLong("cacheReadInputTokens", 0))
        bucket.days += dayFmt.format(Date(record.createdAt))
        bucket.sessions += record.sessionId
    }

    val providerOrder = listOf("OpenAI", "Anthropic", "Google Gemini", "Google", "Antigravity", "Unknown")
    val groups = buckets.values
        .groupBy { it.provider }
        .toList()
        .sortedBy { (name, _) -> providerOrder.indexOf(name).let { if (it >= 0) it else providerOrder.size } }
        .map { (name, models) -> name to models.sortedByDescending { it.totalInput } }

    return UsageReport(
        totalInput = buckets.values.sumOf { it.totalInput },
        output = buckets.values.sumOf { it.output },
        cacheRead = buckets.values.sumOf { it.cacheRead },
        cacheCreate = buckets.values.sumOf { it.cacheCreate },
        groups = groups,
    )
}

private fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> (n / 1000.0).let { k ->
        if (k == k.toLong().toDouble()) "${k.toLong()}k" else String.format("%.1fk", k)
    }
    else -> n.toString()
}

// ── 界面 ────────────────────────────────────────────────────────────────────

@Composable
fun UsageStatsScreen(
    chatDao: ChatDao,
    providerConfig: ProviderConfig? = null,
    onBack: () -> Unit,
) {
    var report by remember { mutableStateOf<UsageReport?>(null) }

    LaunchedEffect(Unit) {
        report = aggregateUsage(chatDao.usageJoinRows(), modelNameLookup(providerConfig))
    }

    SettingsScaffold(title = stringResource(R.string.usage_title), onBack = onBack) {
        val r = report ?: return@SettingsScaffold

        SettingsSection(header = stringResource(R.string.usage_section_total)) {
            val rows = buildList {
                add(stringResource(R.string.usage_label_total_input) to compactCount(r.totalInput))
                add(stringResource(R.string.usage_label_output) to compactCount(r.output))
                if (r.cacheRead > 0) add(stringResource(R.string.usage_label_cache_read) to compactCount(r.cacheRead))
                if (r.cacheCreate > 0) add(stringResource(R.string.usage_label_cache_creation) to compactCount(r.cacheCreate))
                r.cacheHitRate?.let { add(stringResource(R.string.usage_label_cache_hit_rate) to String.format("%.1f%%", it)) }
            }
            rows.forEachIndexed { i, (label, value) ->
                SettingsValueRow(title = label, value = value, showDivider = i < rows.lastIndex)
            }
        }

        for ((providerName, models) in r.groups) {
            SettingsSection(header = providerName) {
                models.forEachIndexed { i, model ->
                    UsageModelRow(model = model, showDivider = i < models.lastIndex)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun UsageModelRow(model: UsageBucket, showDivider: Boolean) {
    var open by remember { mutableStateOf(false) }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(model.displayName, style = NovexType.ItemTitle, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${compactCount(model.totalInput)} / ${compactCount(model.output)}",
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                )
                Icon(
                    if (open) NovexIcons.ExpandMore else NovexIcons.KeyboardArrowRight,
                    contentDescription = null,
                    tint = NovexColors.TertiaryText,
                )
            }
        }

        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 32.dp, end = 16.dp, bottom = 8.dp)) {
                UsageDetailLine(stringResource(R.string.usage_detail_input), compactCount(model.input))
                UsageDetailLine(stringResource(R.string.usage_detail_output), compactCount(model.output))
                if (model.cacheRead > 0) UsageDetailLine(stringResource(R.string.usage_label_cache_read), compactCount(model.cacheRead))
                if (model.cacheCreate > 0) UsageDetailLine(stringResource(R.string.usage_label_cache_creation), compactCount(model.cacheCreate))
                if (model.totalInput > 0 && model.cacheRead > 0) {
                    UsageDetailLine(
                        stringResource(R.string.usage_label_cache_hit_rate),
                        String.format("%.1f%%", model.cacheRead.toDouble() / model.totalInput * 100),
                    )
                }
                if (model.days.isNotEmpty()) {
                    UsageDetailLine(
                        stringResource(R.string.usage_detail_daily_avg),
                        compactCount((model.input + model.output) / model.days.size),
                    )
                }
                if (model.sessions.isNotEmpty()) {
                    UsageDetailLine(
                        stringResource(R.string.usage_detail_session_avg),
                        compactCount((model.input + model.output) / model.sessions.size),
                    )
                }
                UsageDetailLine(stringResource(R.string.usage_detail_sessions), model.sessions.size.toString())
                UsageDetailLine(stringResource(R.string.usage_detail_active_days), model.days.size.toString())
            }
        }

        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 14.dp)
                    .height(NovexDimensions.Hairline)
                    .background(NovexColors.Divider),
            )
        }
    }
}

@Composable
private fun UsageDetailLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = NovexType.Metadata, color = NovexColors.SecondaryText)
        Text(value, style = NovexType.Metadata)
    }
}
