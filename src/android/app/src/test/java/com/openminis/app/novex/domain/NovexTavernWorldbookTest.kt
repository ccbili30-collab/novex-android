package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexTavernWorldbookTest {
    private fun entry(name: String, key: String = "港口", constant: Boolean = false, enabled: Boolean = true) = JSONObject()
        .put("name", name).put("content", "$name 的正文").put("enabled", enabled).put("constant", constant)
        .put("keys", JSONArray().put(key)).put("extensions", JSONObject())
    private fun snapshot(entries: List<JSONObject>, depth: Int = 2, budget: Int = 2048) = JSONObject()
        .put("runtimeVersion", 1).put("defaultScanDepth", depth).put("defaultCaseSensitive", false)
        .put("defaultPosition", "after_char").put("localTokenBudget", 2048)
        .put("book", JSONObject().put("entries", JSONArray(entries)).put("token_budget", budget)).toString()
    @Test fun `disabled constants and unsupported conditions never become background text`() {
        val rows = listOf(entry("停用", constant = true, enabled = false),
            entry("分组", constant = true).put("extensions", JSONObject().put("group", "互斥组")),
            entry("冷却", constant = true).put("extensions", JSONObject().put("cooldown", 3)),
            entry("未知", constant = true).put("extensions", JSONObject().put("futureCondition", true)),
            entry("宏", constant = true).put("content", "{{user}} 的身份"),
            entry("正则", constant = true).put("use_regex", true),
            entry("次关键词", constant = true).put("selective", true),
            entry("缺少启用").apply { remove("enabled") }, entry("无词").put("keys", JSONArray()),
            entry("允许", constant = true))
        val result = NovexTavernWorldbook.evaluate("actor", snapshot(rows), listOf("港口"), 1000, String::length)
        assertEquals(listOf("允许 的正文"), result.fragments.map { it.text })
        assertEquals(9, result.omissions.size)
        assertTrue(result.omissions.first().reason.contains("关闭"))
    }
    @Test fun `scan depth counts individual visible messages including prior assistant text`() {
        val messages = listOf("港口见闻", "继续")
        assertTrue(NovexTavernWorldbook.evaluate("a", snapshot(listOf(entry("浅")), 1), messages, 100, String::length).fragments.isEmpty())
        assertEquals(1, NovexTavernWorldbook.evaluate("a", snapshot(listOf(entry("深")), 2), messages, 100, String::length).fragments.size)
        assertTrue(NovexTavernWorldbook.evaluate("a", snapshot(listOf(entry("零")), 0), messages, 100, String::length).fragments.isEmpty())
        assertEquals(1, NovexTavernWorldbook.evaluate("a", snapshot(listOf(entry("常驻", constant = true)), 0), messages, 100, String::length).fragments.size)
    }
    @Test fun `case depth types insertion conflicts and full entry budget are explicit`() {
        val sensitive = entry("区分", "Port").put("case_sensitive", true)
        val insensitive = entry("忽略", "Port")
        val badDepth = entry("深度错误", constant = true).put("extensions", JSONObject().put("scan_depth", "2"))
        val conflict = entry("位置冲突", constant = true).put("position", "before_char").put("extensions", JSONObject().put("position", 1))
        val result = NovexTavernWorldbook.evaluate("a", snapshot(listOf(sensitive, insensitive, badDepth, conflict)), listOf("port"), 100, String::length)
        assertEquals(listOf("忽略 的正文"), result.fragments.map { it.text })
        assertEquals(3, result.omissions.size)
        assertTrue(NovexTavernWorldbook.evaluate("a", snapshot(listOf(entry("预算", constant = true)), budget = 2), emptyList(), 100, String::length).fragments.isEmpty())
    }
    @Test fun `triggered fragments bracket the identity in stable author order without granting identity kind`() {
        val after = entry("后方", constant = true).put("position", "after_char").put("insertion_order", 1)
        val before = entry("前方", constant = true).put("position", "before_char").put("insertion_order", 2)
        val result = NovexTavernWorldbook.evaluate("a", snapshot(listOf(after, before)), emptyList(), 100, String::length)
        assertTrue(result.fragments.all { it.kind == ContextSourceKind.BACKGROUND_MODULE && !it.partial })
        val text = NovexContextPromptFormatter.appendTo("共同规则", listOf(NovexContextFragment(ContextSourceKind.ANSWER_IDENTITY, "identity", "回答者", "角色定义正文", 6)) + result.fragments)
        assertTrue(text.indexOf("前方 的正文") < text.indexOf("角色定义正文"))
        assertTrue(text.indexOf("后方 的正文") > text.indexOf("角色定义正文"))
    }
}
