package com.openminis.app.data.model

import novex.android.data.model.*
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R0 沙箱退役灰度钉子：能力提示词不得再向模型广告目录外的命令执行工具。 */
class LLMModelCapabilityHintTest {
    private val textOnly = LLMModel(
        id = "text-only-model",
        displayName = "Text Only",
        provider = "Test",
        inputModalities = listOf("text"),
    )

    @Test
    fun capabilityHintNeverAdvertisesShellTools() {
        val hint = textOnly.capabilityPromptFragment()
        assertNotNull(hint)
        assertTrue(hint!!.contains("cannot natively process"))
        assertTrue("hint must not advertise retired shell tools: $hint",
            !hint.contains("shell_execute") && !hint.contains("ffmpeg"))
    }
}
