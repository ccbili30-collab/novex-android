package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
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

/**
 * 本局状态把手（2026-09-14 决策 14：与书签同族缎带）：矩形主体贴齐停靠边、
 * 小尖角朝屏幕内侧——旧半圆的直径悬在屏幕内侧 24dp 处、看起来"离边脱开"的
 * 几何问题随形状一并消除。点按打开状态面板；长按拖出（变胶囊），松手吸附
 * 最近边；位置按会话持久化。面板内容不变：数值 → 文本状态（变更优先）→
 * 本轮变更垫底。
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
    val saved = remember(sessionKey) { NovexEdgePlacement.read(context, HUD_PREFS, sessionKey) }
    var dock by remember(sessionKey) { mutableStateOf(saved.first) }
    var fraction by remember(sessionKey) { mutableFloatStateOf(saved.second) }
    // Transient drag position (px, box top-left); materialized from the anchor
    // on drag start so no sentinel value ever enters the accumulation.
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val preview = state.values.entries.firstNotNullOfOrNull { entry ->
        (entry.value as? PlaythroughValue.Number)?.let { entry.key to it.value }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val handlePxW = with(density) { HudRibbonWidth.toPx() }
        val handlePxH = with(density) { HudRibbonHeight.toPx() }
        val geometry = NovexEdgeDockGeometry(
            screenW = screenWidthPx, screenH = screenHeightPx,
            ribbonW = handlePxW, ribbonH = handlePxH,
            bottomReserve = with(density) { 170.dp.toPx() },
        )

        fun anchor(): Offset = geometry.anchor(dock, fraction)

        if (!expanded) {
            // Design tokens read in composition; the ribbon body is composable.
            NovexEdgeRibbon(
                dock = dock,
                fill = NovexColors.Surface.copy(alpha = 0.95f),
                rim = NovexColors.Divider,
                width = HudRibbonWidth,
                height = HudRibbonHeight,
                modifier = Modifier.offset {
                    IntOffset(
                        (if (isDragging) dragX else anchor().x).roundToInt(),
                        (if (isDragging) dragY else anchor().y).roundToInt(),
                    )
                },
                floating = isDragging,
                badge = update != null,
                onTap = { expanded = true },
                drag = object : NovexEdgeDragCallbacks {
                    override fun onDragStart() {
                        isDragging = true
                        dragX = anchor().x
                        dragY = anchor().y
                    }

                    override fun onDrag(delta: Offset, change: PointerInputChange) {
                        dragX = (dragX + delta.x).coerceIn(0f, screenWidthPx - handlePxW)
                        dragY = (dragY + delta.y).coerceIn(0f, screenHeightPx - handlePxH)
                    }

                    override fun onDragEnd() {
                        val snapped = geometry.snap(Offset(dragX + handlePxW / 2f, dragY + handlePxH / 2f))
                        dock = snapped.first
                        fraction = snapped.second
                        isDragging = false
                        NovexEdgePlacement.write(context, HUD_PREFS, sessionKey, dock, fraction)
                    }

                    override fun onDragCancel() { isDragging = false }
                },
            ) {
                // Chevron points toward the screen interior.
                val chevron = when (dock) {
                    NovexEdgeDock.RIGHT -> com.openminis.app.ui.novex.NovexIcons.KeyboardArrowLeft
                    NovexEdgeDock.LEFT -> com.openminis.app.ui.novex.NovexIcons.KeyboardArrowRight
                    NovexEdgeDock.TOP -> com.openminis.app.ui.novex.NovexIcons.KeyboardArrowDown
                }
                Icon(chevron, contentDescription = "本局状态", tint = NovexColors.SecondaryText)
            }
        } else {
            val panelWidth = 300.dp
            val panelWidthPx = with(density) { panelWidth.toPx() }
            val panelX = (when (dock) {
                NovexEdgeDock.LEFT -> 0f
                NovexEdgeDock.RIGHT -> (screenWidthPx - panelWidthPx).coerceAtLeast(0f)
                NovexEdgeDock.TOP -> fraction.coerceIn(0f, 1f) * (screenWidthPx - panelWidthPx).coerceAtLeast(0f)
            }).roundToInt()
            val panelYRaw = when (dock) {
                NovexEdgeDock.TOP -> 0f
                else -> fraction.coerceIn(0f, 1f) * (screenHeightPx - with(density) { 170.dp.toPx() }).coerceAtLeast(0f)
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

private val HudRibbonWidth = 24.dp
private val HudRibbonHeight = 44.dp
private const val HUD_PREFS = "novex_playthrough_hud"
