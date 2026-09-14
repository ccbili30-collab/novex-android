package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import com.openminis.app.ui.novex.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.novex.domain.PlaythroughValue
import kotlin.math.roundToInt

/**
 * 本局状态挂耳：一颗可自由拖动的悬浮标签。按住可任意方向拖动，松手后停在原地
 * 自由悬浮；靠近某条边缘（约 56dp 内）松手才吸附到该边。点开为贴同侧的悬浮面板。
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
    // Free-floating position in px from the top-left. -1 means "unplaced": dock to the right edge.
    var x by rememberSaveable(sessionKey) { mutableFloatStateOf(-1f) }
    var y by rememberSaveable(sessionKey) { mutableFloatStateOf(120f) }
    var earWidthPx by rememberSaveable(sessionKey) { mutableFloatStateOf(320f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val maxY = with(density) { (maxHeight - 140.dp).toPx() }.coerceAtLeast(0f)
        val maxX = (with(density) { maxWidth.toPx() } - earWidthPx).coerceAtLeast(0f)
        val placementX = if (x < 0f) maxX else x
        // Free placement by default; only magnet to an edge when released
        // close to one, so the ear can also hover anywhere as a standalone chip.
        val snapMarginPx = with(density) { 56.dp.toPx() }
        val dockedTop = placementX > snapMarginPx && maxX - placementX > snapMarginPx && y <= snapMarginPx
        val dockedLeft = placementX <= snapMarginPx
        val dockedRight = !dockedLeft && maxX - placementX <= snapMarginPx
        val freeFloating = !dockedTop && !dockedLeft && !dockedRight

        fun snapIfNearEdge() {
            when {
                y <= snapMarginPx && placementX > snapMarginPx && maxX - placementX > snapMarginPx -> y = 0f
                placementX <= snapMarginPx -> x = 0f
                maxX - placementX <= snapMarginPx -> x = maxX
                else -> x = placementX
            }
        }

        val onRight = dockedRight || (freeFloating && placementX >= maxX / 2f)
        val preview = state.values.entries.firstNotNullOfOrNull { entry ->
            (entry.value as? PlaythroughValue.Number)?.let { entry.key to it.value }
        }
        if (!expanded) {
            // Flush with the docked edge; only the inner corners round so the
            // tab reads as growing out of that edge. Free-floating keeps all
            // corners round — it is a standalone chip, not an ear.
            val earShape = when {
                freeFloating -> RoundedCornerShape(14.dp)
                onRight -> RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
                else -> RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
            }
            Surface(
                shape = earShape,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(placementX.roundToInt(), y.roundToInt().coerceIn(0, maxY.roundToInt())) }
                    .shadow(4.dp, earShape)
                    .onSizeChanged { earWidthPx = it.width.toFloat() }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                x = (placementX + drag.x).coerceIn(0f, maxX)
                                y = (y + drag.y).coerceIn(0f, maxY)
                            },
                            onDragEnd = { snapIfNearEdge() },
                        )
                    }
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
            // Panel grows from the same edge the ear docked to; capped by the
            // remaining height above the composer, content scrolls inside.
            val panelShape = if (onRight) {
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            }
            val panelMaxHeightDp = with(density) {
                val screenPx = maxHeight.toPx()
                val minPx = 200.dp.toPx()
                val capPx = 420.dp.toPx()
                val bottomSafePx = 120.dp.toPx()
                ((screenPx - y - bottomSafePx).coerceIn(minPx, capPx) / density.density).dp
            }
            Surface(
                shape = panelShape,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(if (onRight) Alignment.TopEnd else Alignment.TopStart)
                    .offset { IntOffset(0, y.roundToInt().coerceIn(0, maxY.roundToInt())) }
                    .shadow(4.dp, panelShape),
            ) {
                Column(Modifier.width(260.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
                    ) {
                        Text(
                            "本局状态",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { expanded = false }) { Text("收起") }
                    }
                    Column(
                        Modifier
                            .heightIn(max = panelMaxHeightDp)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    ) {
                        if (update != null && update.changes.isNotEmpty()) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Text("本轮变更", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "来源：${update.sourceLabel} · 回合 ${update.branchId.takeLast(8)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 1.dp),
                                )
                                update.changes.forEach { change ->
                                    Text(
                                        text = if (change.before.isNullOrBlank()) "${change.key}：设为 ${change.after}"
                                        else "${change.key}：${change.before} → ${change.after}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                                TextButton(onClick = onDismissUpdate, modifier = Modifier.align(Alignment.End)) { Text("知道了") }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                        Text(
                            "当前数值",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.values.entries.sortedBy { it.key }.forEach { (key, value) ->
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text(
                                    key,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(value.displayValue(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}
