package com.openminis.app.novex.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class NovexCreativeDistillationPolicyTest {
    @Test
    fun longFormSummaryPreservesCreativeContinuityAndDurableLocations() {
        val prompt = NovexCreativeDistillationPolicy.systemPrompt

        listOf("人物关系", "时间顺序", "因果", "伏笔", "叙事声音", "本局状态", "世界", "角色", "文游", "创作成果").forEach {
            assertTrue("缺少长期创作保真项：$it", prompt.contains(it))
        }
        assertTrue(prompt.contains("不要把摘要写成新的常驻任务"))
        assertTrue(prompt.contains("已落库"))
        assertTrue(prompt.contains("原始消息"))
        assertTrue(prompt.contains("正式成果"))
    }
}
