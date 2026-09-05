package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexStandardToolDetailPresentationTest {
    @Test
    fun `document result is presented as named fields instead of raw json`() {
        val detail = buildNovexStandardToolDetailPresentation(
            toolName = "document_read",
            argumentsJson = """{"document_ref":"novex://documents/abc","query":"以太"}""",
            resultText = """{"type":"document.read","status":"ok","title":"世界资料","next_cursor":"page-2"}""",
        )

        assertNotNull(detail)
        assertEquals("读取文档", detail?.title)
        assertTrue(detail?.requestFields.orEmpty().any { it.label == "文档" && it.value.endsWith("/abc") })
        assertTrue(detail?.requestFields.orEmpty().any { it.label == "查询" && it.value == "以太" })
        assertTrue(detail?.resultFields.orEmpty().any { it.label == "状态" && it.value == "成功" })
        assertFalse(detail?.body.orEmpty().startsWith("{"))
    }

    @Test
    fun `confirmed content proposal exposes receipt without exposing secrets`() {
        val detail = buildNovexStandardToolDetailPresentation(
            toolName = "novex_propose_content_changes",
            argumentsJson = """{"subject_kind":"world","subject_id":"world-1","changes":[{"operation":"add_module"}],"api_key":"do-not-show"}""",
            resultText = """{"status":"confirmation_required","proposal_id":"proposal-42","confirmation_phrase":"确认执行 4242","summary":"新增一个模块"}""",
        )

        assertEquals("提出内容变更", detail?.title)
        assertTrue(detail?.requestFields.orEmpty().any { it.label == "对象类型" && it.value == "世界" })
        assertTrue(detail?.resultFields.orEmpty().any { it.label == "变更提案" && it.value == "proposal-42" })
        assertTrue(detail?.resultFields.orEmpty().any { it.label == "确认短语" && it.value == "确认执行 4242" })
        assertFalse(detail.toString().contains("do-not-show"))
    }

    @Test
    fun `workspace content gets a readable body while unknown tools retain old fallback`() {
        val known = buildNovexStandardToolDetailPresentation(
            toolName = "workspace_read",
            argumentsJson = """{"area":"notes","path":"整理.md"}""",
            resultText = "资料冲突记录",
        )

        assertEquals("读取工作区", known?.title)
        assertEquals("资料冲突记录", known?.body)
        assertNull(buildNovexStandardToolDetailPresentation("third_party_tool", "{}", "raw"))
    }
}
