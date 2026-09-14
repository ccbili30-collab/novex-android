package com.openminis.app.ui.chat

import android.app.Application
import com.openminis.app.cards.*
import kotlinx.coroutines.runBlocking
import novex.content.*
import novex.runtime.*
import novex.storage.ChangeSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class LargeCardEntryTest {
    @Test fun startThenManageThenSendAgainUsesBoundedMaterialAndReadableOriginal()=runBlocking {
        var binding=CardBinding(primary=SourceSelection("world"))
        val cards=IntegratedCards(RuntimeEnvironment.getApplication(),{binding.encode()},{change->binding=change(binding)})
        val ref=cards.store.contents.allocator()()
        cards.store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){"海港的居民喜欢种花。".repeat(200000).byteInputStream()}
        cards.store.save(ContentDocument("world",CardKind.WORLD,"海港",listOf(ContentModule("m","世界资料",listOf(ContentBlock.Text("b",ref))))),null,ChangeSource.HUMAN,"initial")
        for(request in listOf("start","manage","again")) {
            if(request=="manage")binding=binding.copy(managed=setOf(ManagementTarget("world")))
            val result=cards.candidates("chat",request,"看看港口",emptyList(),4096,com.openminis.app.data.BPETokenizer::countTokens,true){"""{"modules":["m"]}"""}
            assertTrue(result.sumOf {com.openminis.app.data.BPETokenizer.countTokens(it.content)}<=4096)
            assertTrue(result.any {it.sourceId=="new-card-reading" && it.content.contains("next_offset")})
            assertEquals(1,cards.store.open("world")!!.content.modules.size)
        }
        val saved=cards.store.open("world")!!
        val args=JSONObject().put("root_id","world").put("target_id","world").put("draft_version","saved:${saved.revision}")
            .put("module_id","m").put("block_id","b").put("offset",300000).put("count",100)
        val page=cards.execute("chat","again","read","read_text_block",args.toString())
        assertTrue(page.success)
        assertEquals(100,JSONObject(page.output).getString("text").length)
    }
    @Test fun sameRequestShrinksMaterialAfterToolReadsWithoutRepeatingSelection()=runBlocking {
        val binding=CardBinding(primary=SourceSelection("growing"))
        val cards=IntegratedCards(RuntimeEnvironment.getApplication(),{binding.encode()})
        val ref=cards.store.contents.allocator()()
        cards.store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){"安静的港口。".repeat(10000).byteInputStream()}
        cards.store.save(ContentDocument("growing",CardKind.WORLD,"港口",listOf(ContentModule("m","设定",listOf(ContentBlock.Text("b",ref))))),null,ChangeSource.HUMAN,"initial")
        var selections=0
        val first=cards.candidates("chat","request","港口",emptyList(),8192,{it.length},true){selections++;"""{"modules":["m"]}"""}
        val next=cards.candidates("chat","request","港口",emptyList(),4096,com.openminis.app.data.BPETokenizer::countTokens,true){error("不得重复选择")}
        assertEquals(1,selections)
        assertTrue(next.sumOf {it.content.length}<first.sumOf {it.content.length})
        assertTrue(next.sumOf {it.content.length}<=4096)
    }
    @Test fun largeModuleDirectoryDefersSelectionAndKeepsRealReadTargets()=runBlocking {
        val binding=CardBinding(primary=SourceSelection("many"))
        val cards=IntegratedCards(RuntimeEnvironment.getApplication(),{binding.encode()})
        val ref=cards.store.contents.allocator()()
        cards.store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){"港口有一座灯塔。".byteInputStream()}
        val modules=(0 until 300).map {ContentModule("m$it","地点$it",listOf(ContentBlock.Text("b$it",ref)))}
        cards.store.save(ContentDocument("many",CardKind.WORLD,"许多地点",modules),null,ChangeSource.HUMAN,"initial")
        val result=cards.candidates("chat","request","灯塔",emptyList(),4096,com.openminis.app.data.BPETokenizer::countTokens,true){error("目录装不下时不发送选料请求")}
        assertTrue(result.sumOf {com.openminis.app.data.BPETokenizer.countTokens(it.content)}<=4096)
        assertTrue(result.any {it.sourceId=="new-card-source-directory" && it.content.contains("many")})
        assertTrue(result.any {it.sourceId=="new-card-reading"})
        assertEquals(300,cards.store.open("many")!!.content.modules.size)
    }


    @Test fun millionWindowUsesBoundedChineseMaterialAndPreservesOriginal()=runBlocking {
        val binding=CardBinding(primary=SourceSelection("million-window"))
        val cards=IntegratedCards(RuntimeEnvironment.getApplication(),{binding.encode()})
        val ref=cards.store.contents.allocator()()
        cards.store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){"海港的居民喜欢种花。".repeat(200000).byteInputStream()}
        cards.store.save(ContentDocument("million-window",CardKind.WORLD,"海港",listOf(ContentModule("m","设定",listOf(ContentBlock.Text("b",ref))))),null,ChangeSource.HUMAN,"initial")
        val result=cards.candidates("chat","million","看看港口",emptyList(),850000,com.openminis.app.data.BPETokenizer::countTokens,true){"""{"modules":["m"]}"""}
        assertTrue(result.sumOf {com.openminis.app.data.BPETokenizer.countTokens(it.content)}<=850000)
        assertTrue(result.any {it.sourceId=="new-card-reading" && it.content.contains("next_offset")})
        val system=result.joinToString("\n"){it.content}
        // Synthetic fixture only; the live diagnostic reads the exact bounded material.
        java.io.File("build/context-million-synthetic.json").apply {parentFile.mkdirs()}.writeText(
            JSONObject().put("model","deepseek-flash").put("messages",org.json.JSONArray()
                .put(JSONObject().put("role","system").put("content",system))
                .put(JSONObject().put("role","user").put("content","这里只做容量检查，请只回答好。")))
                .put("max_tokens",16).put("thinking",JSONObject().put("type","disabled")).toString())
    }
}
