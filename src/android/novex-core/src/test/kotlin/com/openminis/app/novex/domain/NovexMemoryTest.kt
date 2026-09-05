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
    fun memoryChangesStayInertUntilExactConfirmationAndFollowTheSourceBranch() {
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
        assertThrows(IllegalArgumentException::class.java) {
            service.apply(plan, "同意")
        }
        val applied = service.apply(plan, "确认执行 plan-123")
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
        val entry = service.apply(first, first.confirmationPhrase).entries.single()
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

        service.apply(update, update.confirmationPhrase)
        assertThrows(IllegalArgumentException::class.java) {
            service.apply(competing, competing.confirmationPhrase)
        }
        assertEquals("偏好简洁且直接的回答", service.inspect(scope, context).entries.single().content)
    }
}
