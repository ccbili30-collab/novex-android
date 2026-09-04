package com.openminis.app.ui.novex

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexContentPageContractTest {
    @Test
    fun worldCharacterAndInteractiveFictionShareTheSamePageBehaviors() {
        val expected = NovexContentPageCapabilities(
            display = true,
            edit = true,
            preview = true,
            optionalArtwork = true,
            orderedModules = true,
        )

        assertEquals(expected, novexContentPageContract(NovexContentPageKind.WORLD).capabilities)
        assertEquals(expected, novexContentPageContract(NovexContentPageKind.CHARACTER).capabilities)
        assertEquals(expected, novexContentPageContract(NovexContentPageKind.INTERACTIVE_FICTION).capabilities)
    }

    @Test
    fun eachContentKindOnlySuppliesItsOwnVocabularyToTheSharedSkeleton() {
        assertEquals("世界", novexContentPageContract(NovexContentPageKind.WORLD).singularLabel)
        assertEquals("角色", novexContentPageContract(NovexContentPageKind.CHARACTER).singularLabel)
        assertEquals("文游", novexContentPageContract(NovexContentPageKind.INTERACTIVE_FICTION).singularLabel)
    }
}
