package com.openminis.app.cards

import org.junit.Assert.assertTrue
import org.junit.Test

/** The organizing-discipline contract lines must survive in every card-bound prompt. */
class IntegratedCardPromptDisciplineTest {

    private fun prompt(): String = IntegratedCardPrompt.build(
        style = "默认",
        memory = false,
        tools = setOf("read_card"),
    ).prompt

    @Test
    fun contractCarriesTheVerbatimPreservationDiscipline() {
        val prompt = prompt()
        assertTrue(prompt.contains("整理纪律"))
        assertTrue(prompt.contains("原样入卡"))
        assertTrue(prompt.contains("未经要求不润色"))
        assertTrue(prompt.contains("不自创同义词"))
    }

    @Test
    fun contractCarriesTheStyleNeutralityDiscipline() {
        val prompt = prompt()
        assertTrue(prompt.contains("文中立"))
        assertTrue(prompt.contains("不用排比堆砌"))
        assertTrue(prompt.contains("不是……而是"))
        assertTrue(prompt.contains("不代入你自己的表达习惯"))
    }

    @Test
    fun disciplineAppliesWithAndWithoutTools() {
        val withoutTools = IntegratedCardPrompt.build(style = "默认", memory = false, tools = emptySet()).prompt
        assertTrue(withoutTools.contains("整理纪律"))
        assertTrue(withoutTools.contains("文中立"))
    }
}
