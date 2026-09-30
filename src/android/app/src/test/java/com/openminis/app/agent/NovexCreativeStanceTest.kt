package com.openminis.app.agent

import android.app.Application
import com.openminis.app.cards.IntegratedCardPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 创作搭档守护：普通交流先于建卡；灵感属于用户；明确要求建卡时流程照常放行。
 * 起因是用户导出的对话里 AI 每轮以“现在开始创建吗”收尾，并把用户的画面拆成分类槽。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexCreativeStanceTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val tools = setOf("novex_inspect_content", "novex_propose_content_changes", "novex_apply_content_changes", "render_panel")

    private fun assertStance(prompt: String) {
        assertEquals("创作搭档块只出现一次", 1, Regex("<创作搭档>").findAll(prompt).count())
        assertTrue(prompt.contains("</创作搭档>"))
        assertTrue("先写出来", prompt.contains("先写出来，少谈怎么写"))
        assertTrue("忠实用户设定", prompt.contains("不擅自埋伏笔，不调整剧情顺序"))
        assertTrue("不提议建卡", prompt.contains("不要提议创建，不把它当作下一步，不在结尾问要不要开始"))
        assertTrue("明确要求仍放行", prompt.contains("用户明确说要建、要存、要留下来，才进入建卡流程"))
        assertTrue("不替用户补制度成因", prompt.contains("不要因为讲不通就质疑，也不要替它补制度和成因"))
    }

    @Test fun `carded prompt leads with creative stance before card protocol`() {
        val prompt = IntegratedCardPrompt.build("", memory = false, tools = setOf("read_card", "create_card_bulk")).prompt
        assertStance(prompt)
        assertTrue("创作搭档必须先于卡片编辑协议", prompt.indexOf("<创作搭档>") < prompt.indexOf("<卡片编辑协议>"))
        assertTrue("建卡工具教学保留", prompt.contains("create_card_bulk / add_module_bulk"))
    }

    @Test fun `plain prompt carries the same stance in both tool modes`() {
        for (enabled in listOf(true, false)) {
            val prompt = NovexSystemPrompt.buildPrepared("stance", context, "用户风格", false, enabled, tools).prompt
            assertStance(prompt)
            assertTrue(prompt.contains("明确要求“做成世界卡、角色卡、文游卡”时直接进入对应原生工具流程"))
            assertFalse("不再把制度成因当作必补项", prompt.contains("具体的因果、制度、欲望、代价"))
        }
    }

    @Test fun `shared block does not leak template markers`() {
        assertFalse(NovexCreativeStance.BLOCK.contains("$"))
        assertFalse(NovexCreativeStance.BLOCK.contains("{{"))
    }
}
