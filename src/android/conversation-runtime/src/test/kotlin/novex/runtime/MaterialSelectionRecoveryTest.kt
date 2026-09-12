package novex.runtime

import org.junit.Assert.*
import org.junit.Test

class MaterialSelectionRecoveryTest {
    private val allowed=setOf("a","b")
    @Test fun malformedHelperOutputRecoversCandidatesRatherThanClaimingNothingRelevant() {
        listOf(null,"", "The selected modules are a", "{\"modules\":[\"a", "{\"modules\":[\"outside\"]}", "{}").forEach { answer ->
            val result=MaterialSelectionRecovery.resolve(answer,allowed)
            assertTrue(result.recovered)
            assertEquals(allowed,result.ids)
        }
    }
    @Test fun intentionalEmptySelectionRemainsEmpty() {
        assertEquals(RecoveredSelection(emptySet(),false),MaterialSelectionRecovery.resolve("{\"modules\":[]}",allowed))
    }
    @Test fun validSelectionIncludingFencedJsonIsPreserved() {
        assertEquals(RecoveredSelection(setOf("b"),false),MaterialSelectionRecovery.resolve("```json\n{\"modules\":[\"b\"]}\n```",allowed))
    }
}
