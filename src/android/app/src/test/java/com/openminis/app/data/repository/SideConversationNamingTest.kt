package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-side-naming] 侧边对话命名守护：「侧边 N」编号取现有最大值+1（新旧两种
 * 命名都计数），删除中间一条后新建不重号；旧命名「主对话标题·侧N」仍可解析。
 */
class SideConversationNamingTest {
    @Test fun parsesNewAndLegacyNumbering() {
        assertEquals(3, sideConversationNumber("侧边 3"))
        assertEquals(12, sideConversationNumber("侧边12"))
        assertEquals(2, sideConversationNumber("海贼王大冒险·侧2"))
        assertNull(sideConversationNumber("随便聊"))
        assertNull(sideConversationNumber(""))
    }

    @Test fun nextTitleFromEmptyStartsAtOne() {
        assertEquals("侧边 1", nextSideConversationTitle(emptyList()))
    }

    @Test fun nextTitleContinuesAfterMaxNotCount() {
        // 删掉「侧边 1」后只剩「侧边 2」「侧边 3」→ 新建应为 4，而不是按数量重号成 3。
        assertEquals("侧边 4", nextSideConversationTitle(listOf("侧边 2", "侧边 3")))
    }

    @Test fun nextTitleCountsLegacyNamesToo() {
        assertEquals("侧边 4", nextSideConversationTitle(listOf("侧边 1", "海贼王·侧3")))
    }
}
