package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-stream-stall-watchdog] 等待提示文案阈值：3 秒宽限（正常延迟不闪
 * 提示）、60 秒内只报秒数、满 1 分钟加"卡住将自动重连"（向用户传达
 * 5 分钟看门狗已武装，别干等）。
 */
class StreamAwaitingHintTextTest {

    @Test fun `grace period stays silent`() {
        assertNull(streamAwaitingHintText(0))
        assertNull(streamAwaitingHintText(2))
    }

    @Test fun `seconds only under a minute`() {
        assertEquals("已等待 3 秒", streamAwaitingHintText(3))
        assertEquals("已等待 59 秒", streamAwaitingHintText(59))
    }

    @Test fun `minute and beyond announces auto reconnect`() {
        assertEquals("已等待 1 分 0 秒 · 卡住将自动重连", streamAwaitingHintText(60))
        assertEquals("已等待 5 分 59 秒 · 卡住将自动重连", streamAwaitingHintText(359))
    }
}
