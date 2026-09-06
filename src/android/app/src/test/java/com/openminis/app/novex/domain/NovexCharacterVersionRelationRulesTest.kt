package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionKind
import org.junit.Assert.*
import org.junit.Test

class NovexCharacterVersionRelationRulesTest {
    @Test
    fun `the default can be an adult but a life stage chain cannot loop`() {
        val young = version("young")
        val adult = version("adult", CharacterVersionKind.ORIGINAL)
        val old = version("old")
        val parallel = version("parallel")
        val versions = listOf(young, adult, old, parallel)
        val earlier = NovexCharacterVersionRelation("early", adult.id, young.id, NovexCharacterVersionRelationKind.EARLIER_STAGE)
        val later = NovexCharacterVersionRelation("late", adult.id, old.id, NovexCharacterVersionRelationKind.LATER_STAGE)
        NovexCharacterVersionRelationRules.validate(earlier, versions, emptyList())
        NovexCharacterVersionRelationRules.validate(later, versions, listOf(earlier))
        NovexCharacterVersionRelationRules.validate(NovexCharacterVersionRelation("parallel", adult.id, parallel.id,
            NovexCharacterVersionRelationKind.PARALLEL), versions, listOf(earlier, later))
        assertEquals(CharacterVersionKind.ORIGINAL, adult.kind)
        assertThrows(IllegalArgumentException::class.java) {
            NovexCharacterVersionRelationRules.validate(NovexCharacterVersionRelation("cycle", old.id, young.id,
                NovexCharacterVersionRelationKind.LATER_STAGE), versions, listOf(earlier, later))
        }
    }

    private fun version(id: String, kind: CharacterVersionKind = CharacterVersionKind.VARIANT) =
        CharacterVersionEntity(id, "person", kind, id, createdAt = 0, updatedAt = 0)
}
