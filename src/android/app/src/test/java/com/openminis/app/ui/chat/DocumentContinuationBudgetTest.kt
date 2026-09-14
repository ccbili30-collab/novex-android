package com.openminis.app.ui.chat

import android.app.Application
import com.openminis.app.cards.*
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.model.*
import com.openminis.app.novex.domain.*
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.ZipFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class DocumentContinuationBudgetTest {
    @Test fun emptyBindingDoesNotBlockDocumentContinuationWithNoMaterialBudget() = runBlocking {
        val cards=IntegratedCards(RuntimeEnvironment.getApplication(),{CardBinding().encode()})
        val result=cards.candidates("chat","request","1",emptyList(),0,BPETokenizer::countTokens,true){error("No card selection needed")}
        assertTrue(result.isEmpty())
    }

    @Test fun schemasUseWireRepresentationAndToolResultIsCountedOnlyOnce() {
        val tools=listOf(AgentToolDefinition("read", "Read a document", mapOf("ref" to AgentToolParam("string","Reference"))))
        val output="document text".repeat(100)
        val message=LLMMessage(LLMMessage.Role.USER,output,contentParts=listOf(AgentContentPart.ToolResult("call","read",output)))
        val expected=BPETokenizer.countTokens(output)+8+BPETokenizer.countTokens("prompt")+
            BPETokenizer.countTokens(tools.single().toOpenAIJson().toString())+512
        assertEquals(expected,NovexRequestEstimate.total(listOf(message),"prompt",tools))
    }

    @Test fun originalDocumentReadCanReachNextModelRequestAt64K()=runBlocking {
        val path=System.getenv("NOVEX_QA_CONTINUATION")
        org.junit.Assume.assumeTrue("Private diagnostic archive supplied on the build host", !path.isNullOrBlank())
        ZipFile(path!!).use { zip ->
            val trace=JSONObject(zip.getInputStream(zip.entries().asSequence().first { it.name.contains("teaching-traces/") }).reader().readText())
            fun strings(array:JSONArray?)=(0 until (array?.length()?:0)).map {array!!.getString(it)}
            fun param(value:JSONObject):AgentToolParam=AgentToolParam(value.getString("type"),value.optString("description"),
                value.optJSONArray("enum")?.let(::strings),value.optJSONObject("items")?.let(::param),
                value.optJSONObject("properties")?.let {p->p.keys().asSequence().associateWith {param(p.getJSONObject(it))}},strings(value.optJSONArray("required")))
            val definitions=trace.getJSONArray("toolDefinitions")
            val tools=(0 until definitions.length()).map {index->
                val f=definitions.getJSONObject(index).getJSONObject("function");val schema=f.getJSONObject("parameters");val props=schema.getJSONObject("properties")
                AgentToolDefinition(f.getString("name"),f.getString("description"),props.keys().asSequence().associateWith {param(props.getJSONObject(it))},strings(schema.optJSONArray("required")))
            }
            val history=zip.getInputStream(zip.getEntry("database/messages.jsonl")).reader().readLines().filter(String::isNotBlank).map {line->
                val row=JSONObject(line);val parts=JSONArray(row.getString("parts_json"))
                val content=(0 until parts.length()).mapNotNull {index->
                    val p=parts.getJSONObject(index)
                    when(p.getString("type")) {
                        "text"->AgentContentPart.Text(p.getString("value"))
                        "toolUse"->p.getJSONObject("value").let {AgentContentPart.ToolUse(it.getString("toolUseId"),it.getString("name"),JSONObject(it.getString("input")))}
                        "toolResult"->p.getJSONObject("value").let {AgentContentPart.ToolResult(it.getString("toolUseId"),it.getString("name"),it.getString("output"))}
                        else->null // The file itself is parsed locally; its receipt is a text part above.
                    }
                }
                LLMMessage(if(row.getString("role")=="user")LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT,"",contentParts=content,dbMessageId=row.getString("id"))
            }
            val prompt=trace.getString("formalPrompt")
            val cards=IntegratedCards(RuntimeEnvironment.getApplication(),{CardBinding().encode()})
            val reserve=8192+64000/20
            for(size in listOf(1,3,5)) {
                val stage=history.take(size)
                val occupied=NovexRequestEstimate.total(stage,prompt,tools)
                val budget=NovexContextBudgetPolicy.moduleBudget(64000,occupied,reserve)
                assertTrue("stage=$size must leave room for an answer; occupied=$occupied",budget>0)
                val candidates=cards.candidates("chat","request","1",stage,budget,BPETokenizer::countTokens,true){error("No adopted cards")}
                val composition=NovexContextComposer.compose("1",budget,candidates,BPETokenizer::countTokens)
                assertEquals(prompt,NovexContextPromptFormatter.appendTo(prompt,composition.fragments))
            }
            val oldOccupied=history.sumOf(NovexRequestEstimate::message)+BPETokenizer.countTokens(prompt)+BPETokenizer.countTokens(tools.toString())+2048
            assertTrue("Original debug-string budget must reproduce the overflow",oldOccupied+reserve>64000)
            val occupied=NovexRequestEstimate.total(history,prompt,tools)
            assertTrue(occupied+reserve<=64000)
            println("continuation: legacy=$oldOccupied; fixed=$occupied; reserve=$reserve; enabled=64000")
            val server=MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Document received\"},\"finish_reason\":null}]}\n\n"+
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"+
                    "data: [DONE]\n\n"))
                val provider=OpenAIProvider("test",LLMModel.gpt4oMini,server.url("/v1").toString().trimEnd('/'))
                val response=provider.streamMessage(history,prompt,8192,tools=tools).toList()
                assertTrue(response.any {it is LLMStreamChunk.Text && it.text=="Document received"})
                val body=JSONObject(requireNotNull(server.takeRequest(5,TimeUnit.SECONDS)).body.readUtf8())
                val messages=body.getJSONArray("messages")
                val result=history.last().contentParts.single() as AgentContentPart.ToolResult
                assertTrue((0 until messages.length()).map(messages::getJSONObject).any {it.optString("role")=="tool" && it.optString("content")==result.content})
            }finally{server.shutdown()}
        }
    }
}
