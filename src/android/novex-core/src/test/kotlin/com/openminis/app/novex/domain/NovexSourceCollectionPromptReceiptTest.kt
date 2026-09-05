package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexSourceCollectionPromptReceiptTest {
    private val collectionRef = NovexResourceRef(
        "novex://source-collections/${"b".repeat(64)}",
    )

    @Test
    fun `receipt carries only stable collection reference and source count`() {
        val receipt = NovexSourceCollectionPromptReceipt.build(collectionRef, sourceCount = 18)

        assertEquals(
            "<novex-source-collection ref=\"${collectionRef.value}\" sources=\"18\" />",
            receipt,
        )
        assertFalse(receipt.contains("/var/minis"))
    }

    @Test
    fun `recovery accepts only validated Novex collection references`() {
        val prompt = """
            ${NovexSourceCollectionPromptReceipt.build(collectionRef, 3)}
            <novex-source-collection ref="novex://source-collections/not-a-hash" sources="2" />
            <novex-source-collection ref="file:///private/data" sources="1" />
        """.trimIndent()

        assertEquals(setOf(collectionRef), NovexSourceCollectionPromptReceipt.refsIn(prompt))
    }

    @Test
    fun `receipt can be hidden from user facing content without removing prose`() {
        val receipt = NovexSourceCollectionPromptReceipt.build(collectionRef, 1)

        assertTrue(NovexSourceCollectionPromptReceipt.contains(receipt))
        assertEquals("正文前\n\n正文后", NovexSourceCollectionPromptReceipt.stripFrom("正文前\n$receipt\n正文后"))
        assertEquals("", NovexSourceCollectionPromptReceipt.stripFrom(receipt))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty collection cannot produce a receipt`() {
        NovexSourceCollectionPromptReceipt.build(collectionRef, sourceCount = 0)
    }
}
