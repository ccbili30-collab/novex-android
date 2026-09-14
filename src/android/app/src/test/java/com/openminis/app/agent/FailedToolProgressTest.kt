package com.openminis.app.agent
import org.junit.Assert.*
import org.junit.Test

class FailedToolProgressTest {
    @Test fun sameFailureStopsDespiteChangedOptionsAndUnrelatedSuccessfulTools() {
        val guard=FailedToolProgress()
        val error="阅读记录未能保存，本次没有返回正文：读取没有对应的用户请求"
        repeat(2) { depth ->
            assertNull(guard.record("document_inspect",mapOf("document_ref" to "doc", "max_depth" to depth),false,error))
            assertNull(guard.record("workspace_inspect",emptyMap(),true,"empty"))
            assertNull(guard.record("learning_prepare",emptyMap(),true,"prepared"))
        }
        assertNotNull(guard.record("document_inspect",mapOf("document_ref" to "doc","include_outline" to true),false,error))
    }
    @Test fun successfulPagesAndRecoveredReadsNeverHitAnArbitraryCountLimit() {
        val guard=FailedToolProgress()
        repeat(150) { page ->
            assertNull(guard.record("document_read",mapOf("document_ref" to "doc", "offset" to page),true,"page-$page"))
        }
        repeat(4) {
            assertNull(guard.record("document_read",mapOf("document_ref" to "doc"),false,"retry"))
            assertNull(guard.record("document_read",mapOf("document_ref" to "doc"),true,"read"))
        }
    }
    @Test fun separateResourcesAndFreshUserTurnsDoNotInheritFailures() {
        val guard=FailedToolProgress()
        repeat(8) { assertNull(guard.record("document_read",mapOf("document_ref" to "doc-$it"),false,"missing")) }
        repeat(2) { assertNull(guard.record("document_read",mapOf("document_ref" to "doc"),false,"missing")) }
        guard.reset()
        assertNull(guard.record("document_read",mapOf("document_ref" to "doc"),false,"missing"))
    }
}
