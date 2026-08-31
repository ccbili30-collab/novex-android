package com.openminis.app.data.character

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernCardParserTest {
    private val v2Json = """
        {
          "spec": "chara_card_v2",
          "spec_version": "2.0",
          "data": {
            "name": "艾琳",
            "description": "夜港向导\n熟悉每一条暗巷。",
            "personality": "克制、敏锐",
            "scenario": "雨夜酒馆",
            "first_mes": "你终于来了。",
            "mes_example": "{{user}}：安全吗？\n{{char}}：从来没有。",
            "system_prompt": "保持角色身份。",
            "post_history_instructions": "回答简洁。",
            "alternate_greetings": ["今晚很安静。"],
            "tags": ["奇幻", "向导"],
            "character_book": {
              "entries": [
                {"name":"夜港","keys":["港口"],"content":"潮汐钟每天响三次。","enabled":true},
                {"name":"禁用","content":"不应导入","enabled":false}
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `v2 json maps role fields and keeps character book separate`() {
        val preview = SillyTavernCardParser.parseJson(v2Json)

        assertEquals("艾琳", preview.card.name)
        assertEquals("克制、敏锐", preview.card.personality)
        assertEquals("你终于来了。", preview.card.greeting)
        assertEquals(listOf("今晚很安静。"), preview.card.alternateGreetings)
        assertEquals(1, preview.knowledgeEntryCount)
        assertTrue(preview.card.knowledge.contains("潮汐钟每天响三次"))
        assertTrue(!preview.card.knowledge.contains("不应导入"))
    }

    @Test
    fun `sillytavern png chara metadata is decoded and image stays avatar`() {
        val png = pngWithText("chara", Base64.getEncoder().encodeToString(v2Json.toByteArray()))

        val preview = SillyTavernCardParser.parse(png, mimeType = "image/png", fileName = "艾琳.png")

        assertEquals("艾琳", preview.card.name)
        assertTrue(preview.sourceLabel.startsWith("酒馆 PNG"))
        assertArrayEquals(png, preview.avatarPng)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ordinary png is rejected instead of becoming an empty role`() {
        SillyTavernCardParser.parse(pngWithText("note", "plain image"), mimeType = "image/png")
    }

    private fun pngWithText(keyword: String, text: String): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk(output, "IHDR", ByteArray(13))
        writeChunk(output, "tEXt", keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + text.toByteArray())
        writeChunk(output, "IEND", byteArrayOf())
        return output.toByteArray()
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        DataOutputStream(output).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            val crc = CRC32().apply { update(typeBytes); update(data) }
            writeInt(crc.value.toInt())
        }
    }
}
