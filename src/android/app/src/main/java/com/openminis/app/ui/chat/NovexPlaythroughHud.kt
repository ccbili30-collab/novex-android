package com.openminis.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.novex.domain.PlaythroughValue
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.TextButton

internal val PanelDefaultWidth = 300.dp
internal val PanelMinWidth = 240.dp
internal val PanelMinHeight = 220.dp

/**
 * 本局状态面板（edge-v1/03 定稿）：右缘锚定白卡、固定宽度、内容内部滚动——
 * 定位是"拉开看一眼的抽屉"，不做缩放柄/窗口管理（手机屏没有空地可拖）。
 * 数值带可选上限的渲染成进度条（AI 声明 max 即成条，如血条）；小节可折叠；
 * 不做标签页导航。
 */
@Composable
internal fun NovexPlaythroughPanel(
    state: PlaythroughState,
    update: NovexDataUpdateEvent?,
    onDismissUpdate: () -> Unit,
    onCollapse: () -> Unit,
    width: Dp = PanelDefaultWidth,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val collapsedSections = remember { mutableStateMapOf<String, Boolean>() }
    val preview = state.values.entries.firstNotNullOfOrNull { entry ->
        (entry.value as? PlaythroughValue.Number)?.let { entry.key to it.value }
    }
    Box(
        modifier
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(NovexColors.Surface, RoundedCornerShape(16.dp))
            .width(width),
    ) {
        Column(Modifier.heightIn(max = maxHeight)) {
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
                    CollapsibleHeader("数值", collapsedSections) {
                        numeric.forEach { (key, value) ->
                            StateValueRow(key, value)
                        }
                    }
                }
                textEntries.forEachIndexed { index, (key, value) ->
                    if (numeric.isNotEmpty() || index > 0) Spacer(Modifier.height(10.dp))
                    CollapsibleHeader(key, collapsedSections, emphasize = key in changedKeys) {
                        Text(
                            value.displayValue(),
                            style = NovexType.Body,
                            color = NovexColors.Text,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                if (update != null && update.changes.isNotEmpty()) {
                    if (numeric.isNotEmpty() || textEntries.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(NovexColors.Divider))
                        Spacer(Modifier.height(12.dp))
                    }
                    CollapsibleHeader("本轮变更", collapsedSections, forceOpen = true) {
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
}

/** 一行状态：带上限的数值渲染成进度条，其余保持 标签…值。 */
@Composable
private fun StateValueRow(key: String, value: PlaythroughValue) {
    val number = value as? PlaythroughValue.Number
    val barMax = number?.max
    // Design tokens must be read in composition — hoisted before any lambda.
    val trackColor = NovexColors.SurfaceMuted
    val lowColor = NovexColors.Danger
    val normalColor = NovexColors.Primary
    Row(
        Modifier.fillMaxWidth().padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(key, Modifier.weight(1f), style = NovexType.Body, color = NovexColors.SecondaryText)
        if (barMax != null && barMax > 0) {
            val fraction = ((number.value / barMax).coerceIn(0.0, 1.0)).toFloat()
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    value.displayValue(),
                    style = NovexType.Body,
                    fontWeight = FontWeight.Medium,
                    color = NovexColors.Text,
                )
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .width(96.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(trackColor),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (fraction <= 0.25f) lowColor else normalColor),
                    )
                }
            }
        } else {
            Text(value.displayValue(), style = NovexType.Body, fontWeight = FontWeight.Medium, color = NovexColors.Text)
        }
    }
}

/** 可折叠小节标题：点标题整节收起/展开；本轮变更强制展开。 */
@Composable
private fun CollapsibleHeader(
    label: String,
    collapsed: MutableMap<String, Boolean>,
    emphasize: Boolean = false,
    forceOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isCollapsed = !forceOpen && (collapsed[label] == true)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!forceOpen) Modifier.clickable {
                    collapsed[label] = !(collapsed[label] == true)
                } else Modifier)
                .padding(top = 4.dp),
        ) {
            Box(
                Modifier
                    .padding(end = 6.dp)
                    .width(3.dp)
                    .height(12.dp)
                    .background(if (emphasize) NovexColors.Danger else NovexColors.Primary, RoundedCornerShape(2.dp)),
            )
            Text(
                label,
                Modifier.weight(1f),
                style = NovexType.Metadata,
                fontWeight = FontWeight.SemiBold,
                color = NovexColors.SecondaryText,
            )
            if (!forceOpen) {
                Icon(
                    com.openminis.app.ui.novex.NovexIcons.KeyboardArrowDown,
                    contentDescription = null,
                    tint = NovexColors.TertiaryText,
                    modifier = Modifier.size(16.dp).rotate(if (isCollapsed) -90f else 0f),
                )
            }
        }
        if (!isCollapsed) content()
    }
}
