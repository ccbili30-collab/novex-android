package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.novex.domain.PlaythroughValue
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.TextButton
import kotlin.math.roundToInt

/** Docked edges for the playthrough state handle. */
private enum class HudDock { LEFT, RIGHT, TOP }

/**
 * 本局状态把手：贴边（左/右/上）的半圆把手，箭头指向屏幕内侧；按住拖出时变成
 * 圆形（朝向不变），松手永远吸附回最近的边。位置（边 + 沿边比例）按会话持久化，
 * 重开对话保持。点按打开状态面板（数值 → 文本状态 → 变更记录垫底）。
 *
 * 性能约定：拖动只经 offset 布局期读取，重组仅发生在按下/松手；把手用 Canvas
 * 自绘并使用 Novex 设计令牌。状态载荷原样持久化，此层只做文本渲染。
 */
@Composable
internal fun NovexPlaythroughHud(
    sessionKey: String,
    state: PlaythroughState?,
    update: NovexDataUpdateEvent?,
    onDismissUpdate: () -> Unit,
) {
    if (state == null) return
    val context = LocalContext.current
    var expanded by rememberSaveable(sessionKey) { mutableStateOf(false) }
    val saved = remember(sessionKey) { readHudPlacement(context, sessionKey) }
    var dock by remember(sessionKey) { mutableStateOf(saved.first) }
    var fraction by remember(sessionKey) { mutableFloatStateOf(saved.second) }
    // Transient drag position (px, box top-left); materialized from the anchor
    // on drag start so no sentinel value ever enters the accumulation.
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val handlePx = with(density) { HandleSize.toPx() }
        val panelWidth = 300.dp
        val panelWidthPx = with(density) { panelWidth.toPx() }
        // Bottom reserve: composer + navigation; the handle never parks there.
        val maxVerticalPx = (screenHeightPx - with(density) { 170.dp.toPx() }).coerceAtLeast(0f)
        val maxHorizontalPx = (screenWidthPx - handlePx).coerceAtLeast(0f)

        fun anchorX(): Float = when (dock) {
            HudDock.LEFT -> 0f
            HudDock.RIGHT -> maxHorizontalPx
            HudDock.TOP -> fraction.coerceIn(0f, 1f) * maxHorizontalPx
        }
        fun anchorY(): Float = when (dock) {
            HudDock.TOP -> 0f
            else -> fraction.coerceIn(0f, 1f) * maxVerticalPx
        }
        val preview = state.values.entries.firstNotNullOfOrNull { entry ->
            (entry.value as? PlaythroughValue.Number)?.let { entry.key to it.value }
        }

        if (!expanded) {
            // Design-token colors must be read in composition; the draw lambda
            // only captures the resolved values.
            val handleFill = NovexColors.Surface.copy(alpha = 0.94f)
            val handleRim = NovexColors.Divider
            val chevronColor = NovexColors.SecondaryText
            val badgeColor = NovexColors.Primary
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(HandleSize)
                    .offset {
                        IntOffset(
                            (if (isDragging) dragX else anchorX()).roundToInt(),
                            (if (isDragging) dragY else anchorY()).roundToInt(),
                        )
                    }
                    .then(if (isDragging) Modifier else Modifier.shadow(3.dp, CircleShape))
                    .pointerInput(screenWidthPx, screenHeightPx) {
                        detectDragGestures(
                            onDragStart = {
                                isDragging = true
                                dragX = anchorX()
                                dragY = anchorY()
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                dragX = (dragX + drag.x).coerceIn(0f, maxHorizontalPx)
                                dragY = (dragY + drag.y).coerceIn(0f, maxVerticalPx)
                            },
                            onDragEnd = {
                                val centerX = dragX + handlePx / 2f
                                val centerY = dragY + handlePx / 2f
                                val toLeft = centerX
                                val toRight = screenWidthPx - centerX
                                val toTop = centerY
                                dock = when {
                                    toTop <= toLeft && toTop <= toRight -> HudDock.TOP
                                    toLeft <= toRight -> HudDock.LEFT
                                    else -> HudDock.RIGHT
                                }
                                fraction = when (dock) {
                                    HudDock.TOP -> (dragX / maxHorizontalPx).coerceIn(0f, 1f)
                                    else -> (dragY / maxVerticalPx).coerceIn(0f, 1f)
                                }
                                isDragging = false
                                writeHudPlacement(context, sessionKey, dock, fraction)
                            },
                            onDragCancel = { isDragging = false },
                        )
                    }
                    .clickable { expanded = true },
            ) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val fill = handleFill
                val rim = handleRim
                val chevron = chevronColor
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                if (isDragging) {
                    drawCircle(fill, radius = radius, center = center)
                    drawCircle(rim, radius = radius, center = center, style = Stroke(width = 1.dp.toPx()))
                } else {
                    // Half-disc flush with the docked edge; chevron points inward.
                    val (start, sweep) = when (dock) {
                        HudDock.RIGHT -> 90f to 180f
                        HudDock.LEFT -> 270f to 180f
                        HudDock.TOP -> 0f to 180f
                    }
                    drawArc(fill, startAngle = start, sweepAngle = sweep, useCenter = true)
                    drawArc(rim, startAngle = start, sweepAngle = sweep, useCenter = true, style = Stroke(width = 1.dp.toPx()))
                }
                val tip = radius * 0.30f
                val armX = radius * 0.16f
                val armY = radius * 0.34f
                val path = androidx.compose.ui.graphics.Path().apply {
                    when (dock) {
                        HudDock.RIGHT -> { // chevron "<"
                            moveTo(center.x + armX, center.y - armY)
                            lineTo(center.x - tip, center.y)
                            lineTo(center.x + armX, center.y + armY)
                        }
                        HudDock.LEFT -> { // chevron ">"
                            moveTo(center.x - armX, center.y - armY)
                            lineTo(center.x + tip, center.y)
                            lineTo(center.x - armX, center.y + armY)
                        }
                        HudDock.TOP -> { // chevron "∨"
                            moveTo(center.x - armY, center.y - armX)
                            lineTo(center.x, center.y + tip)
                            lineTo(center.x + armY, center.y - armX)
                        }
                    }
                }
                drawPath(path, color = chevron, style = stroke)
                if (update != null && !isDragging) {
                    val badgeCenter = when (dock) {
                        HudDock.RIGHT -> Offset(center.x - radius * 0.62f, center.y - radius * 0.62f)
                        HudDock.LEFT -> Offset(center.x + radius * 0.62f, center.y - radius * 0.62f)
                        HudDock.TOP -> Offset(center.x, center.y + radius * 0.55f)
                    }
                    drawCircle(badgeColor, radius = 3.5.dp.toPx(), center = badgeCenter)
                }
            }
        } else {
            val panelX = (when (dock) {
                HudDock.LEFT -> 0f
                HudDock.RIGHT -> (screenWidthPx - panelWidthPx).coerceAtLeast(0f)
                HudDock.TOP -> fraction.coerceIn(0f, 1f) * (screenWidthPx - panelWidthPx).coerceAtLeast(0f)
            }).roundToInt()
            val panelYRaw = when (dock) {
                HudDock.TOP -> 0f
                else -> fraction.coerceIn(0f, 1f) * maxVerticalPx
            }
            val remainingPx = (screenHeightPx - panelYRaw - with(density) { 120.dp.toPx() })
                .coerceAtLeast(with(density) { 96.dp.toPx() })
            val panelMaxHeight = (remainingPx / density.density).dp
            Column(
                Modifier
                    .offset { IntOffset(panelX, panelYRaw.roundToInt()) }
                    .shadow(4.dp, RoundedCornerShape(16.dp))
                    .background(NovexColors.Surface, RoundedCornerShape(16.dp))
                    .width(panelWidth),
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

private val HandleSize = 48.dp
private const val HUD_PREFS = "novex_playthrough_hud"

private fun readHudPlacement(context: Context, sessionKey: String): Pair<HudDock, Float> {
    val raw = context.getSharedPreferences(HUD_PREFS, Context.MODE_PRIVATE).getString(sessionKey, null) ?: return HudDock.RIGHT to 0.35f
    val parts = raw.split(':')
    val dock = parts.getOrNull(0)?.let { runCatching { HudDock.valueOf(it) }.getOrNull() } ?: HudDock.RIGHT
    val fraction = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.35f
    return dock to fraction
}

private fun writeHudPlacement(context: Context, sessionKey: String, dock: HudDock, fraction: Float) {
    context.getSharedPreferences(HUD_PREFS, Context.MODE_PRIVATE)
        .edit().putString(sessionKey, "${dock.name}:${fraction.coerceIn(0f, 1f)}").apply()
}
