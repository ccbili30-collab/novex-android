package com.openminis.app.provider

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-provider-wire-capture] 抓取行为守护：纯文本请求不落盘（隐私与体积），
 * 工具轮请求落 JSONL；未初始化目录时静默跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class ProviderWireCaptureTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test fun capturesOnlyToolBearingRequests() {
        ProviderWireCapture.captureDir = files.root
        ProviderWireCapture.record("test", "普通纯文本对话请求，没有工具标记")
        ProviderWireCapture.record("test", """{"messages":[{"role":"user","content":"x"},{"type":"tool_result","tool_use_id":"t1"}]}""")
        val captured = File(files.root, "provider-wire-capture.jsonl")
        val lines = captured.readLines()
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("tool_result"))
        assertTrue(lines[0].contains("\"provider\":\"test\""))
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
}
