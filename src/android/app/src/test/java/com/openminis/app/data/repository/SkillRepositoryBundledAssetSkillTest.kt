package com.openminis.app.data.repository

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class SkillRepositoryBundledAssetSkillTest {

    private fun newRepository(): SkillRepository = SkillRepository(RuntimeEnvironment.getApplication())

    @Test
    fun bundledWenyouMakerInstallsWithReferences() {
        val repo = newRepository()

        val skill = repo.skills.value.firstOrNull { it.id == "wenyou-maker" }
        assertNotNull("wenyou-maker should be installed on first init", skill)
        assertEquals("1.0.0", skill!!.version)
        assertEquals(SkillRepository.ImportSource.BUNDLED, skill.importSource)
        assertTrue("name must be ASCII so slugify can derive the id", skill.name == "wenyou-maker")
        assertTrue(
            "description carries the Chinese trigger text",
            skill.description.contains("文游"),
        )

        val files = repo.listSkillFiles("wenyou-maker")
        assertTrue("SKILL.md on disk: $files", files.contains("SKILL.md"))
        for (reference in listOf("engine.md", "pack-template.md", "database.md", "acceptance.md")) {
            assertTrue("references/$reference on disk: $files", files.contains("references/$reference"))
        }
        val engine = repo.readSkillFile("wenyou-maker", "references/engine.md")
        assertTrue("engine.md content intact", engine.orEmpty().contains("仲裁优先级"))
    }

    /** Temporary diagnostic: dumps exactly what the CI Robolectric sees. Remove before merge. */
    @Test
    fun diagnosticsAssetVisibility() {
        val app = RuntimeEnvironment.getApplication()
        val rootList = app.assets.list("skills/wenyou-maker").joinToString(",").ifEmpty { "<empty>" }
        val refsList = app.assets.list("skills/wenyou-maker/references").joinToString(",").ifEmpty { "<empty>" }
        val filesTxt = runCatching {
            app.assets.open("skills/wenyou-maker/FILES.txt").bufferedReader().use { it.readText() }
        }.fold({ it.take(80) }, { "ERR:${it.message}" })
        val skillMd = runCatching {
            app.assets.open("skills/wenyou-maker/SKILL.md").bufferedReader().use { it.readText() }
        }.fold({ it.take(40) }, { "ERR:${it.message}" })
        val engineMd = runCatching {
            app.assets.open("skills/wenyou-maker/references/engine.md").bufferedReader().use { it.readText() }
        }.fold({ it.take(40) }, { "ERR:${it.message}" })
        org.junit.Assert.fail(
            "DIAG list(root)=[$rootList] list(refs)=[$refsList] FILES.txt=[$filesTxt] SKILL.md=[$skillMd] engine.md=[$engineMd]",
        )
    }

    @Test
    fun sameVersionReinstallDoesNotClobberUserEdits() {
        val first = newRepository()
        val skillDir = File(
            File(RuntimeEnvironment.getApplication().filesDir, "minis-global/skills"),
            "wenyou-maker",
        )
        val edited = File(skillDir, "references/engine.md")
        edited.writeText("用户改过的引擎文本")

        // Second init runs installBundledSkills again; same version + files
        // present means the installer must skip entirely.
        val second = newRepository()
        assertEquals("用户改过的引擎文本", edited.readText())
        val skill = second.skills.value.first { it.id == "wenyou-maker" }
        assertEquals("1.0.0", skill.version)
    }

    @Test
    fun halfInstalledSkillSelfHealsWithoutVersionBump() {
        val first = newRepository()
        val skillDir = File(
            File(RuntimeEnvironment.getApplication().filesDir, "minis-global/skills"),
            "wenyou-maker",
        )
        // Simulate a half install: registry row exists (init created it) but the
        // directory was wiped before sibling files landed.
        skillDir.deleteRecursively()

        val second = newRepository()
        val files = second.listSkillFiles("wenyou-maker")
        assertTrue("references restored without a version bump: $files", files.contains("references/engine.md"))
    }

    @Test
    fun skillPromptFragmentDisclosesWenyouMakerWhenEnabled() {
        val repo = newRepository()
        val fragment = repo.skillPromptFragment(sessionId = "session-a")
        assertNotNull("bundled skills are enabled by default, fragment must exist", fragment)
        assertTrue(
            "fragment should disclose wenyou-maker",
            fragment!!.contains("wenyou-maker"),
        )
        assertTrue(
            "fragment should point at the readable SKILL.md path",
            fragment.contains("/var/minis/skills/wenyou-maker/SKILL.md"),
        )
    }
}
