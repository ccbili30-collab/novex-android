package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 侧边组件轨的守护测试（2026-09-15 用户修复批）：同边组件永不重叠、只有非常近
 * 才磁吸成列、上边停靠贴内容区顶端（不再叠加保留高度）、新落位自动找空槽。
 */
class NovexEdgeRailTest {
    private val geometry = NovexEdgeDockGeometry(
        screenW = 400f, screenH = 800f,
        ribbonW = 34f, ribbonH = 46f,
        bottomReserve = 170f,
    )
    private val maxAlong = geometry.maxAlong(NovexEdgeDock.RIGHT) // 800-46-170 = 584
    private val pitch = 46f + 8f // 54
    private val magnet = 24f

    @Test fun nearEntriesStackIntoAColumnFarOnesStayIndependent() {
        val resolved = NovexEdgeRail.resolveAlong(
            maxAlong, pitch, magnet,
            listOf(
                NovexEdgeRailEntry("a", NovexEdgeDock.RIGHT, 0.30f), // 175.2
                NovexEdgeRailEntry("b", NovexEdgeDock.RIGHT, 0.34f), // 198.6 → within magnet of a → stacked
                NovexEdgeRailEntry("c", NovexEdgeDock.RIGHT, 0.80f), // 467.2 → far → independent
            ),
        )
        assertEquals(175.2f, resolved["a"]!!, 0.01f)
        assertEquals(175.2f + pitch, resolved["b"]!!, 0.01f)
        assertEquals(467.2f, resolved["c"]!!, 0.01f)
    }

    @Test fun identicalPositionsNeverOverlapAndFormAStack() {
        val resolved = NovexEdgeRail.resolveAlong(
            maxAlong, pitch, magnet,
            listOf(
                NovexEdgeRailEntry("x", NovexEdgeDock.RIGHT, 0.5f),
                NovexEdgeRailEntry("y", NovexEdgeDock.RIGHT, 0.5f),
                NovexEdgeRailEntry("z", NovexEdgeDock.RIGHT, 0.5f),
            ),
        )
        val positions = listOf(resolved["x"]!!, resolved["y"]!!, resolved["z"]!!).sorted()
        assertEquals(292f, positions[0], 0.01f)
        assertEquals(pitch, positions[1] - positions[0], 0.01f)
        assertEquals(pitch, positions[2] - positions[1], 0.01f)
    }

    @Test fun justBeyondMagnetStaysIndependent() {
        // Desired distance from the previous resolved end = pitch + magnet + ε.
        val base = 0.10f
        val far = (base * maxAlong + pitch + magnet + 1f) / maxAlong
        val resolved = NovexEdgeRail.resolveAlong(
            maxAlong, pitch, magnet,
            listOf(
                NovexEdgeRailEntry("a", NovexEdgeDock.RIGHT, base),
                NovexEdgeRailEntry("b", NovexEdgeDock.RIGHT, far),
            ),
        )
        assertEquals(far * maxAlong, resolved["b"]!!, 0.01f)
    }

    @Test fun topDockLandsFlushAtContentTopNotFloatingInMidAir() {
        // beta.50 缺陷回归：上边停靠曾再叠加 90dp 顶部保留（宿主的 y=0 已在
        // 顶栏下方），看起来"吸附在半空中"。
        assertEquals(0f, geometry.anchorAt(NovexEdgeDock.TOP, 120f).y, 0.01f)
        val snapped = geometry.snap(androidx.compose.ui.geometry.Offset(200f, 12f))
        assertEquals(NovexEdgeDock.TOP, snapped.first)
        assertEquals(0f, geometry.anchor(NovexEdgeDock.TOP, snapped.second).y, 0.01f)
    }

    @Test fun snapPrefersTheNearestEdgeIncludingTop() {
        assertEquals(NovexEdgeDock.TOP, geometry.snap(androidx.compose.ui.geometry.Offset(200f, 20f)).first)
        assertEquals(NovexEdgeDock.RIGHT, geometry.snap(androidx.compose.ui.geometry.Offset(380f, 300f)).first)
        assertEquals(NovexEdgeDock.LEFT, geometry.snap(androidx.compose.ui.geometry.Offset(15f, 300f)).first)
    }

    @Test fun firstFreeFractionSkipsOccupiedSlotsIncludingTheHandle() {
        val handleAt = 0.35f
        val first = NovexEdgeRail.firstFreeFraction(maxAlong, pitch, listOf(handleAt), 0.35f)
        assertEquals(handleAt * maxAlong + pitch, first * maxAlong, 0.01f)
        val second = NovexEdgeRail.firstFreeFraction(maxAlong, pitch, listOf(handleAt, first), 0.35f)
        assertEquals(handleAt * maxAlong + 2 * pitch, second * maxAlong, 0.01f)
    }
}
