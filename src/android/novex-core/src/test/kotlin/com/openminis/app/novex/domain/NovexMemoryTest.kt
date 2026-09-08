package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexMemoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun memoryProposalIsInertUntilAppliedAndFollowsTheSourceBranch() {
        val store = FileNovexMemoryStore(temporaryFolder.newFolder("memory"))
        val service = NovexMemoryService(store, entryIdFactory = { "memory-1" }, nowMillis = { 1_234L })
        val scope = NovexMemoryScope.role(
            worldId = "world-1",
            playerIdentityId = "player-1",
            characterVersionId = "version-1",
        )
        val source = NovexMemoryReadContext(
            conversationId = "chat-1",
            activeBranchIds = listOf("user-1", "reply-a"),
        )

        val plan = service.propose(
            scope = scope,
            changesJson = """[{"operation":"add","content":"苏晚晴答应守住山门","tags":["承诺","苏晚晴"]}]""",
            source = source,
            sourceBranchId = "reply-a",
            sourceMessageId = "user-1",
            planId = "plan-12345678",
        )

        assertTrue(service.inspect(scope, source).entries.isEmpty())
        val applied = service.apply(plan)
        assertEquals(1, applied.entries.size)
        assertTrue(applied.entries.single().ref.value.startsWith("novex://memories/"))

        assertEquals(1, service.inspect(scope, source).entries.size)
        assertTrue(
            service.inspect(
                scope,
                source.copy(activeBranchIds = listOf("user-1", "reply-b")),
            ).entries.isEmpty(),
        )
        assertEquals(
            1,
            service.inspect(
                scope,
                NovexMemoryReadContext("chat-2", listOf("user-2")),
            ).entries.size,
        )
    }

    @Test
    fun staleMemoryRevisionRejectsASecondWriterWithoutPartialChanges() {
        val store = FileNovexMemoryStore(temporaryFolder.newFolder("stale"))
        var nextId = 0
        val service = NovexMemoryService(store, entryIdFactory = { "memory-${++nextId}" }, nowMillis = { 7L })
        val scope = NovexMemoryScope.nova()
        val context = NovexMemoryReadContext("chat-1", listOf("reply-a"))
        val first = service.propose(
            scope,
            """[{"operation":"add","content":"偏好简洁回答"}]""",
            context,
            "reply-a",
            "user-1",
            "plan-add-0001",
        )
        val entry = service.apply(first).entries.single()
        val updateJson = """[{"operation":"replace","memory_ref":"${entry.ref.value}","expected_revision":"${entry.revision}","content":"偏好简洁且直接的回答"}]"""
        val update = service.propose(
            scope,
            updateJson,
            context,
            "reply-a",
            "user-2",
            "plan-update-1",
        )
        val competing = service.propose(
            scope,
            updateJson,
            context,
            "reply-a",
            "user-3",
            "plan-update-2",
        )

        service.apply(update)
        assertThrows(IllegalArgumentException::class.java) {
            service.apply(competing)
        }
        assertEquals("偏好简洁且直接的回答", service.inspect(scope, context).entries.single().content)
    }
    @Test fun persistedPlansReplayWithoutRepeatingAddReplaceOrRemove() {
        val directory = temporaryFolder.newFolder("replay")
        fun service() = NovexMemoryService(FileNovexMemoryStore(directory), { "entry" }, { 10L })
        val scope = NovexMemoryScope.nova()
        val source = NovexMemoryReadContext("chat", listOf("reply"))
        val add = service().propose(scope, """[{"operation":"add","content":"原记忆"}]""", source, "reply", "user", "add")
        assertEquals(add, NovexMemoryPlanCodec.decode(NovexMemoryPlanCodec.encode(add)))
        val entry = service().apply(add).entries.single()
        assertEquals(entry, service().apply(NovexMemoryPlanCodec.decode(NovexMemoryPlanCodec.encode(add))).entries.single())
        val replace = service().propose(scope, """[{"operation":"replace","memory_ref":"${entry.ref.value}","expected_revision":"${entry.revision}","content":"新记忆"}]""", source, "reply", "user", "replace")
        val changed = service().apply(replace).entries.single()
        assertEquals(changed, service().apply(NovexMemoryPlanCodec.decode(NovexMemoryPlanCodec.encode(replace))).entries.single())
        val remove = service().propose(scope, """[{"operation":"remove","memory_ref":"${changed.ref.value}","expected_revision":"${changed.revision}"}]""", source, "reply", "user", "remove")
        service().apply(remove)
        assertTrue(service().apply(NovexMemoryPlanCodec.decode(NovexMemoryPlanCodec.encode(remove))).entries.isEmpty())
        service().apply(add) // An old add retry must not resurrect a later deletion.
        assertTrue(service().inspect(scope, source).entries.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { service().apply(add.copy(summary = "同编号已改变")) }
    }

    @Test fun failedBatchDoesNotLeavePartialMemoryOrACompletionReceipt() {
        val store = FileNovexMemoryStore(temporaryFolder.newFolder("rollback"))
        val service = NovexMemoryService(store, { "entry" }, { 1L })
        val scope = NovexMemoryScope.nova()
        val source = NovexMemoryReadContext("chat", listOf("reply"))
        val add = service.propose(scope, """[{"operation":"add","content":"正文"}]""", source, "reply", null, "plan")
        val broken = add.copy(changes = add.changes + NovexMemoryChange.Remove(NovexMemoryRef.create(scope, "missing"), "0".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { service.apply(broken) }
        assertTrue(service.inspect(scope, source).entries.isEmpty())
        assertEquals(1, service.apply(add).entries.size)
    }

}
