package com.openminis.app.novex.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NovexReferenceTraversalTest {
    @Test
    fun `background traversal deduplicates cycles and never reads identity-only targets`() = runBlocking {
        val a = NovexReferenceTarget(NovexContentAddress.world("a"))
        val b = NovexReferenceTarget(NovexContentAddress.world("b"))
        val privateRole = NovexReferenceTarget(NovexContentAddress.characterVersion("private-role"))
        val links = mapOf(
            a to listOf(NovexCardReference("ab", a.subject, b, NovexReferencePurpose.BACKGROUND),
                NovexCardReference("ab-again", a.subject, b, NovexReferencePurpose.RULES)),
            b to listOf(NovexCardReference("ba", b.subject, a, NovexReferencePurpose.BACKGROUND),
                NovexCardReference("identity", b.subject, privateRole, NovexReferencePurpose.ANSWER_IDENTITY)),
        )
        val reads = mutableListOf<NovexReferenceTarget>()
        val result = NovexReferenceTraversal.collect(a,
            setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES),
            read = { target -> reads += target; links[target] })
        assertEquals(setOf(a, b), result.targets.toSet())
        assertEquals(listOf(a, b), reads)
        assertEquals(setOf("ba"), result.cycleReferenceIds)
        assertFalse(result.truncated)
        assertTrue(result.missingTargets.isEmpty())
    }
}
