package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexCardReferenceCodecTest {
    @Test
    fun `foreign reference metadata survives address remapping without restoring the old address`() {
        val original = JSONObject("""{"id":"foreign","source":{"kind":"INTERACTIVE_FICTION","id":"old-game","origin":"来源修订"},"target":{"kind":"WORLD","id":"old-world","foreignAnchor":{"entry":"原文锚点"}},"purpose":"BACKGROUND","extensions":{"trigger":{"keys":["书院"],"probability":75}}}""")
        val imported = NovexCardReferenceCodec.decode(original.toString()).copy(id = "local-reference",
            source = NovexContentAddress.interactiveFiction("new-game"), target = NovexReferenceTarget(NovexContentAddress.world("new-world")))
        val encoded = JSONObject(NovexCardReferenceCodec.encode(imported))
        assertEquals("local-reference", encoded.getString("id"))
        assertEquals("new-game", encoded.getJSONObject("source").getString("id"))
        assertEquals("new-world", encoded.getJSONObject("target").getString("id"))
        assertEquals("来源修订", encoded.getJSONObject("source").optString("origin"))
        assertEquals("原文锚点", encoded.getJSONObject("target").getJSONObject("foreignAnchor").getString("entry"))
        assertEquals(original.getJSONObject("extensions").toString(), encoded.getJSONObject("extensions").toString())
    }
}
