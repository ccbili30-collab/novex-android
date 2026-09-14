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
 * 本局状态挂耳：可自由拖动的悬浮标签。拖动中是四角全圆的独立圆块；松手停在原地，
 * 贴近某条边缘（约 56dp 内）松手才吸附为该边的挂耳造型。
 *
 * 性能约定：拖动路径上的位置更新只经过 offset 的布局期状态读取（x/y），
 * 不触发重组；组合层只依赖 settledX/settledY（松手时写入一次）与 isDragging
 * （起止各一次）。状态载荷原样持久化，此层只做文本渲染。
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
    // Live drag position (px, top-left). -1 means "unplaced": dock to the right edge.
    var x by rememberSaveable(sessionKey) { mutableFloatStateOf(-1f) }
    var y by rememberSaveable(sessionKey) { mutableFloatStateOf(120f) }
    // Composition-facing anchor, written once on release — shapes and the panel
    // position derive from these so pointer moves never recompose this scope.
    var settledX by rememberSaveable(sessionKey) { mutableFloatStateOf(-1f) }
    var settledY by rememberSaveable(sessionKey) { mutableFloatStateOf(120f) }
    var isDragging by remember { mutableStateOf(false) }
    var earWidthPx by rememberSaveable(sessionKey) { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        // Leave room for the composer and navigation area.
        val maxY = with(density) { (maxHeight - 140.dp).toPx() }.coerceAtLeast(0f)
        val maxX = (screenWidthPx - earWidthPx).coerceAtLeast(0f)
        val snapMarginPx = with(density) { 56.dp.toPx() }
        val anchorX = if (settledX < 0f) maxX else settledX.coerceIn(0f, maxX)
        val anchorY = settledY.coerceIn(0f, maxY)
        val dockedTop = !isDragging && anchorY <= snapMarginPx && anchorX > snapMarginPx && maxX - anchorX > snapMarginPx
        val dockedLeft = !isDragging && !dockedTop && anchorX <= snapMarginPx
        val dockedRight = !isDragging && !dockedTop && !dockedLeft && maxX - anchorX <= snapMarginPx
        val preview = state.values.entries.firstNotNullOfOrNull { entry ->
            (entry.value as? PlaythroughValue.Number)?.let { entry.key to it.value }
        }

        if (!expanded) {
            val earShape = when {
                isDragging || (!dockedTop && !dockedLeft && !dockedRight) ->
                    RoundedCornerShape(14.dp)
                dockedTop -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                dockedLeft -> RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
                else -> RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
            }
            Surface(
                shape = earShape,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    // Layout-phase reads: finger moves relayout only, no recomposition.
                    .offset {
                        val px = if (x < 0f) maxX else x.coerceIn(0f, maxX)
                        IntOffset(px.roundToInt(), y.roundToInt().coerceIn(0, maxY.roundToInt()))
                    }
                    .then(if (isDragging) Modifier else Modifier.shadow(4.dp, earShape))
                    .onSizeChanged { measured ->
                        if (abs(measured.width.toFloat() - earWidthPx) > 1f) earWidthPx = measured.width.toFloat()
                    }
                    // maxX / maxY are composition vals — keyed so the clamps stay fresh.
                    .pointerInput(maxX, maxY) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDrag = { change, drag ->
                                change.consume()
                                x = (x + drag.x).coerceIn(0f, maxX)
                                y = (y + drag.y).coerceIn(0f, maxY)
                            },
                            onDragEnd = {
                                isDragging = false
                                val px = x.coerceIn(0f, maxX)
                                when {
                                    y <= snapMarginPx && px > snapMarginPx && maxX - px > snapMarginPx -> y = 0f
                                    px <= snapMarginPx -> x = 0f
                                    maxX - px <= snapMarginPx -> x = maxX
                                    // else: keep the free-floating position
                                }
                                settledX = x
                                settledY = y
                            },
                            onDragCancel = {
                                isDragging = false
                                settledX = x
                                settledY = y
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
            val panelWidth = 300.dp
            val panelWidthPx = with(density) { panelWidth.toPx() }
            val panelX = anchorX.coerceIn(0f, (screenWidthPx - panelWidthPx).coerceAtLeast(0f))
            // Grow downward from the ear; when little room remains the panel is short
            // and scrolls internally instead of overlapping the composer.
            val remainingPx = (with(density) { maxHeight.toPx() } - anchorY - with(density) { 120.dp.toPx() })
                .coerceAtLeast(with(density) { 96.dp.toPx() })
            val panelMaxHeight = (remainingPx / density.density).dp
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(panelX.roundToInt(), anchorY.roundToInt().coerceIn(0, maxY.roundToInt())) }
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
                        textEntries.forEachIndexed { index, (key, value) ->
                            if (numeric.isNotEmpty() || index > 0) Spacer(Modifier.height(14.dp))
                            HudSectionHeader(key)
                            Text(
                                value.displayValue(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        if (update != null && update.changes.isNotEmpty()) {
                            if (numeric.isNotEmpty() || textEntries.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(12.dp))
                            }
                            HudSectionHeader("本轮变更")
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
                        }
                        if (numeric.isEmpty() && textEntries.isEmpty() && (update == null || update.changes.isEmpty())) {
                            Text(
                                "暂无状态",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
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
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
