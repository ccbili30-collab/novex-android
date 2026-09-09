package com.openminis.app.ui.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexPanelContentTest {
    @Test fun emptyAndUnknownBlocksFailBeforeSuccess() {
        listOf("""{"title":"状态","summary":"位置线索","blocks":[]}""",
            """{"blocks":[{"type":"text","body":"邮局"}]}""",
            """{"blocks":[{"type":"stats","items":[]}]}""",
            """{"blocks":[{"type":"divider"}]}""").forEach {
            assertTrue(it, runCatching { NovexPanelContent.parse(JSONObject(it)) }.isFailure)
        }
    }
    @Test fun realStateAndTextSurviveBothArrayEncodings() {
        val raw = """[{"type":"stats","items":[{"label":"地点","value":"邮局"},{"label":"血量","value":0}]},{"type":"markdown","content":"线索：蓝色信封"}]"""
        listOf(JSONObject().put("blocks", raw), JSONObject("""{"blocks":$raw}""")).forEach {
            val parsed = NovexPanelContent.parse(it)
            assertEquals(2, parsed.blocks.size)
            assertEquals("邮局", parsed.blocks.first().getJSONArray("items").getJSONObject(0).getString("value"))
            assertEquals("线索：蓝色信封", parsed.blocks.last().getString("content"))
        }
    }
    @Test fun legacyItemsAndImagesAreRenderedInsteadOfSilentlyIgnored() {
        val parsed = NovexPanelContent.parse(JSONObject("""{"items":[{"label":"地点","value":"邮局"}],"images":["https://example.invalid/image.png"]}"""))
        assertEquals(listOf("gallery", "stats"), parsed.blocks.map { it.getString("type") })
    }
    @Test fun encodedNestedArraysAreNormalizedForTheRenderer() {
        val block = JSONObject().put("type", " stats ").put("items", """[{"label":"地点","value":"邮局"}]""")
        val parsed = NovexPanelContent.parse(JSONObject().put("blocks", org.json.JSONArray().put(block)))
        assertEquals("stats", parsed.blocks.single().getString("type"))
        assertEquals("邮局", parsed.blocks.single().getJSONArray("items").getJSONObject(0).getString("value"))
    }
}
