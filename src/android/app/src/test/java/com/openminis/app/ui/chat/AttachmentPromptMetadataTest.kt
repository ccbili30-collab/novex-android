package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPromptMetadataTest {
    @Test
    fun `small extracted document text is delivered inline`() {
        val xml = buildUserAttachedFilesPrompt(
            listOf(
                UserAttachedFilePromptMeta(
                    linuxPath = "/var/minis/attachments/uploads/world&rules.docx",
                    size = 13_000,
                    modifiedIso = "2026-08-31T00:00:00Z",
                    extractedTextPath = "/var/minis/attachments/uploads/world.md",
                    extractedFormat = "DOCX",
                    extractedText = "世界规则：A < B，并保留 ]]> 边界。",
                ),
            ),
        )

        assertNotNull(xml)
        assertTrue(xml!!.contains("world&amp;rules.docx"))
        assertTrue(xml.contains("extracted_text_path=\"/var/minis/attachments/uploads/world.md\""))
        assertTrue(xml.contains("世界规则：A < B"))
        assertTrue(xml.contains("]]]]><![CDATA[>"))
        assertFalse(xml.contains("extracted_text_truncated=\"true\""))
    }

    @Test
    fun `large extracted text keeps paging path and marks inline excerpt`() {
        val xml = buildUserAttachedFilesPrompt(
            listOf(
                UserAttachedFilePromptMeta(
                    linuxPath = "/var/minis/attachments/uploads/large.docx",
                    size = 500_000,
                    modifiedIso = "2026-08-31T00:00:00Z",
                    extractedTextPath = "/var/minis/attachments/uploads/large.md",
                    extractedFormat = "DOCX",
                    extractedText = "字".repeat(60_000),
                ),
            ),
        )!!

        assertTrue(xml.contains("extracted_text_truncated=\"true\""))
        assertTrue(xml.contains("剩余内容请按 extracted_text_path 分页读取"))
        assertTrue(xml.length < 55_000)
    }
}
