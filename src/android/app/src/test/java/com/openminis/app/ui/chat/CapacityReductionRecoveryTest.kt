package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.BPETokenizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application=android.app.Application::class,sdk=[35])
class CapacityReductionRecoveryTest {
    @Test fun noReducibleHistoryMustNotRequestTheSameCompactionAgain() {
        val history=listOf(LLMMessage(LLMMessage.Role.USER,"继续",dbMessageId="u"))
        assertNull(ConversationRetention.cut(history,0,ConversationRetention.recentBudget(64000)){100})
        repeat(20) {
            assertFalse(ConversationRetention.shouldCompact(history,0,64000,60000){100})
        }
        assertTrue(ConversationRetention.needsCompact(60000,64000))
    }

    @Test fun capacityProjectionIsReversibleAndOriginalRemainsReadable() {
        val text="世界旧设定😀".repeat(6000)
        val result=LLMMessage(LLMMessage.Role.USER,"",contentParts=listOf(AgentContentPart.ToolResult("call","read_text_block",text)),dbMessageId="result")
        val history=listOf(LLMMessage(LLMMessage.Role.USER,"开始",dbMessageId="u"),
            LLMMessage(LLMMessage.Role.ASSISTANT,"",contentParts=listOf(AgentContentPart.ToolUse("call","read_text_block",JSONObject())),dbMessageId="call-row"),result)+
            (1..5).map {LLMMessage(if(it%2==0)LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT,"继续剧情$it",dbMessageId="recent-$it")}
        val occupied=90000
        val small=ConversationToolRetention.project(history,64000,occupied,true,NovexRequestEstimate::part)
        val kept=small[2].contentParts.single() as AgentContentPart.ToolResult
        assertEquals("call",kept.id)
        assertTrue(kept.content.contains("read_conversation_history"))
        assertTrue(kept.content.contains("result"))
        assertFalse(kept.content.contains("file_read"))
        assertEquals(text,(history[2].contentParts.single() as AgentContentPart.ToolResult).content)
        assertEquals(history.takeLast(4),small.takeLast(4))
        assertTrue(NovexRequestEstimate.message(small[2])<NovexRequestEstimate.message(result))
        // Increasing capacity recomputes from originals, not from an irreversible stub.
        assertEquals(history,ConversationToolRetention.project(history,1048576,occupied,true,NovexRequestEstimate::part))
        assertEquals(history,ConversationToolRetention.project(history,64000,occupied,false,NovexRequestEstimate::part))
        var offset=0;val recovered=StringBuilder()
        do {
            val page=JSONObject(ConversationRecall.execute("read_conversation_history",JSONObject().put("message_id","result").put("offset",offset),history,6000))
            recovered.append(page.getString("text"))
            offset=if(page.isNull("next_offset"))-1 else page.getInt("next_offset")
        }while(offset>=0)
        assertEquals(ConversationCompactionPolicy.transcript(listOf(result)),recovered.toString())
    }

    @Test fun recentAndUnpersistedResultsStayIntactEvenUnderPressure() {
        val long=AgentContentPart.ToolResult("t","read","证据".repeat(20000))
        val history=(0..7).map {LLMMessage(LLMMessage.Role.USER,"",contentParts=listOf(long.copy(id="t$it")),dbMessageId=if(it<4)null else "$it")}
        assertEquals(history,ConversationToolRetention.project(history,64000,200000,true,NovexRequestEstimate::part))
    }

    @Test fun aCompleteOlderPrefixStillCompactsNormally() {
        val history=(0..19).map {LLMMessage(if(it%2==0)LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT,"text",dbMessageId="$it")}
        assertTrue(ConversationRetention.shouldCompact(history,0,64000,60000){4000})
        assertFalse(ConversationRetention.shouldCompact(history,0,1048576,60000){4000})
    }

