package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.novex.domain.NovexLongformModelPolicy
import com.openminis.app.novex.domain.NovexLongformModelTier
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexDimensions
import com.openminis.app.ui.novex.NovexEditorSection
import com.openminis.app.ui.novex.NovexSummaryRow
import com.openminis.app.ui.novex.NovexType

/**
 * Session Token Usage bottom sheet — mirrors iOS `TokenUsageSheet` and uses
 * the standardized chat sheet shell so its header/dismiss behavior matches
 * every other "⋯" menu sheet.
 *
 * Sections (top to bottom), 分组白卡呈现（design-system §13）：
 *   - 上下文：占用进度条（用到多少窗口）+ Context Window / Max Output / 上限控制
 *   - 长篇创作：当前能力 / 本轮资料预算
 *   - Thinking（模型支持推理时）：On/Off / Level / Supported
 *   - Tokens（会话累计）：Input (incl. cache) / Output
 *   - Cache（会话累计）：Cache Read / Cache Write
 *   - Agent Loop：Total Loops
 *
 * Data loads asynchronously via [ChatViewModel.loadSessionTokenStats] when the
 * sheet appears; we intentionally don't hold a live subscription — token
 * counters change per API call, not per keystroke, so pull-on-open is enough.
 */
@Composable
fun TokenUsageSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var stats by remember { mutableStateOf<ChatViewModel.SessionTokenStats?>(null) }
    val capacity by viewModel.contextCapacity.collectAsState()
    val contextWindow = capacity.second
    val usedContext by viewModel.lastTurnContextTokens.collectAsState()
    val estimatedContext by viewModel.contextEstimated.collectAsState()
    val usageReady by viewModel.contextUsageReady.collectAsState()
    var editingCapacity by remember { mutableStateOf(false) }
    var capacityText by remember { mutableStateOf("") }
    var capacityError by remember { mutableStateOf<String?>(null) }
    val isStreaming by viewModel.isStreaming.collectAsState()
    val maxOutput = remember { viewModel.currentModelMaxOutputTokens }
    val thinking = remember { viewModel.thinkingInfo() }

    LaunchedEffect(Unit) {
        stats = viewModel.loadSessionTokenStats()
    }

    if (editingCapacity) com.openminis.app.ui.novex.AlertDialog(
        onDismissRequest = { editingCapacity = false },
        title = { Text("模型上下文上限") },
        text = { Column {
            Text("填写当前服务支持的词元数；留空恢复自动识别。")
            com.openminis.app.ui.novex.OutlinedTextField(value = capacityText, onValueChange = { capacityText = it; capacityError = null },
                singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            capacityError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { com.openminis.app.ui.novex.TextButton(enabled = !isStreaming, onClick = {
            val parsed = capacityText.trim().toIntOrNull()
            if (capacityText.isNotBlank() && (parsed == null || parsed < 1024)) capacityError = "请输入至少 1024 的整数"
            else runCatching { viewModel.saveModelContextWindow(parsed) }
                .onSuccess { editingCapacity = false }.onFailure { capacityError = it.message }
        }) { Text("保存") } },
        dismissButton = { com.openminis.app.ui.novex.TextButton(onClick = { editingCapacity = false }) { Text("取消") } },
    )

    StandardChatSheet(
        title = stringResource(R.string.token_usage_sheet_title),
        onDismiss = onDismiss,
        // T148: iOS uses .presentationDetents([.medium]) for the same sheet
        // (AIChatView.swift:508). Match that proportion on Android so the
        // half-screen feel is consistent — the token-usage view holds maybe
        // a screenful of stat rows max and looked overgrown at 90%.
        heightFraction = 0.5f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 24.dp),
        ) {
            val s = stats
            val onText = stringResource(R.string.common_on)
            val offText = stringResource(R.string.common_off)
            val yesText = stringResource(R.string.common_yes)
            val noText = stringResource(R.string.common_no)

            NovexEditorSection(
                header = stringResource(R.string.token_usage_section_context),
                footer = if (viewModel.modelContextIsEstimated)
                    "未取得明确容量，当前按型号估算；可在模型设置中填写上游公布的容量。" else null,
            ) {
                // 上下文占用进度条：用得越满越接近上限。
                contextWindow?.let { window ->
                    ContextUsageBar(
                        used = if (usageReady) usedContext else null,
                        window = window,
                        label = if (estimatedContext) "本轮预计用量" else "本轮实际用量",
                    )
                } ?: NovexSummaryRow(
                    if (estimatedContext) "本轮预计用量" else "本轮实际用量",
                    if (usageReady) formatTokens(usedContext) else "尚未完成装配",
                )
                contextWindow?.let {
                    NovexSummaryRow(stringResource(R.string.token_usage_context_window), formatTokens(it), summaryTinted = true)
                }
                maxOutput?.let {
                    NovexSummaryRow(stringResource(R.string.token_usage_max_output), formatTokens(it))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NovexDimensions.PageHorizontal),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NovexContextLimitControl(capacity.first, contextWindow, !isStreaming, viewModel::saveConversationContextLimit)
                }
                if (viewModel.canEditModelCapacity) Row(
                    Modifier.fillMaxWidth().padding(horizontal = NovexDimensions.PageHorizontal),
                ) {
                    com.openminis.app.ui.novex.TextButton(enabled = !isStreaming, onClick = {
                        capacityText = capacity.first?.toString().orEmpty(); capacityError = null; editingCapacity = true
                    }) { Text("校正模型上限") }
                }
            }

            val longform = NovexLongformModelPolicy.evaluate(
                effectiveWindowTokens = contextWindow,
                occupiedTokens = s?.context ?: 0,
                reservedOutputTokens = minOf(maxOutput ?: 16_000, 32_000),
            )
            NovexEditorSection(
                header = "长篇创作",
                footer = if (longform.tier == NovexLongformModelTier.UNKNOWN || !longform.meetsMinimum)
                    longform.guidance else null,
            ) {
                NovexSummaryRow("当前能力", longform.label, summaryTinted = longform.meetsMinimum)
                NovexSummaryRow("本轮资料预算", formatTokens(longform.moduleBudgetTokens))
            }

            thinking?.let { t ->
                NovexEditorSection(header = stringResource(R.string.token_usage_section_thinking)) {
                    NovexSummaryRow(stringResource(R.string.token_usage_thinking_label), if (t.enabled) onText else offText, summaryTinted = t.enabled)
                    if (t.enabled) NovexSummaryRow(stringResource(R.string.token_usage_thinking_level), t.level)
                    NovexSummaryRow(stringResource(R.string.token_usage_thinking_supported), if (t.supported) yesText else noText, summaryTinted = t.supported)
                }
            }

            NovexEditorSection(header = stringResource(R.string.token_usage_section_tokens)) {
                val inputTotal = (s?.input ?: 0L) + (s?.cacheRead ?: 0L) + (s?.cacheWrite ?: 0L)
                NovexSummaryRow(stringResource(R.string.token_usage_input_with_cache), formatTokens(inputTotal), summaryTinted = inputTotal > 0)
                NovexSummaryRow(stringResource(R.string.token_usage_output), formatTokens(s?.output ?: 0L), summaryTinted = (s?.output ?: 0L) > 0)
            }

            NovexEditorSection(header = stringResource(R.string.token_usage_section_cache)) {
                NovexSummaryRow(stringResource(R.string.token_usage_cache_read), formatTokens(s?.cacheRead ?: 0L), summaryTinted = (s?.cacheRead ?: 0L) > 0)
                NovexSummaryRow(stringResource(R.string.token_usage_cache_write), formatTokens(s?.cacheWrite ?: 0L), summaryTinted = (s?.cacheWrite ?: 0L) > 0)
            }

            NovexEditorSection(header = stringResource(R.string.token_usage_section_agent_loop)) {
                NovexSummaryRow(stringResource(R.string.token_usage_total_loops), (s?.loopCount ?: 0).toString(), summaryTinted = (s?.loopCount ?: 0) > 0)
            }
        }
    }
}

/** 上下文占用条：薄荷填充，>85% 转警示色；无数据时灰条+说明。 */
@Composable
private fun ContextUsageBar(used: Int?, window: Int, label: String) {
    val fraction = if (used != null && window > 0) (used.toFloat() / window).coerceIn(0f, 1f) else 0f
    val barColor = if (fraction > 0.85f) NovexColors.Danger else NovexColors.Primary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NovexDimensions.PageHorizontal, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = NovexColors.Text, style = NovexType.Body, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(
                if (used != null) "${formatTokens(used)} / ${formatTokens(window)}" else "尚未完成装配",
                color = NovexColors.SecondaryText,
                style = NovexType.Metadata,
                fontFamily = FontFamily.Monospace,
            )
        }
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(NovexColors.SurfaceMuted),
        ) {
            if (used != null) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor),
                )
            }
        }
    }
}

private fun formatTokens(n: Long): String = java.text.NumberFormat.getIntegerInstance().format(n.coerceAtLeast(0L))

private fun formatTokens(n: Int): String = formatTokens(n.toLong())
