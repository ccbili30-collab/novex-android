package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-side-naming] 书签条短标签：新名「侧边 N」/旧名「主对话标题·侧N」提取
 * 数字；其余标题回落首字（空标题回落「侧」）。条身半出屏可见区窄，只放数字。
 */
class BookmarkTabLabelTest {
    @Test fun extractsNumberFromNewNaming() {
        assertEquals("3", bookmarkTabLabel("侧边 3"))
        assertEquals("10", bookmarkTabLabel("侧边 10"))
    }

    @Test fun extractsNumberFromLegacyNaming() {
        assertEquals("2", bookmarkTabLabel("海贼王大冒险·侧2"))
    }

    @Test fun fallsBackToFirstCharacter() {
        assertEquals("海", bookmarkTabLabel("海贼王"))
        assertEquals("侧", bookmarkTabLabel(null))
        assertEquals("侧", bookmarkTabLabel("   "))
    }
}
