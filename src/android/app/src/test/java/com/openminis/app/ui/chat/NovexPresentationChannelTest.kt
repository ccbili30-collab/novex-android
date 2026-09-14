package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexPresentationChannelTest {
    @Test fun formalAnswerIsNeverClassifiedAsExecutionText() {
        assertEquals(
            NovexPresentationChannel.FORMAL_ANSWER,
            AssistantBlock("answer", "text", "正常回答").presentationChannel(),
        )
    }

    @Test fun executionTextThinkingAndToolsHaveDistinctChannels() {
        assertEquals(
            NovexPresentationChannel.PROCESS_TEXT,
            AssistantBlock("progress", "text", "正在读取", executionText = true).presentationChannel(),
        )
        assertEquals(
            NovexPresentationChannel.THINKING,
            AssistantBlock("thinking", "thinking", "内部过程").presentationChannel(),
        )
        assertEquals(
            NovexPresentationChannel.TOOL,
            AssistantBlock("tool", "tool_use", toolName = "novex_read_card").presentationChannel(),
        )
    }
}
