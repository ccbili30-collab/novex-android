package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerInputSynchronizerTest {
    @Test
    fun `delayed echoes during long press delete never overwrite newer local text`() {
        val sync = ComposerInputSynchronizer()
        sync.recordLocalEdit("甲乙丁戊")
        sync.recordLocalEdit("甲乙戊")
        sync.recordLocalEdit("甲戊")

        assertFalse(sync.shouldApplyExternal("甲乙丁戊"))
        assertFalse(sync.shouldApplyExternal("甲乙戊"))
        assertFalse(sync.shouldApplyExternal("甲戊"))
    }

    @Test
    fun `a real external insertion still replaces composer state`() {
        val sync = ComposerInputSynchronizer()
        sync.recordLocalEdit("正在输入")

        assertTrue(sync.shouldApplyExternal("外部插入的文字"))
    }
}
