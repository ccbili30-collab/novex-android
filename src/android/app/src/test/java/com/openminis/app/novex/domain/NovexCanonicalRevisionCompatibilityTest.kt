package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NovexCanonicalRevisionCompatibilityTest {
    @Test fun savedDirectoryAndRevisionBytesKeepTheirOrderingAndEscaping() {
        val input = JSONObject().put("z", JSONArray().put(JSONObject().put("b", 2.5).put("a", false))
            .put(JSONObject.NULL).put(JSONArray()).put(JSONObject()))
            .put("a", "line\n\"\\\u0000\u8bbe\u5b9a\ud83d\udd4a")
        assertEquals(
            """{"a":"line\n\"\\\u0000设定🕊","z":[{"a":false,"b":2.5},null,[],{}]}""",
            canonicalRevisionJson(input),
        )
        assertEquals("null", canonicalRevisionJson(null))
    }
}
