package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StoryWithToolsTest {
    @Test fun toolsDoNotReclassifyStoryBeforeOrAfterTheCall()=runBlocking {
        for(monolithic in listOf(true,false)) {
            val turn=AssistantStreamTurn(0,{it})
            turn.accept(LLMStreamChunk.Text("你走进村庄。"),monolithic){}
            turn.accept(LLMStreamChunk.ToolUseStart("call","read_card"),monolithic){}
            turn.accept(LLMStreamChunk.ToolCallComplete("call","read_card",JSONObject()),monolithic){}
            turn.accept(LLMStreamChunk.Text("村民向你挥手。"),monolithic){}
            turn.finish {}
            val snapshot=turn.snapshot()
            assertEquals("你走进村庄。村民向你挥手。",snapshot.text)
            assertTrue(snapshot.blocks.filter {it.isText}.all {!it.executionText})
            assertEquals(if(monolithic)snapshot.text else "你走进村庄。\n\n村民向你挥手。",formalAssistantText(snapshot.blocks,""))
        }
    }
}
