package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.novex.domain.PlaythroughValue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 本局状态挂耳：可自由拖动的悬浮标签，松手后停在原地自由悬浮；只有贴近某条边缘
 * （约 56dp 内）松手才吸附到该边。点开为悬浮面板（分区：本轮变更 / 当前数值，
 * 内容内部滚动）。
 *
 * 拖动手势处理器里只能读 rememberSaveable 的状态（x/y 经代理实时读取）；
 * 组合期计算的局部 val（maxX 等）必须作为 pointerInput 的 key 传入，
 * 否则闭包捕获首次组合的旧值——横向拖动会被拉回初始边缘（beta.44 的缺陷）。
 * 状态载荷原样持久化，此层只做文本渲染；结构化字段与 HTML 血条是后续升级位。
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
    var earWidthPx by rememberSaveable(sessionKey) { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        // Leave room for the composer and navigation area.
        val maxY = with(density) { (maxHeight - 140.dp).toPx() }.coerceAtLeast(0f)
        val maxX = (screenWidthPx - earWidthPx).coerceAtLeast(0f)
        val placementX = if (x < 0f) maxX else x.coerceIn(0f, maxX)
        val snapMarginPx = with(density) { 56.dp.toPx() }
        val dockedTop = y <= snapMarginPx && placementX > snapMarginPx && maxX - placementX > snapMarginPx
        val dockedLeft = !dockedTop && placementX <= snapMarginPx
        val dockedRight = !dockedTop && !dockedLeft && maxX - placementX <= snapMarginPx
        val freeFloating = !dockedTop && !dockedLeft && !dockedRight
        val preview = state.values.entries.firstNotNullOfOrNull { entry ->
            (entry.value as? PlaythroughValue.Number)?.let { entry.key to it.value }
        }

        if (!expanded) {
            // Flush with the docked edge (only inner corners round); a free-floating
            // chip keeps all corners round; docked-top rounds its bottom corners.
            val earShape = when {
                freeFloating -> RoundedCornerShape(14.dp)
                dockedTop -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                dockedLeft -> RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
                else -> RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
            }
            Surface(
                shape = earShape,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(placementX.roundToInt(), y.roundToInt().coerceIn(0, maxY.roundToInt())) }
                    .shadow(4.dp, earShape)
                    .onSizeChanged { measured ->
                        if (abs(measured.width.toFloat() - earWidthPx) > 1f) earWidthPx = measured.width.toFloat()
                    }
                    // maxX / maxY are composition-time vals — pass them as keys so the
                    // gesture handler restarts (with fresh clamps) whenever they change.
                    .pointerInput(maxX, maxY) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                // x / y are remembered state delegates — live reads.
                                x = (x + drag.x).coerceIn(0f, maxX)
                                y = (y + drag.y).coerceIn(0f, maxY)
                            },
                            onDragEnd = {
                                val px = x.coerceIn(0f, maxX)
                                when {
                                    y <= snapMarginPx && px > snapMarginPx && maxX - px > snapMarginPx -> y = 0f
                                    px <= snapMarginPx -> x = 0f
                                    maxX - px <= snapMarginPx -> x = maxX
                                    // else: keep the free-floating position
                                }
                            },
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
            val panelWidth = 280.dp
            val panelWidthPx = with(density) { panelWidth.toPx() }
            val panelX = placementX.coerceIn(0f, (screenWidthPx - panelWidthPx).coerceAtLeast(0f))
            // Grow downward from the ear; never force a minimum that would push the
            // panel over the composer — when little room remains the panel is short
            // and scrolls internally instead of overlapping the input area.
            val remainingPx = (with(density) { maxHeight.toPx() } - y - with(density) { 120.dp.toPx() })
                .coerceAtLeast(with(density) { 96.dp.toPx() })
            val panelMaxHeight = (remainingPx / density.density).dp
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(panelX.roundToInt(), y.roundToInt().coerceIn(0, maxY.roundToInt())) }
                    .shadow(4.dp, RoundedCornerShape(16.dp)),
            ) {
                Column(Modifier.width(panelWidth)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp),
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
                            .heightIn(max = panelMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    ) {
                        if (update != null && update.changes.isNotEmpty()) {
                            Text(
                                "本轮变更",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    "来源：${update.sourceLabel} · 回合 ${update.branchId.takeLast(8)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                update.changes.forEach { change ->
                                    Text(
                                        text = if (change.before.isNullOrBlank()) "${change.key}：设为 ${change.after}"
                                        else "${change.key}：${change.before} → ${change.after}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                TextButton(onClick = onDismissUpdate, modifier = Modifier.align(Alignment.End)) { Text("知道了") }
                            }
                            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 10.dp))
                        }
                        Text(
                            "当前数值",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        state.values.entries.sortedBy { it.key }.forEach { (key, value) ->
                            Row(
                                Modifier.fillMaxWidth().padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
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
