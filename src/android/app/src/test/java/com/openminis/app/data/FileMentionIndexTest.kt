package com.openminis.app.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [血统清剿 P3.7 行为钉] FileMentionIndex 索引行为——三层扫描的 linux 路
 * 径拼装、根自指条目、作用域次序与 @ 查询评分钉死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileMentionIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newIndex(mounts: List<FileMentionIndex.MountEntry> = emptyList()): FileMentionIndex =
        FileMentionIndex(
            filesDir = tmp.root,
            mountsProvider = { mounts },
            cacheTtlMs = 10_000,
            scope = CoroutineScope(dispatcher),
        )

    private fun plant(relative: String, content: String = "x"): File {
        val f = File(tmp.root, relative)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    @Test
    fun `session roots map onto var-minis linux paths with self entries`() = runTest(dispatcher) {
        plant("workspace/s1/notes.md")
        plant("attachments/s1/photo.png")
        val index = newIndex()
        index.refreshIfNeeded("s1")

        val paths = index.entries.value.map { it.linuxPath }
        assertTrue(paths.contains("/var/minis/workspace/s1"))
        assertTrue(paths.contains("/var/minis/workspace/s1/notes.md"))
        assertTrue(paths.contains("/var/minis/attachments/s1"))
        assertTrue(paths.contains("/var/minis/attachments/s1/photo.png"))
        // displayPath 剥掉 /var/minis/ 前缀。
        val note = index.entries.value.first { it.basename == "notes.md" }
        assertEquals("workspace/s1/notes.md", note.displayPath)
    }

    @Test
    fun `shared roots are indexed with scope labels`() = runTest(dispatcher) {
        plant("shared/readme.txt")
        plant("skills/codex-image/SKILL.md")
        plant("memory/daily.md")
        val index = newIndex()
        index.refreshIfNeeded("s1")

        val skill = index.entries.value.first { it.linuxPath == "/var/minis/skills/codex-image" }
        assertEquals(FileMentionIndex.Scope.SKILLS, skill.scope)
        val memoryFile = index.entries.value.first { it.basename == "daily.md" }
        assertEquals(FileMentionIndex.Scope.MEMORY, memoryFile.scope)
        assertTrue(index.entries.value.any { it.linuxPath == "/var/minis/shared" })
    }

    @Test
    fun `dotfiles and skip dirs are excluded`() = runTest(dispatcher) {
        plant("workspace/s2/.hidden")
        plant("workspace/s2/node_modules/pkg/index.js")
        plant("workspace/s2/real.txt")
        val index = newIndex()
        index.refreshIfNeeded("s2")

        val names = index.entries.value.map { it.basename }
        assertTrue(names.contains("real.txt"))
        assertTrue(names.contains("node_modules").not())
        assertTrue(names.contains(".hidden").not())
    }

    @Test
    fun `mount entries always carry a self entry under mounts dir`() = runTest(dispatcher) {
        val mountRoot = tmp.newFolder("my-mount")
        File(mountRoot, "inner.txt").writeText("x")
        val index = newIndex(
            listOf(FileMentionIndex.MountEntry(name = "docs", root = mountRoot)),
        )
        index.refreshIfNeeded("s1")

        val paths = index.entries.value.map { it.linuxPath }
        assertTrue(paths.contains("/var/minis/mounts/docs"))
        assertTrue(paths.contains("/var/minis/mounts/docs/inner.txt"))
    }

    @Test
    fun `empty query returns entries in default scope order`() = runTest(dispatcher) {
        plant("workspace/s3/w.md")
        plant("skills/skill-dir")
        val index = newIndex()
        index.refreshIfNeeded("s3")

        val scopes = index.matches("").map { it.scope }
        // skills 排在 workspace 之前（order 0 < 4）。
        val firstWorkspaceIdx = scopes.indexOf(FileMentionIndex.Scope.WORKSPACE)
        val lastSkillsIdx = scopes.lastIndexOf(FileMentionIndex.Scope.SKILLS)
        assertTrue(
            "skills must sort before workspace; got $scopes",
            lastSkillsIdx < firstWorkspaceIdx,
        )
    }

    @Test
    fun `exact basename match outranks path-only match`() = runTest(dispatcher) {
        plant("workspace/s4/report.md")
        plant("workspace/s4/deep/report-backup.md")
        val index = newIndex()
        index.refreshIfNeeded("s4")

        val top = index.matches("report").first()
        assertEquals("report.md", top.basename)
    }

    @Test
    fun `word boundary partial match beats arbitrary substring`() = runTest(dispatcher) {
        plant("workspace/s5/markdown_parser.py")
        plant("workspace/s5/xparserz.txt")
        val index = newIndex()
        index.refreshIfNeeded("s5")

        val ranked = index.matches("parser").map { it.basename }
        // markdown_parser.py 的词边界命中应压过 xparserz 的任意位子串。
        assertTrue(ranked.indexOf("markdown_parser.py") < ranked.indexOf("xparserz.txt"))
    }

    @Test
    fun `refresh caches within ttl - no duplicate scan churn`() = runTest(dispatcher) {
        plant("workspace/s6/a.txt")
        val index = newIndex()
        index.refreshIfNeeded("s6")
        val snapshot = index.entries.value
        // 同会话 TTL 内再刷：缓存命中，entries 保持不变。
        index.refreshIfNeeded("s6")
        assertEquals(snapshot, index.entries.value)
    }

    @Test
    fun `session change triggers rescan of new session roots`() = runTest(dispatcher) {
        plant("workspace/s7/only-in-s7.txt")
        val index = newIndex()
        index.refreshIfNeeded("s6")
        // 换会话：即使 TTL 未过期也必须重扫（缓存按会话判）。
        index.refreshIfNeeded("s7")
        assertTrue(
            index.entries.value.any { it.linuxPath == "/var/minis/workspace/s7/only-in-s7.txt" },
        )
    }
}
