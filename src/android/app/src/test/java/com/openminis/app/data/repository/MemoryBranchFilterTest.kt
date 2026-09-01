package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBranchFilterTest {
    @Test
    fun `inactive entry is hidden without rewriting the memory file content`() {
        val source = """
            <!-- 2026-09-02 10:00:00 -->
            active memory

            <!-- 2026-09-02 09:00:00 -->
            inactive memory

        """.trimIndent() + "\n"

        val filtered = MemoryRepository.filterBranchEntries(source, mapOf("inactive memory" to 1))

        assertTrue(filtered.contains("active memory"))
        assertFalse(filtered.contains("inactive memory"))
        assertEquals(2, source.count { it == '<' })
    }

    @Test
    fun `duplicate active body remains visible when it is not excluded`() {
        val source = "<!-- 2026-09-02 10:00:00 -->\nshared memory\n\n"

        assertEquals(source, MemoryRepository.filterBranchEntries(source, emptyMap()))
    }

    @Test
    fun `filter preserves file prefix and only removes the recorded duplicate count`() {
        val source = "# imported notes\n\n" +
            "<!-- 2026-09-02 10:00:00 -->\nsame memory\n\n" +
            "<!-- 2026-09-02 11:00:00 -->\nsame memory\n\n"

        val filtered = MemoryRepository.filterBranchEntries(source, mapOf("same memory" to 1))

        assertTrue(filtered.startsWith("# imported notes\n\n"))
        assertEquals(1, "same memory".toRegex().findAll(filtered).count())
    }
}
