package com.openminis.app.ui.chat

import java.io.File
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

    @Test
    fun `explicit choice-tool request gets one forced structured retry`() {
        val source = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()

        assertTrue(source.contains("MissingChoiceToolRecoveryPolicy"))
        assertTrue(source.contains("choiceRepairAttempted"))
        assertTrue(source.contains("forcedChoiceToolOnly"))
    }

    @Test
    fun `plain promise to add choices is not mistaken for a rendered menu`() {
        assertTrue(
            NovexChoiceFallback.extract(
                "对，我该在这给选项。抱歉，又说忘了。补上。饭吃得差不多了，这个节点——",
            ).isEmpty(),
        )
    }

    @Test
    fun `explicit choice tool request retries once and only once`() {
        val first = MissingChoiceToolRecoveryPolicy.decide(
            userRequest = "选项工具？",
            assistantText = "对，我该在这给选项。补上。",
            hasAnyToolCall = false,
            hasPresentChoicesCall = false,
            finishReason = "stop",
            presentChoicesAvailable = true,
            forcedAttempt = false,
        )
        val second = MissingChoiceToolRecoveryPolicy.decide(
            userRequest = "选项工具？",
            assistantText = "还是没有调用工具",
            hasAnyToolCall = false,
            hasPresentChoicesCall = false,
            finishReason = "stop",
            presentChoicesAvailable = true,
            forcedAttempt = true,
        )

        assertEquals(MissingChoiceToolRecoveryAction.RETRY_PRESENT_CHOICES, first)
        assertEquals(MissingChoiceToolRecoveryAction.FAIL_AFTER_RETRY, second)
    }

    @Test
    fun `ordinary story and existing numbered choices do not force a retry`() {
        assertEquals(
            MissingChoiceToolRecoveryAction.NONE,
            MissingChoiceToolRecoveryPolicy.decide(
                userRequest = "继续故事",
                assistantText = "她推开门，走入雨夜。",
                hasAnyToolCall = false,
                hasPresentChoicesCall = false,
                finishReason = "stop",
                presentChoicesAvailable = true,
                forcedAttempt = false,
            ),
        )
        assertEquals(
            MissingChoiceToolRecoveryAction.NONE,
            MissingChoiceToolRecoveryPolicy.decide(
                userRequest = "给我三个选项",
                assistantText = "请选择：\n1. 留下\n2. 离开\n3. 等待",
                hasAnyToolCall = false,
                hasPresentChoicesCall = false,
                finishReason = "stop",
                presentChoicesAvailable = true,
                forcedAttempt = false,
            ),
        )
    }
}
