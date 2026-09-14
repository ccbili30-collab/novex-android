package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexChoiceButtonsTest {
    @Test fun `choice array accepts native array and preserves payload`() {
        val parsed = parseNovexChoiceOptions(
            """{"title":"下一步","choices":[{"label":"去城门","value":"前往城门"},{"label":"留在房间"}]}""",
        )
        assertEquals("下一步", parsed.first)
        assertEquals(listOf("去城门", "留在房间"), parsed.second.map { it.label })
        assertEquals("前往城门", parsed.second.first().payload)
        assertEquals("留在房间", parsed.second.last().payload)
    }

    @Test fun `choice string accepts legacy encoded array`() {
        val parsed = parseNovexChoiceOptions(
            """{"choices":"[\"一\",\"二\"]"}""",
        )
        assertEquals(listOf("一", "二"), parsed.second.map { it.label })
    }

    @Test fun `malformed choices never become a visible one item menu`() {
        val parsed = parseNovexChoiceOptions("{\"choices\":\"not-json\"}")
        assertTrue(parsed.second.isEmpty())
    }

    @Test fun `choice event preserves selection policy and expiry`() {
        val event = parseNovexChoiceEvent(
            """{"choices":["甲","乙"],"allow_multiple":true,"status":"expired"}""",
        )
        assertTrue(event.allowMultiple)
        assertTrue(event.expired)
        assertEquals(listOf("甲", "乙"), event.options.map { it.label })
    }
}
