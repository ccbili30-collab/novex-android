package com.openminis.app.novex.domain

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.novex.adapter.NovexScopedConversationHistory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexScopedConversationHistoryTest {
    private val old = NovexConversationConfigurationSnapshot("chat", answerIdentity = AnswerIdentity.CharacterVersion("role"),
        managedSubjects = listOf(ManagedSubject(NovexContentAddress.world("world"), ManagedAccess.EDIT)))
    private val key = NovexHistoryAccessScope.key(old)
    private val secret = "未公开的私人约定"
    private fun text(value: String) = JSONArray().put(JSONObject().put("type", "text").put("value", value)).toString()
    private val rows get() = listOf(
        MessageEntity("user", "chat", "user", text("整理邮局"), 1, sortOrder = 0),
        MessageEntity("call", "chat", "assistant", JSONArray().put(JSONObject().put("type", "text").put("value", "工作推断$secret").put("execution", true))
            .put(JSONObject().put("type", "toolUse")).toString(), 2, sortOrder = 1),
        MessageEntity("result", "chat", "user", JSONArray().put(JSONObject().put("type", "toolResult")).toString(), 3, sortOrder = 2),
        MessageEntity("formal", "chat", "assistant", text("邮局已保存"), 4, sortOrder = 3),
    )
    private val history get() = listOf(
        LLMMessage(LLMMessage.Role.USER, "整理邮局", dbMessageId = "user"),
        LLMMessage(LLMMessage.Role.ASSISTANT, "工作推断$secret", contentParts = listOf(AgentContentPart.Text("工作推断$secret"),
            AgentContentPart.ToolUse("read", "novex_context_read", JSONObject().put("private", secret))), dbMessageId = "call", reasoningContent = secret),
        LLMMessage(LLMMessage.Role.USER, "", contentParts = listOf(AgentContentPart.ToolResult("read", "novex_context_read", secret)), dbMessageId = "result"),
        LLMMessage(LLMMessage.Role.ASSISTANT, "邮局已保存", dbMessageId = "formal", reasoningContent = secret),
    )
    private fun record(scope: String?) = ContextUsageRecord("record", "user", "call", "call", old.answerIdentity,
        emptyList(), usedTokens = 0, effectiveWindowTokens = 128000, historyScopeKey = scope)

    private fun paired(value: List<LLMMessage>): List<LLMMessage> = value.toMutableList().apply {
        this[1] = this[1].copy(contentParts = this[2].contentParts.filterIsInstance<AgentContentPart.ToolResult>().map {
            AgentContentPart.ToolUse(it.id, it.name, JSONObject().put("private", secret))
        })
    }
    private fun receiptText(message: LLMMessage) =
        message.contentParts.filterIsInstance<AgentContentPart.ToolResult>().joinToString("\n") { it.content }

    @Test fun sameScopeKeepsExactBalancedToolHistoryIncludingReasoning() {
        val original = history
        val result = NovexScopedConversationHistory.project(original, rows, listOf(record(key)), key)
        assertEquals(original, result.messages)
        assertTrue(result.restrictedMessageIds.isEmpty())
        assertEquals(key, NovexContextUsageCodec.decode(NovexContextUsageCodec.encode(record(key))).historyScopeKey)
    }

    @Test fun identityChangeKeepsFailedStartOutcomeWithoutReplayingPrivateConfiguration() {
        val configured = JSONObject().put("ok", true).put("code", "conversation.configured")
            .put("summary", secret).put("data", JSONObject().put("player_identity", secret)).toString()
        val tools = listOf(
            AgentContentPart.ToolResult("start", "start_interactive_fiction", "身份校验失败：$secret", isError = true),
            AgentContentPart.ToolResult("identity", "set_player_identity", configured),
        )
        val altered = history.toMutableList().apply { this[2] = this[2].copy(contentParts = tools) }
        val changed = NovexHistoryAccessScope.key(old.copy(playerIdentity = ConversationPlayerIdentity("player", "我", "新邮差")))
        val projected = NovexScopedConversationHistory.project(paired(altered), rows, listOf(record(key)), changed)
        val receipt = projected.messages[2]
        assertTrue(receiptText(receipt).contains("启动文游尝试未完成"))
        assertTrue(receiptText(receipt).contains("已完成一次保存玩家身份"))
        assertFalse(projected.messages.toString().contains(secret))
        assertEquals(LLMMessage.Role.USER, receipt.role)
        assertTrue(receipt.contentParts.all { it is AgentContentPart.ToolResult })
        assertEquals(tools, altered[2].contentParts)
    }

    @Test fun projectedReceiptsKeepPairingErrorsAndDropUnverifiableOrPrivatePayloads() {
        val output = JSONObject().put("ok", true).put("code", "conversation.configured").put("private", secret)
        val source = paired(history.toMutableList().apply { this[2] = this[2].copy(contentParts = listOf(
            AgentContentPart.ToolResult("identity", "set_player_identity", output.toString(),
                imageData = secret.toByteArray(), imageMimeType = "image/png", imageLinuxPath = secret))) })
        val changed = NovexHistoryAccessScope.key(old.copy(answerIdentity = AnswerIdentity.Nova))
        val projected = NovexScopedConversationHistory.project(source, rows, listOf(record(key)), changed).messages
        val call = projected[1].contentParts.single() as AgentContentPart.ToolUse
        val result = projected[2].contentParts.single() as AgentContentPart.ToolResult
        assertEquals(call.id, result.id)
        assertEquals(call.name, result.name)
        assertTrue(call.input.getBoolean("_history_arguments_omitted"))
        assertEquals("", projected[2].content)
        assertNull(result.imageData)
        assertNull(result.imageLinuxPath)
        assertFalse(projected.toString().contains(secret))
        assertTrue(source.toString().contains(secret))
        for (unverifiable in listOf(source.filterIndexed { index, _ -> index != 1 }, source.toMutableList().apply {
            this[1] = this[1].copy(contentParts = listOf(AgentContentPart.ToolUse("identity", "another_tool", JSONObject())))
        })) {
            val clean = NovexScopedConversationHistory.project(unverifiable, rows, listOf(record(key)), changed).messages
            assertTrue(clean.none { it.contentParts.any { part -> part is AgentContentPart.ToolResult } })
        }
    }

    @Test fun changedIdentityOrRevokedManagementDropsPrivateWorkButKeepsPublicTaskAndReply() {
        for (configuration in listOf(old.copy(answerIdentity = AnswerIdentity.Nova), old.copy(managedSubjects = emptyList()))) {
            val original = history
            val result = NovexScopedConversationHistory.project(original, rows, listOf(record(key)), NovexHistoryAccessScope.key(configuration))
            assertFalse(result.messages.toString().contains(secret))
            assertTrue(result.messages.any { it.content == "整理邮局" })
            assertTrue(result.messages.any { it.content == "邮局已保存" })
            assertEquals(setOf("call", "result", "formal"), result.restrictedMessageIds)
            assertTrue("原件不修改", original.toString().contains(secret))
        }
    }

    @Test fun legacyOrAnotherBranchRecordCannotAuthorizePrivateReplay() {
        for (records in listOf(listOf(record(null)), listOf(record(key).copy(responseMessageId = "other-branch")))) {
            val result = NovexScopedConversationHistory.project(history, rows, records, key)
            assertFalse(result.messages.toString().contains(secret))
        }
    }

    @Test fun ordinaryStateUpdatesDoNotInvalidateReadingWhilePlayerAndAccessChangesDo() {
        assertEquals(key, NovexHistoryAccessScope.key(old.copy(playthroughStates = mapOf("branch" to PlaythroughState("branch", mapOf("hp" to PlaythroughValue.Number(3.0)))))))
        assertNotEquals(key, NovexHistoryAccessScope.key(old.copy(playerIdentity = ConversationPlayerIdentity("player", "记言人", "私有经历"))))
        assertEquals(key, NovexHistoryAccessScope.key(old.copy(executionMode = NovexExecutionMode.READ_ONLY)))
        val expanded = NovexHistoryAccessScope.key(old.copy(managedSubjects = old.managedSubjects +
            ManagedSubject(NovexContentAddress.world("another-world"), ManagedAccess.READ_ONLY)))
        assertTrue(NovexHistoryAccessScope.canReplay(key, expanded))
        assertFalse(NovexHistoryAccessScope.canReplay(expanded, key))
        val original = history
        assertEquals(original, NovexScopedConversationHistory.project(original, rows, listOf(record(key)), expanded).messages)
    }
    @Test fun changingPlayerKeepsOnlyPermittedSavedCardReferencesSoWorkDoesNotRestart() {
        val receipt = JSONObject().put("saved", true).put("status", "saved_verified")
            .put("message", secret).put("body", secret)
            .put("created_cards", JSONArray().put(JSONObject().put("kind", "world").put("id", "world")
                .put("name", "邮局").put("private_body", secret)))
        val saved = history.toMutableList().apply { this[2] = this[2].copy(contentParts = listOf(
            AgentContentPart.ToolResult("write", "novex_write_card", receipt.toString()))) }
        val afterPlayer = old.copy(playerIdentity = ConversationPlayerIdentity("self", "我的身份", "新邮差"))
        val result = NovexScopedConversationHistory.project(paired(saved), rows, listOf(record(key)), NovexHistoryAccessScope.key(afterPlayer))
        val receiptMessage = result.messages[2]
        assertTrue(receiptText(receiptMessage).contains("world"))
        assertTrue(receiptText(receiptMessage).contains("邮局"))
        assertTrue(receiptText(receiptMessage).contains("此前已保存"))
        assertEquals(LLMMessage.Role.USER, receiptMessage.role)
        assertFalse(result.messages.toString().contains(secret))
        assertTrue(receiptMessage.contentParts.all { it is AgentContentPart.ToolResult })
        val revoked = NovexScopedConversationHistory.project(saved, rows, listOf(record(key)),
            NovexHistoryAccessScope.key(afterPlayer.copy(managedSubjects = emptyList())))
        assertEquals("", revoked.messages[2].content)
        assertEquals(receipt.toString(), (saved[2].contentParts.single() as AgentContentPart.ToolResult).content)
    }

    @Test fun failedOrUnrecognizedToolResultsCannotBecomeSavedWorkReceipts() {
        for (name in listOf("novex_write_card", "novex_read_context")) {
            val payload = JSONObject().put("saved", false).put("status", "saved_needs_review")
                .put("created_cards", JSONArray().put(JSONObject().put("kind", "world").put("id", "world").put("name", secret)))
            val altered = history.toMutableList().apply { this[2] = this[2].copy(contentParts = listOf(
                AgentContentPart.ToolResult("write", name, payload.toString()))) }
            val result = NovexScopedConversationHistory.project(altered, rows, listOf(record(key)),
                NovexHistoryAccessScope.key(old.copy(answerIdentity = AnswerIdentity.Nova)))
            assertFalse(result.messages.toString().contains(secret))
            assertEquals("", result.messages[2].content)
        }
    }

    @Test fun nativeModuleAndLegacyProposalReceiptsKeepCompletedWorkWithoutSourceBodies() {
        val scope = NovexHistoryAccessScope.key(old.copy(answerIdentity = AnswerIdentity.Nova))
        val variants = listOf(
            "novex_apply_content_changes" to JSONObject().put("proposal_id", "plan")
                .put("applied_changes", 1).put("readback_status", secret)
                .put("created_subjects", JSONArray().put(JSONObject().put("kind", "world").put("id", "world"))),
            "novex_move_module" to JSONObject().put("saved", true).put("status", "saved_verified")
                .put("saved_modules", JSONArray().put(JSONObject().put("module_id", "module-one")
                    .put("card_id", "world").put("name", secret).put("body", secret))),
            "novex_write_module" to JSONObject().put("saved", true).put("status", "saved_needs_review")
                .put("saved_modules", JSONArray().put(JSONObject().put("module_id", "module-one")
                    .put("card_id", "world").put("name", secret))),
        )
        variants.forEach { (name, payload) ->
            val altered = history.toMutableList().apply { this[2] = this[2].copy(contentParts = listOf(
                AgentContentPart.ToolResult("write", name, payload.toString()))) }
            val result = NovexScopedConversationHistory.project(paired(altered), rows, listOf(record(key)), scope)
            assertTrue(name, receiptText(result.messages[2]).contains("world"))
            assertTrue(name, receiptText(result.messages[2]).contains("此前已保存"))
            assertFalse(result.messages.toString().contains(secret))
            if (name == "novex_apply_content_changes") assertFalse(receiptText(result.messages[2]).contains("已保存并核验"))
        }
    }

    @Test fun linkedCardReceiptRequiresBothEndpointsAndDoesNotGuessAmbiguousOwners() {
        val linked = JSONObject().put("saved", true).put("status", "saved_verified")
            .put("saved_references", JSONArray().put(JSONObject().put("reference_id", "ref")
                .put("source_id", "world").put("target_id", "other").put("purpose", secret)))
        val part = AgentContentPart.ToolResult("link", "novex_link_cards", linked.toString())
        val expanded = old.copy(managedSubjects = old.managedSubjects + ManagedSubject(
            NovexContentAddress.world("other"), ManagedAccess.READ_ONLY))
        assertTrue(com.openminis.app.novex.adapter.NovexPublicWriteReceipt.project(part, key).isEmpty())
        val result = com.openminis.app.novex.adapter.NovexPublicWriteReceipt.project(part, NovexHistoryAccessScope.key(expanded))
        assertTrue(result.toString().contains("ref"))
        assertFalse(result.toString().contains(secret))
        val ambiguous = expanded.copy(managedSubjects = expanded.managedSubjects + ManagedSubject(
            NovexContentAddress.characterVersion("world"), ManagedAccess.READ_ONLY))
        assertTrue(com.openminis.app.novex.adapter.NovexPublicWriteReceipt.project(part, NovexHistoryAccessScope.key(ambiguous)).isEmpty())
    }

    @Test fun missingRowsDoNotTurnFlattenedExecutionIntoPublicConversation() {
        val unknown = listOf(
            LLMMessage(LLMMessage.Role.ASSISTANT, secret, dbMessageId = "missing-work"),
            LLMMessage(LLMMessage.Role.USER, secret, dbMessageId = "missing-tool-result"),
            LLMMessage(LLMMessage.Role.USER, "用户明确原话", publicHistoryText = "用户明确原话",
                contentParts = listOf(AgentContentPart.Text(secret))),
        )
        val result = NovexScopedConversationHistory.project(unknown, emptyList(), emptyList(), key)
        assertEquals(listOf("", "", "用户明确原话"), result.messages.map { it.content })
        assertFalse(result.messages.toString().contains(secret))
        assertTrue(unknown.toString().contains(secret))
    }

}
