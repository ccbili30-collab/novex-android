package com.openminis.app.provider.opencode

import com.openminis.app.data.model.LLMModel
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeFreeModelsApiTest {
    @Test
    fun `catalog is intersected with the live gateway model ids`() {
        val catalog = listOf(
            model("free-online"),
            model("free-removed"),
        )

        val filtered = OpenCodeFreeModelsApi.filterAvailable(
            catalog = catalog,
            liveIds = setOf("free-online", "paid-online"),
        )

        assertEquals(listOf("free-online"), filtered.map { it.model.id })
    }

    @Test
    fun `empty live set keeps the last catalog instead of deleting all choices`() {
        val catalog = listOf(model("free-cached"))

        val filtered = OpenCodeFreeModelsApi.filterAvailable(catalog, emptySet())

        assertEquals(listOf("free-cached"), filtered.map { it.model.id })
    }

    private fun model(id: String) = OpenCodeFreeModelsApi.FreeModel(
        model = LLMModel(id, id, "OpenCode Zen"),
        usesResponses = false,
    )
}
