package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexChoiceFallbackTest {
    @Test
    fun `extracts explicit numbered choices near a selection question`() {
        val text = """
            你想从哪里开始？

            1. 大魔灾前夕
            2. 魔族入侵时代
            3. 战后重建时代
        """.trimIndent()

        assertEquals(
            listOf("大魔灾前夕", "魔族入侵时代", "战后重建时代"),
            NovexChoiceFallback.extract(text),
        )
    }

    @Test
    fun `extracts circled choices`() {
        assertEquals(
            listOf("查看信封", "找乳母谈话", "提前进城"),
            NovexChoiceFallback.extract("请选择一个行动：\n① 查看信封\n② 找乳母谈话\n③ 提前进城"),
        )
    }

    @Test
    fun `does not convert ordinary explanatory lists`() {
        val text = """
            世界存在以下文明：
            1. 王国与帝国
            2. 学院与教会
            3. 商会与贵族
        """.trimIndent()
        assertTrue(NovexChoiceFallback.extract(text).isEmpty())
    }

    @Test
    fun `does not convert character attributes`() {
        val text = """
            当前角色：
            1. 姓名：埃莉诺
            2. 年龄：20
            3. 身份：伯爵长女
        """.trimIndent()
        assertTrue(NovexChoiceFallback.extract(text).isEmpty())
    }
}
