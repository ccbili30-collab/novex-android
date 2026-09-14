package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.openminis.app.ui.novex.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.novex.domain.PlaythroughValue
import kotlin.math.roundToInt

/**
 * 本局状态挂耳：贴屏幕右缘、可上下拖动的标签；点开为悬浮面板。
 * 状态载荷原样持久化，此层只做文本渲染 —— 结构化字段（如 value/max）与
 * HTML 血条渲染是后续升级位，不在本层锁死。
 */
@Composable
internal fun NovexPlaythroughHud(
    sessionKey: String,
    state: PlaythroughState?,
    update: NovexDataUpdateEvent?,
    onDismissUpdate: () -> Unit,
) {
    if (state == null) return
    var expanded by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var y by rememberSaveable(sessionKey) { mutableFloatStateOf(120f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // Leave room for the composer and navigation area; the ear slides
        // anywhere above this safe bottom band and follows the finger.
        val maxY = with(density) { (maxHeight - 140.dp).toPx() }.coerceAtLeast(0f)
        val preview = state.values.entries.firstNotNullOfOrNull { entry ->
            (entry.value as? PlaythroughValue.Number)?.let { entry.key to it.value }
        }
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y.roundToInt().coerceIn(0, maxY.roundToInt())) }
                .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); y += drag.y } },
            horizontalAlignment = Alignment.End,
        ) {
            if (!expanded) {
                // Right edge is flush with the screen border; only the left
                // corners round, so the tab reads as growing out of the edge.
                val earShape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
                Surface(
                    shape = earShape,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .shadow(4.dp, earShape)
                        .clickable { expanded = true },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
                    ) {
                        Text(
                            text = "状态" + preview?.let { (key, value) ->
                                " · $key ${if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()}"
                            }.orEmpty(),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp),
                        )
                        if (update != null) {
                            Box(
                                Modifier
                                    .padding(start = 6.dp)
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                        }
                    }
                }
            } else {
                val panelShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                Surface(
                    shape = panelShape,
                    tonalElevation = 6.dp,
                    modifier = Modifier.shadow(4.dp, panelShape),
                ) {
                    Column(Modifier.width(220.dp).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("本局状态", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { expanded = false }) { Text("收起") }
                        }
                        if (update != null) {
                            Text("状态已更新", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            Text("来源：${update.sourceLabel} · 回合 ${update.branchId.takeLast(8)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            update.changes.forEach { change ->
                                Text(
                                    "${change.key}：${change.before ?: "空"} → ${change.after}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            TextButton(onClick = onDismissUpdate, modifier = Modifier.align(Alignment.End)) { Text("知道了") }
                        }
                        state.values.entries.sortedBy { it.key }.forEach { (key, value) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(value.displayValue(), style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        }
    }
}