    @Test fun originalWorldConversationResizesAndRetainsAReadableHistory() = kotlinx.coroutines.runBlocking {
        val path=System.getenv("NOVEX_QA_REDUCTION")
        org.junit.Assume.assumeTrue("Private capacity-change archive supplied",!path.isNullOrBlank())
        java.util.zip.ZipFile(path!!).use {zip->
            val runtime=JSONObject(zip.getInputStream(zip.getEntry("environment/current-runtime.json")).reader().readText())
            val trace=zip.entries().asSequence().filter {it.name.contains("teaching-traces/")}.map {
                JSONObject(zip.getInputStream(it).reader().readText())
            }.maxBy {it.optLong("createdAt")}
            // formalPrompt includes adopted material; the host allocates cards
            // only AFTER estimating the base prompt, never as retained history.
            val prompt=trace.getString("formalPrompt").substringBefore("<novex-answer-identity>").trimEnd()
            fun strings(a:org.json.JSONArray?)=(0 until (a?.length()?:0)).map {a!!.getString(it)}
            fun param(v:JSONObject):com.openminis.app.data.model.AgentToolParam=com.openminis.app.data.model.AgentToolParam(
                v.getString("type"),v.optString("description"),v.optJSONArray("enum")?.let(::strings),
                v.optJSONObject("items")?.let(::param),v.optJSONObject("properties")?.let {o->o.keys().asSequence().associateWith {param(o.getJSONObject(it))}},strings(v.optJSONArray("required")))
            val definitions=runtime.getJSONArray("toolDefinitions")
            val tools=(0 until definitions.length()).map {
                val f=definitions.getJSONObject(it).getJSONObject("function");val schema=f.getJSONObject("parameters");val props=schema.getJSONObject("properties")
                com.openminis.app.data.model.AgentToolDefinition(f.getString("name"),f.getString("description"),props.keys().asSequence().associateWith {param(props.getJSONObject(it))},strings(schema.optJSONArray("required")))
            }
            val history=zip.getInputStream(zip.getEntry("database/messages.jsonl")).reader().readLines().filter(String::isNotBlank).map {line->
                val row=JSONObject(line);val parts=org.json.JSONArray(row.getString("parts_json"))
                val content=(0 until parts.length()).mapNotNull {
                    val p=parts.getJSONObject(it)
                    when(p.getString("type")) {
                        "text"->AgentContentPart.Text(p.getString("value"))
                        "toolUse"->p.getJSONObject("value").let {AgentContentPart.ToolUse(it.getString("toolUseId"),it.getString("name"),JSONObject(it.getString("input")))}
                        "toolResult"->p.getJSONObject("value").let {AgentContentPart.ToolResult(it.getString("toolUseId"),it.getString("name"),it.getString("output"))}
                        else->null
                    }
                }
                LLMMessage(if(row.getString("role")=="user")LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT,"",contentParts=content,dbMessageId=row.getString("id"))
            }
            val originals=ConversationCompactionPolicy.transcript(history)
            var binding=com.openminis.app.cards.CardBinding()
            val cards=com.openminis.app.cards.IntegratedCards(org.robolectric.RuntimeEnvironment.getApplication(),{binding.encode()})
            val nested=zip.entries().asSequence().first {it.name.startsWith("new-card-library/") && it.name.endsWith(".zip")}
            val temp=java.io.File.createTempFile("private-world-", ".zip")
            try {
                zip.getInputStream(nested).use {input->temp.outputStream().use {input.copyTo(it)}}
                val card=novex.storage.ExchangeLab.read(temp.toPath(),cards.store.contents)
                cards.store.save(card,null,novex.storage.ChangeSource.HUMAN,"fixture")
                binding=com.openminis.app.cards.CardBinding(primary=novex.runtime.SourceSelection(card.id))
                for(window in listOf(1048576,304770,64000,1048576)) {
                    val before=NovexRequestEstimate.total(history,prompt,tools)
                    val projected=ConversationToolRetention.project(history,window,before,true,NovexRequestEstimate::part)
                    val occupied=NovexRequestEstimate.total(projected,prompt,tools)
                    if(window==64000) {
                        assertTrue("Old tool results must be reduced at 64K",occupied<before)
                        assertFalse(ConversationRetention.shouldCompact(history,0,window,occupied,NovexRequestEstimate::message))
                    }else assertEquals(history,projected)
                    val budget=com.openminis.app.novex.domain.NovexContextBudgetPolicy.moduleBudget(window,occupied,8192+window/20)
                    val material=cards.candidates("chat","resize-$window","继续",projected,budget,BPETokenizer::countTokens,true) {
                        JSONObject().put("modules",org.json.JSONArray(card.modules.map {it.id})).toString()
                    }
                    val composition=com.openminis.app.novex.domain.NovexContextComposer.compose("继续",budget,material,BPETokenizer::countTokens)
                    val finalPrompt=com.openminis.app.novex.domain.NovexContextPromptFormatter.appendTo(prompt,composition.fragments)
                    val finalCost=NovexRequestEstimate.total(projected,finalPrompt,tools)
                    assertTrue("window=$window final=$finalCost",finalCost+8192+window/20<=window)
                    println("resize: window=$window before=$before after=$occupied final=$finalCost")
                }
                assertEquals(originals,ConversationCompactionPolicy.transcript(history))
            }finally{temp.delete()}
        }
    }
}
