package com.openminis.app.ui.chat

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.PlaythroughState
import kotlin.math.roundToInt

@Composable
internal fun NovexPlaythroughHud(
    sessionKey: String,
    state: PlaythroughState?,
    update: NovexDataUpdateEvent?,
    onDismissUpdate: () -> Unit,
) {
    if (state == null) return
    var expanded by rememberSaveable(sessionKey) { mutableStateOf(false) }
    var x by rememberSaveable(sessionKey) { mutableFloatStateOf(12f) }
    var y by rememberSaveable(sessionKey) { mutableFloatStateOf(120f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxX = with(density) { (maxWidth - if (expanded) 220.dp else 52.dp).toPx() }.coerceAtLeast(0f)
        // Leave room for the composer and navigation area. The panel can be
        // moved anywhere above this safe bottom band and follows the finger.
        val maxY = with(density) { (maxHeight - 140.dp).toPx() }.coerceAtLeast(0f)
        Column(
            Modifier
                .offset { IntOffset(x.roundToInt().coerceIn(0, maxX.roundToInt()), y.roundToInt().coerceIn(0, maxY.roundToInt())) },
            horizontalAlignment = Alignment.End,
        ) {
            if (update != null && !expanded) {
                Surface(
                    Modifier.padding(bottom = 6.dp).widthIn(max = 230.dp).clickable { expanded = true },
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 4.dp,
                ) {
                    Text("数据已更新（点击查看）", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .shadow(4.dp, RoundedCornerShape(16.dp))
                    .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); x += drag.x; y += drag.y } },
            ) {
                if (!expanded) {
                    Text("数据", Modifier.clickable { expanded = true }.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
                } else {
                    Column(Modifier.width(220.dp).padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("本局数据", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { expanded = false }) { Text("收起") }
                        }
                        if (update != null) {
                            Text("数据已更新", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
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
