package com.openminis.app.novex.domain

import org.junit.Assert.*
import org.junit.Test

class NovexSourceReadCoverageTest {
    @Test
    fun `only contiguous actual reads of one revision establish complete coverage`() {
        val first = NovexSourceRead("rules", "帝议", "revision-one", 0, 40, 100)
        val observations = listOf(first, first.copy(start = 30, end = 60), first.copy(start = 80, end = 100),
            first.copy(start = 0, end = 100, method = NovexSourceReadMethod.SEARCH),
            first.copy(start = 0, end = 100, method = NovexSourceReadMethod.PREVIEW),
            first.copy(start = 60, end = 80, revision = "revision-two"))
        val incomplete = NovexSourceReadCoverage.from(observations).first { it.revision == "revision-one" }
        assertEquals(80, incomplete.coveredCharacters)
        assertFalse(incomplete.complete)
        val complete = NovexSourceReadCoverage.from(observations + first.copy(start = 60, end = 80))
            .first { it.revision == "revision-one" }
        assertTrue(complete.complete)
        assertEquals(100, complete.coveredCharacters)
    }
}
