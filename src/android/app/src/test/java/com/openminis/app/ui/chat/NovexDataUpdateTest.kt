package com.openminis.app.ui.chat

import com.openminis.app.novex.domain.PlaythroughState
import com.openminis.app.novex.domain.PlaythroughValue
import org.junit.Assert.assertEquals
import org.junit.Test

class NovexDataUpdateTest {
    @Test fun `diff reports added and changed values in stable order`() {
        val before = PlaythroughState(
            branchId = "old",
            values = mapOf("地点" to PlaythroughValue.Text("村口"), "击杀数" to PlaythroughValue.Number(3.0)),
        )
        val after = PlaythroughState(
            branchId = "new",
            values = mapOf("地点" to PlaythroughValue.Text("旧马厩"), "生命" to PlaythroughValue.Flag(true), "击杀数" to PlaythroughValue.Number(3.0)),
        )
        assertEquals(
            listOf(
                NovexDataChange("地点", "村口", "旧马厩"),
                NovexDataChange("生命", null, "是"),
            ),
            diffNovexPlaythroughState(before, after),
        )
    }

    @Test fun `unchanged values produce no software update`() {
        val state = PlaythroughState("same", mapOf("生命" to PlaythroughValue.Flag(false)))
        assertEquals(emptyList<NovexDataChange>(), diffNovexPlaythroughState(state, state))
    }
}
