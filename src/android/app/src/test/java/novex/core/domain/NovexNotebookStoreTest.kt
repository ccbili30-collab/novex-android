package novex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * [T-stage2-memory] 随身笔记本存储守护：往返保真、损坏文件静默重置为空、
 * 常驻注入块（空零痕迹/条目全列出）、整理输出解析（合法/非法静默/去重）。
 */
class NovexNotebookStoreTest {

    @get:Rule val temporary = TemporaryFolder()

    private fun store() = NovexNotebookStore(temporary.newFile())

    private fun entry(id: String, text: String) = NovexNotebookStore.MemoryEntry(
        id = id, kind = "fact", text = text, createdAt = 1L, updatedAt = 2L)

    @Test fun `round trip preserves entries and tick`() {
        val store = store()
        val memory = NovexNotebookStore.SessionMemory(
            entries = listOf(entry("m1", "费千户好感 83"), entry("m2", "西苑夜闻哭声")),
            highWaterTickPercent = 20, updatedAt = 99L)
        store.save(memory)
        assertEquals(memory, store.load())
    }

    @Test fun `corrupt file silently resets to empty`() {
        val file = temporary.newFile()
        file.writeText("{not json")
        assertEquals(NovexNotebookStore.SessionMemory(), NovexNotebookStore(file).load())
    }

    @Test fun `injection block is null when empty and lists entries when present`() {
        val store = store()
        assertNull(store.injectionBlock(NovexNotebookStore.SessionMemory()))
        val block = store.injectionBlock(NovexNotebookStore.SessionMemory(entries = listOf(entry("m1", "线索：蓝道行"))))
        assertNotNull(block)
        assertEquals(true, block!!.startsWith("<AI 随身笔记>"))
        assertEquals(true, block.contains("- [fact] 线索：蓝道行"))
    }

    @Test fun `consolidation parse accepts rejects and dedupes`() {
        val now = 5L
        val parsed = NovexNotebookStore.parseConsolidation(
            """{"entries":[{"id":"a","kind":"event","text":"严嵩倒台"},{"id":"a","kind":"event","text":"重复 id 丢弃"},{"text":"缺 id 自动编号"}]}""", now)
        assertEquals(2, parsed!!.entries.size)
        assertEquals("严嵩倒台", parsed.entries.first().text)
        // 非法输出静默 null
        assertNull(NovexNotebookStore.parseConsolidation("我觉得应该记点什么", now))
        assertNull(NovexNotebookStore.parseConsolidation("{\"entries\":\"不是数组\"}", now))
    }

    /**
     * [T-notebook-prompt-scope] 提示词契约（2026-09-27 codex 研究·模式
     * 1/2 落地）：锁住窄身份+输出纪律前置、值得记/不值得记正反对照、
     * 淘汰判据（不留新旧两版）。重写措辞弄丢任一句，本测即红。
     */
    @Test fun `consolidation prompt carries scope contract`() {
        val text = NovexNotebookStore.consolidationPrompt(NovexNotebookStore.SessionMemory(), "近期", null)
        org.junit.Assert.assertTrue(text.contains("无任何其他文字"))
        org.junit.Assert.assertTrue(text.contains("值得记"))
        org.junit.Assert.assertTrue(text.contains("不值得记"))
        org.junit.Assert.assertTrue(text.contains("不要同时保留新旧两个版本"))
    }
}
