package com.openminis.app.novex.domain

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class NovexMemoryExecutionTest {
    @get:Rule val files = TemporaryFolder()
    private val scope = NovexMemoryScope.role("world", "player", "role")
    private val source = NovexMemoryReadContext("chat", listOf("user", "reply"))
    private fun service() = NovexMemoryService(FileNovexMemoryStore(File(files.root, "memory")), { "entry" }, { 10L })
    private fun executor() = NovexMemoryToolExecutor(service(), File(files.root, "plans"))

    @Test fun `persisted plan survives restart and rejects other conversation branch or identity`() {
        val proposal = executor().propose(scope, source, "reply", "user",
            """{"changes":[{"operation":"add","content":"答应守住山门"}]}""")
        assertTrue(proposal.output, proposal.success)
        assertTrue(service().inspect(scope, source).entries.isEmpty())
        val id = JSONObject(proposal.output).getJSONObject("data").getString("proposal_id")
        val args = JSONObject().put("proposal_id", id).toString()
        assertFalse(executor().apply(scope, source.copy(conversationId = "other"), args).success)
        assertFalse(executor().apply(scope, source.copy(activeBranchIds = listOf("user", "other-reply")), args).success)
        assertFalse(executor().apply(NovexMemoryScope.nova(), source, args).success)
        assertTrue(executor().review(scope, source, id).contains("答应守住山门"))
        val exported = NovexMemoryToolExecutor.exportPlans(File(files.root, "plans"), source.conversationId)
        assertEquals(1, exported.size)
        assertTrue(exported.values.single().contains("答应守住山门"))
        assertTrue(NovexMemoryToolExecutor.exportPlans(File(files.root, "plans"), "other").isEmpty())
        assertTrue(executor().apply(scope, source, args).success)
        val retry = executor().apply(scope, source, args)
        assertTrue(retry.success)
        assertTrue(JSONObject(retry.output).getJSONObject("data").getBoolean("replayed"))
        assertEquals(0, JSONObject(retry.output).getJSONObject("data").getInt("changed_entries"))
        assertEquals(1, service().inspect(scope, source).entries.size)
    }

    @Test fun `memory writes use the same readonly and free execution gate`() = runBlocking {
        val executor = executor()
        val proposal = executor.propose(scope, source, "reply", "user", """{"changes":[{"operation":"add","content":"正文"}]}""")
        val id = JSONObject(proposal.output).getJSONObject("data").getString("proposal_id")
        val args = JSONObject().put("proposal_id", id).toString()
        val operation = NovexToolOperation("chat", "reply", "call", "novex_apply_memory_changes", args, "保存记忆", executor.review(scope, source, id))
        val runtime = NovexToolExecution(NovexOperationJournal(File(files.root, "operations")))
        assertFalse(runtime.execute(operation, { NovexExecutionMode.READ_ONLY }) { executor.apply(scope, source, args) }.success)
        assertTrue(service().inspect(scope, source).entries.isEmpty())
        assertTrue(runtime.execute(operation, { NovexExecutionMode.FREE }) { executor.apply(scope, source, args) }.success)
        assertEquals("正文", service().inspect(scope, source).entries.single().content)
    }
}
