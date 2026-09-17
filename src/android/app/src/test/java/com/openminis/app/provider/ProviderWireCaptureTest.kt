package com.openminis.app.provider

import android.app.Application
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-provider-wire-capture] 抓取行为守护。
 *
 * [T-presend-contract] PR 0 起契约更新：旧版"纯文本请求不落盘"正是诊断盲区
 * （conv9 队列注入发出的空请求无工具/图片标记，抓取器一声不吭）。现在
 * **每个出站请求无条件记一行摘要**（不含正文，隐私与体积不回归）；带标记的
 * 请求在同一行附原文；被发送前合同拒发的请求留 note 行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ProviderWireCaptureTest {
    @get:Rule
    val files = TemporaryFolder()

    private fun capturedLines(): List<JSONObject> =
        File(files.root, "provider-wire-capture.jsonl").readLines()
            .filter { it.isNotBlank() }.map { JSONObject(it) }

    @Test fun pureTextRequestLeavesSummaryLineWithoutBody() {
        ProviderWireCapture.captureDir = files.root
        ProviderWireCapture.record(
            "test", "普通纯文本对话请求，没有工具标记",
            stats = ProviderWireCapture.RequestStats(messageCount = 1),
        )
        val lines = capturedLines()
        assertEquals(1, lines.size)
        assertEquals("summary", lines[0].getString("kind"))
        assertEquals(1, lines[0].getInt("messages"))
        // 隐私不回归：纯文本正文仍然不落盘。
        assertFalse(lines[0].has("body"))
    }

    @Test fun toolBearingRequestCapturesBodyOnSameLine() {
        ProviderWireCapture.captureDir = files.root
        val body = """{"messages":[{"role":"user","content":"x"},{"type":"tool_result","tool_use_id":"t1"}]}"""
        ProviderWireCapture.record(
            "test", body,
            stats = ProviderWireCapture.RequestStats(messageCount = 2, toolUseCount = 1),
        )
        val lines = capturedLines()
        assertEquals(1, lines.size)
        assertEquals("full", lines[0].getString("kind"))
        assertEquals(body, lines[0].getString("body"))
        assertTrue(lines[0].toString().contains("\"provider\":\"test\""))
    }

    @Test fun refusalNoteCarriesInvariantAndStats() {
        ProviderWireCapture.captureDir = files.root
        ProviderWireCapture.record(
            "test", "", "",
            ProviderWireCapture.RequestStats(messageCount = 1, imageCount = 1),
            note = "refused:I1",
        )
        val lines = capturedLines()
        assertEquals(1, lines.size)
        assertEquals("summary", lines[0].getString("kind"))
        assertEquals("refused:I1", lines[0].getString("note"))
        assertEquals(1, lines[0].getInt("images"))
    }

    @Test fun uninitializedDirIsSilentNoOp() {
        ProviderWireCapture.captureDir = null
        // 不抛异常即通过——目录未初始化时 record 必须静默跳过。
        ProviderWireCapture.record("test", "whatever tool_result body")
    }

    @Test fun oversizedBodyKeepsHeadAndTail() {
        ProviderWireCapture.captureDir = files.root
        val filler = "a".repeat(1_050_000)
        val body = """{"head":"$filler","tail_marker":"tool_result_END"}"""
        ProviderWireCapture.record("test", body)
        val line = File(files.root, "provider-wire-capture.jsonl").readLines().single()
        // 尾部标记必须保留——最近的工具轮在消息数组尾部，是诊断关键。
        assertTrue(line.contains("tool_result_END"))
        assertTrue(line.contains("截断"))
    }

    @Test fun statsOfCountsMessagesImagesAndToolUses() {
        val messages = listOf(
            LLMMessage(
                LLMMessage.Role.USER, "看图",
                imageParts = listOf(LLMMessage.ImagePart(ByteArray(1), "image/png")),
            ),
            LLMMessage(
                LLMMessage.Role.ASSISTANT, "",
                contentParts = listOf(
                    AgentContentPart.ToolUse("c1", "read_text_block", JSONObject()),
                    AgentContentPart.ImageData(ByteArray(1), "image/png"),
                ),
            ),
        )
        val stats = ProviderWireCapture.RequestStats.of(messages)
        assertEquals(2, stats.messageCount)
        assertEquals(2, stats.imageCount)
        assertEquals(1, stats.toolUseCount)
    }
}
