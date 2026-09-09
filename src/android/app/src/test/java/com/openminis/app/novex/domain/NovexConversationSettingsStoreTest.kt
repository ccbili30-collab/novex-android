package com.openminis.app.novex.domain

import com.openminis.app.data.ConversationSettingsSnapshot
import com.openminis.app.novex.adapter.NovexConversationSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class NovexConversationSettingsStoreTest {
    private class Fixture {
        var live = ConversationSettingsSnapshot("我的提示词", imageStylePrompt = "水彩",
            novexConfigurationJson = NovexConversationConfigurationCodec.encode(NovexConversationConfigurationSnapshot("conversation")))
        var stored = live
        val events = mutableListOf<String>()
        var failWrite = false
        val store = NovexConversationSettingsStore(Mutex(), { "conversation" }, { live },
            NovexManagementTransaction { work ->
                val previous = stored
                events += "begin"
                try { work(); events += "commit" }
                catch (failure: Throwable) { stored = previous; events += "rollback"; throw failure }
            },
            adopt = { events += "adopt"; it },
            write = { _, settings -> events += "write"; check(!failWrite) { "disk full" }; stored = settings },
            install = { events += "install"; live = it })
    }

    @Test fun settingsAndToolChangesShareOneCommitAndKeepUnrelatedFields() = runBlocking {
        val f = Fixture()
        val identity = AnswerIdentity.PersonaPreset("host", "主持人", "主持，不代替玩家行动")
        val configured = f.store.update(captureSources = true) { it.copy(answerIdentity = identity) }
        assertEquals(identity, configured.answerIdentity)
        assertEquals(listOf("begin", "adopt", "write", "commit", "install"), f.events)
        assertEquals(f.stored, f.live)
        assertEquals("水彩", f.live.imageStylePrompt)
        assertEquals("我的提示词", f.live.conversationPrompt)
        val old = f.live
        f.store.update(settings = old.copy(conversationPrompt = "本对话修改"), expectedConfigurationJson = old.novexConfigurationJson)
        assertEquals(identity, NovexConversationConfigurationCodec.decode(f.stored.novexConfigurationJson, "conversation").answerIdentity)
        assertEquals("本对话修改", f.live.conversationPrompt)
    }

    @Test fun failedWriteDoesNotInstallOrClaimTheNewState() = runBlocking {
        val f = Fixture(); val before = f.live; f.failWrite = true
        val failure = runCatching { f.store.update { it.copy(answerIdentity = NovexPersonaPresets.gameHost) } }
        assertTrue(failure.isFailure)
        assertEquals(before, f.live); assertEquals(before, f.stored)
        assertEquals(listOf("begin", "write", "rollback"), f.events)
    }

    @Test fun editorWaitingBehindToolCannotOverwriteTheCommittedIdentity() = runBlocking {
        val f = Fixture(); val before = f.live
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val tool = async { f.store.update { entered.complete(Unit); release.await(); it.copy(answerIdentity = NovexPersonaPresets.gameHost) } }
        entered.await()
        val editor = async { runCatching { f.store.update(settings = before.copy(conversationPrompt = "旧编辑"), expectedConfigurationJson = before.novexConfigurationJson) } }
        release.complete(Unit); tool.await()
        assertTrue(editor.await().isFailure)
        assertEquals(NovexPersonaPresets.gameHost, NovexConversationConfigurationCodec.decode(f.stored.novexConfigurationJson, "conversation").answerIdentity)
        assertEquals("我的提示词", f.live.conversationPrompt)
    }
}
