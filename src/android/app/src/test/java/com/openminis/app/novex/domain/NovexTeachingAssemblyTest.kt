package com.openminis.app.novex.domain

import android.app.Application
import com.openminis.app.agent.NovexSystemPrompt
import com.openminis.app.agent.NovexPreviousSystemPromptControl
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexTeachingAssemblyTest {
    @get:Rule val files = TemporaryFolder()
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private fun source() = context.assets.open("novex/teaching/v6-candidate.md").bufferedReader().use { it.readText() }
    @Test fun `formal repair preserves custom conversation instructions and excludes v6`() {
        val tools = setOf("novex_inspect_content", "novex_propose_content_changes", "novex_apply_content_changes", "save_checkpoint", "render_panel")
        for(enabled in listOf(true, false)) for(memory in listOf(true, false)) {
            val old = NovexPreviousSystemPromptControl.build("audit", context, "保留用户原有风格", memory, enabled, tools)
            val current = NovexSystemPrompt.buildPrepared("audit", context, "保留用户原有风格", memory, enabled, tools)
            assertTrue(old.contains("保留用户原有风格"))
            assertTrue(current.prompt.contains("保留用户原有风格"))
            assertTrue(current.prompt.contains("普通交流、保存资料、编辑卡片不会因此进入游玩"))
            if (enabled) assertTrue(current.prompt.contains("创作或修改请求完成后自然结束"))
            else assertTrue(current.prompt.contains("当前对话未启用工具"))
            assertFalse(current.prompt.contains("候选第六版"))
        }
    }
    @Test fun `candidate assembles six sections and selects one identity without replacing formal text`() {
        val role = NovexTeachingCandidate.build(source(), AnswerIdentity.CharacterVersion("role"), setOf("save_checkpoint"),
            "用户风格原话", "图片风格原话", "当前角色公开身份与已采用规则")
        assertEquals(listOf("common", "identity", "methods", "tools", "style", "runtime"), role.sections.map { it.key })
        assertEquals("R", role.identityKey)
        val identity = role.sections.single { it.key == "identity" }.text
        assertTrue(identity.startsWith("### R.")); assertFalse(identity.contains("### G.")); assertFalse(identity.contains("### N."))
        assertTrue(role.sections.single { it.key == "methods" }.text.contains("T05."))
        val teaching = role.sections.single { it.key == "tools" }.text
        assertTrue(teaching.contains("save_checkpoint")); assertFalse(teaching.contains("browser_use"))
        assertTrue(role.prompt.contains("用户风格原话")); assertTrue(role.prompt.contains("图片风格原话"))
        assertFalse(role.prompt.contains("{{本对话")); assertFalse(role.prompt.contains("## 阅读与装配说明"))
        assertFalse(role.toJson().getBoolean("sent")); assertFalse(role.toJson().getBoolean("accepted"))
        assertEquals("N", NovexTeachingCandidate.build(source(), AnswerIdentity.Nova, emptySet(), "", "", "").identityKey)
        assertEquals("G", NovexTeachingCandidate.build(source(), NovexPersonaPresets.gameHost, emptySet(), "", "", "").identityKey)
        assertEquals("X", NovexTeachingCandidate.build(source(), AnswerIdentity.PersonaPreset("custom", "自定义"), emptySet(), "", "", "").identityKey)
    }
    @Test fun `native trace persists complete text independently of model workspace and rejects replacement or tampering`() {
        val root = File(files.root, "native-traces")
        val formal = "正式长文\n".repeat(20_000)
        val record = JSONObject().put("conversationId", "chat").put("recordId", "request")
            .put("formalPrompt", formal).put("formalRevision", NovexFrozenContextCodec.digest(formal))
            .put("candidate", NovexTeachingCandidate.build(source(), AnswerIdentity.Nova, emptySet(), "", "", "").toJson())
        val store = FileNovexTeachingTraceStore(root)
        val ref = store.save("chat", "request", record)
        assertEquals(formal, FileNovexTeachingTraceStore(root).read(ref).getString("formalPrompt"))
        assertEquals(ref, store.save("chat", "request", record))
        assertThrows(IllegalArgumentException::class.java) { store.save("chat", "request", JSONObject(record.toString()).put("formalPrompt", "覆盖")) }
        assertThrows(IllegalArgumentException::class.java) { store.read("../../outside.json") }
        File(root, ref).writeText(JSONObject(record.toString()).put("formalPrompt", "已篡改").toString())
        assertThrows(IllegalArgumentException::class.java) { store.read(ref) }
        val workspace = FileNovexConversationWorkspaceStore(File(files.root, "workspace"))
        assertTrue(workspace.inspect(NovexConversationWorkspaceScope("chat", listOf("request"), "request")).entries.isEmpty())
    }
    @Test fun `context usage retains native trace reference through persistence`() {
        val record = ContextUsageRecord("usage", "request", branchId = "request", answerIdentity = AnswerIdentity.Nova,
            includedSources = emptyList(), usedTokens = 0, effectiveWindowTokens = 4096, teachingTraceRef = "native-reference")
        assertEquals(record, NovexContextUsageCodec.decode(NovexContextUsageCodec.encode(record)))
    }
}
