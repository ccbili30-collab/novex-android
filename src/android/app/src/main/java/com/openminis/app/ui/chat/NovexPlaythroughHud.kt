package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.novex.domain.PlaythroughValue
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.TextButton

/**
 * 本局状态面板（2026-09-15 拆分）：把手已并入统一侧边组件轨
 * （[NovexSideConversations] 宿主），此文件只剩点按把手后展开的面板本体。
 * 内容与排序不变：数值 → 文本状态（变更优先）→ 本轮变更垫底。
 * 定位（offset/宽度/最大高度）由宿主通过 [modifier] 与 [maxHeight] 给定。
 */
@Composable
internal fun NovexPlaythroughPanel(
    state: PlaythroughState,
    update: NovexDataUpdateEvent?,
    onDismissUpdate: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: androidx.compose.ui.unit.Dp,
) {
    val preview = state.values.entries.firstNotNullOfOrNull { entry ->
        (entry.value as? PlaythroughValue.Number)?.let { entry.key to it.value }
    }
    Column(
        modifier
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .background(NovexColors.Surface, RoundedCornerShape(16.dp))
            .width(PanelWidth),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp),
        ) {
            Text(
                "本局状态 · " + (preview?.let { "${it.first} ${it.second}" } ?: "查看"),
                Modifier.weight(1f),
                style = NovexType.ItemTitle,
                fontWeight = FontWeight.SemiBold,
                color = NovexColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onCollapse) { Text("收起") }
        }
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        ) {
            // Importance order: scannable data first, then text states
            // (recently-changed first), change log last.
            val numeric = state.values.entries
                .filter { it.value is PlaythroughValue.Number || it.value is PlaythroughValue.Flag }
                .sortedBy { it.key }
            val changedKeys = update?.changes?.mapTo(hashSetOf()) { it.key }.orEmpty()
            val textEntries = state.values.entries
                .filter { it.value is PlaythroughValue.Text }
                .sortedWith(compareByDescending<Map.Entry<String, PlaythroughValue>> { it.key in changedKeys }.thenBy { it.key })

            if (numeric.isNotEmpty()) {
                HudSectionHeader("数值")
                numeric.forEach { (key, value) ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(key, Modifier.weight(1f), style = NovexType.Body, color = NovexColors.SecondaryText)
                        Text(value.displayValue(), style = NovexType.Body, fontWeight = FontWeight.Medium, color = NovexColors.Text)
                    }
                }
            }
            textEntries.forEachIndexed { index, (key, value) ->
                if (numeric.isNotEmpty() || index > 0) Spacer(Modifier.height(14.dp))
                HudSectionHeader(key)
                Text(value.displayValue(), style = NovexType.Body, color = NovexColors.Text, modifier = Modifier.padding(top = 6.dp))
            }
            if (update != null && update.changes.isNotEmpty()) {
                if (numeric.isNotEmpty() || textEntries.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(NovexColors.Divider))
                    Spacer(Modifier.height(12.dp))
                }
                HudSectionHeader("本轮变更")
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .background(NovexColors.SurfaceMuted.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        "来源：${update.sourceLabel} · 回合 ${update.branchId.takeLast(8)}",
                        style = NovexType.Metadata,
                        color = NovexColors.TertiaryText,
                    )
                    update.changes.forEach { change ->
                        Text(
                            text = if (change.before.isNullOrBlank()) "${change.key}：设为 ${change.after}"
                            else "${change.key}：${change.before} → ${change.after}",
                            style = NovexType.Body,
                            color = NovexColors.Text,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    TextButton(onClick = onDismissUpdate, modifier = Modifier.align(Alignment.End)) { Text("知道了") }
                }
            }
            if (numeric.isEmpty() && textEntries.isEmpty() && (update == null || update.changes.isEmpty())) {
                Text(
                    "暂无状态",
                    style = NovexType.Body,
                    color = NovexColors.SecondaryText,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun HudSectionHeader(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(end = 6.dp)
                .width(3.dp)
                .height(12.dp)
                .background(NovexColors.Primary, RoundedCornerShape(2.dp)),
        )
        Text(label, style = NovexType.Metadata, fontWeight = FontWeight.SemiBold, color = NovexColors.SecondaryText)
    }
}

internal val PanelWidth = 300.dp
