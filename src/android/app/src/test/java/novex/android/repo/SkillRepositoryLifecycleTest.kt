package novex.android.repo

import android.app.Application

import com.openminis.app.data.repository.SkillRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P3.5a 重写后的技能仓库关键行为守护：
 *  - SKILL.md 文本导入与同 id 原位替换（保留启用位与使用计数）；
 *  - 会话级开关覆盖全局位、草稿→正式会话的覆盖行改挂；
 *  - 使用计数与频段；
 *  - 虚拟路径 → 技能 id 的换算边界。
 * 捆绑技能的安装/自愈另有 SkillRepositoryBundledAssetSkillTest 钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class SkillRepositoryLifecycleTest {

    private fun newRepository(): SkillRepository = SkillRepository(RuntimeEnvironment.getApplication())

    private fun skillMd(name: String, description: String, version: String = "1.2.0"): String = """
        ---
        name: $name
        description: $description
        version: $version
        ---
        # 正文指令
        做事要有条理。
    """.trimIndent()

    @Test
    fun importFromContentReplacesInPlacePreservingToggleAndUsage() {
        val repo = newRepository()
        val first = repo.importFromContent(skillMd("demo skill", "第一版描述"))!!
        repo.setEnabled(first.id, false)
        repo.recordSkillUse(first.id)

        val second = repo.importFromContent(skillMd("demo skill", "第二版描述", version = "2.0.0"))!!

        assertEquals(first.id, second.id)
        assertEquals("第二版描述", second.description)
        assertEquals("2.0.0", second.version)
        // 原位替换不动启用位与使用计数 —— 重复导入是「更新」，不是新技能。
        assertFalse(second.isEnabled)
        assertEquals(1.0, second.useCount, 0.0001)
        assertEquals(1, repo.skills.value.count { it.id == first.id })
    }

    @Test
    fun sessionOverrideBeatsGlobalToggleAndRetargetFollowsDraftPromotion() {
        val repo = newRepository()
        val skill = repo.importFromContent(skillMd("toggle me", "开关实验"))!!
        assertTrue(repo.isEnabledForSession(skill.id, "session-any"))

        repo.setSessionOverride("session-draft", skill.id, false)
        assertFalse(repo.isEnabledForSession(skill.id, "session-draft"))
        // 其他会话不受影响。
        assertTrue(repo.isEnabledForSession(skill.id, "session-other"))

        // ensureSession 之后把草稿键改挂到正式键：覆盖必须跟着走。
        repo.renameSessionOverrides("session-draft", "session-real")
        assertTrue(repo.isEnabledForSession(skill.id, "session-draft"))
        assertFalse(repo.isEnabledForSession(skill.id, "session-real"))

        repo.clearSessionOverrides("session-real")
        assertTrue(repo.isEnabledForSession(skill.id, "session-real"))
    }

    @Test
    fun promptFragmentFollowsSessionVisibility() {
        val repo = newRepository()
        val skill = repo.importFromContent(skillMd("frag skill", "片段实验"))!!
        val fragment = repo.skillPromptFragment("session-frag")
        assertNotNull(fragment)
        assertTrue(fragment!!.contains("frag skill"))
        assertTrue(fragment.contains("/var/minis/skills/${skill.id}/SKILL.md"))

        repo.setSessionOverride("session-frag", skill.id, false)
        val hidden = repo.skillPromptFragment("session-frag")
        // 捆绑技能仍在（片段非 null），但本技能必须消失。
        assertNotNull(hidden)
        assertFalse(hidden!!.contains("frag skill"))
    }

    @Test
    fun usageBandsTrackRelativeCount() {
        val repo = newRepository()
        val skill = repo.importFromContent(skillMd("usage skill", "频段实验"))!!
        assertEquals(SkillRepository.UsageFrequency.NEVER, repo.usageFrequency(skill.id))

        repo.recordSkillUse(skill.id)
        // 唯一被用过的技能即最大值 → HIGH。
        assertEquals(SkillRepository.UsageFrequency.HIGH, repo.usageFrequency(skill.id))
    }

    @Test
    fun skillIdFromPathOnlyMatchesKnownSkillMd() {
        val repo = newRepository()
        val skill = repo.importFromContent(skillMd("path skill", "路径实验"))!!

        assertEquals(skill.id, repo.skillIdFromPath("/var/minis/skills/${skill.id}/SKILL.md"))
        // 子资源读取不算技能使用。
        assertNull(repo.skillIdFromPath("/var/minis/skills/${skill.id}/scripts/run.sh"))
        // 未知技能不认。
        assertNull(repo.skillIdFromPath("/var/minis/skills/ghost/SKILL.md"))
        assertNull(repo.skillIdFromPath("/var/other/${skill.id}/SKILL.md"))
    }

    @Test
    fun writeSkillFileForSkillMdFlowsBackToRegistry() {
        val repo = newRepository()
        val skill = repo.importFromContent(skillMd("rescan skill", "旧描述"))!!

        val rewritten = skillMd("rescan skill", "手工改过的新描述", version = "9.9.9")
        assertTrue(repo.writeSkillFile(skill.id, "SKILL.md", rewritten))

        val refreshed = repo.rescanFromDisk(skill.id)!!
        assertEquals("手工改过的新描述", refreshed.description)
        assertEquals("9.9.9", refreshed.version)
    }
}
