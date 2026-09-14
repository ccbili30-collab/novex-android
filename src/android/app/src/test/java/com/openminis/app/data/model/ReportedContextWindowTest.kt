package com.openminis.app.data.model
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReportedContextWindowTest {
    @Test fun upstreamFormatsAndOutputLimitRemainDistinct() {
        assertEquals(1000000,ReportedContextWindow.read(JSONObject("""{"context_window":"1M"}""")))
        assertEquals(262144,ReportedContextWindow.read(JSONObject("""{"max_model_len":262144}""")))
        assertEquals(128000,ReportedContextWindow.read(JSONObject("""{"limits":{"context":128000},"max_output_tokens":8192}""")))
        assertNull(ReportedContextWindow.read(JSONObject("""{"max_tokens":8192}""")))
        assertNull(ReportedContextWindow.read(JSONObject("""{"context_length":-1}""")))
    }
    @Test fun datedV4NamesAreRecognizedWithoutGuessingUnknownRelayAliases() {
        assertTrue(NovexDeepSeekModelMetadata.isKnownV4("vendor/DeepSeek-V4-Flash-0731"))
        assertTrue(NovexDeepSeekModelMetadata.isKnownV4("DeepSeek-V4-Pro-0813"))
        assertTrue(NovexDeepSeekModelMetadata.isKnownV4("deepseek-flash"))
        assertFalse(NovexDeepSeekModelMetadata.isKnownV4("deepseek-v3.2"))
    }
}
