package com.openminis.app.ui.chat

import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class ContextCapacityRegressionTest {
    @Test fun chineseCardsAndLongAsciiDoNotUseCharacterDividedByThree() {
        assertTrue(BPETokenizer.countTokens("海港的居民喜欢种花。".repeat(200000)) > 1_048_576)
        // All 27 successful live providers reported at least 140,000 input tokens here.
        assertTrue(BPETokenizer.countTokens(" a".repeat(140000)) >= 140311)
        assertEquals(0, BPETokenizer.countTokens(""))
        assertEquals(1069387,ProviderFailure.rejectedInputTokens("HTTP 400: maximum context length is 1048576 tokens. However, you requested 1069387 tokens (1069387 in the messages, 0 in the completion)."))
        assertTrue(BPETokenizer.countTokens("🕊️你好") > 0)
    }
    @Test fun meterUsesOneScaleAndEnabledDenominatorWithoutHidingOverflow() {
        val half = contextMeterGeometry(100000,1000000,500000)
        assertEquals(0.5f,half.enabled,0.0001f)
        assertEquals(0.1f,half.used,0.0001f)
        assertEquals(20,half.percent)
        assertEquals(120,contextMeterGeometry(600000,1000000,500000).percent)
        assertEquals(0,contextMeterGeometry(0,1000000,64000).percent)
    }
    @Test fun newOfficialAliasAndManualOverrideRemainDistinct() {
        val raw = LLMModel("deepseek-flash","Flash","DeepSeek",contextWindow=128000)
        val repaired = NovexDeepSeekModelMetadata.official(raw,"https://api.deepseek.com/v1")
        assertEquals(1000000,repaired.contextWindowTokens)
        assertEquals(128000,NovexDeepSeekModelMetadata.official(raw,"https://relay.example/v1").contextWindowTokens)
        val manual = ModelEntry("provider",repaired,ModelOverrides(contextWindow=200000),isCustom=true)
        assertEquals(200000,manual.model.contextWindowTokens)
    }
}
