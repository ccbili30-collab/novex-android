package com.openminis.app.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class NovexProviderSetupTest {
    @Test fun officialDefaultUsesV4FlashIdentifier() {
        assertEquals("deepseek-v4-flash", NOVEX_DEFAULT_DEEPSEEK_MODEL)
    }

    @Test fun officialDefaultHasLocalizedDisplayName() {
        assertEquals(
            "DeepSeek V4 Flash（深度求索 V4 快速版）",
            novexModelDisplayName(NOVEX_DEFAULT_DEEPSEEK_MODEL),
        )
    }
}
