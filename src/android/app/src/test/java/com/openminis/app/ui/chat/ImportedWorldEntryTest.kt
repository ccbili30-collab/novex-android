package com.openminis.app.ui.chat

import android.app.Application
import com.openminis.app.cards.*
import com.openminis.app.data.BPETokenizer
import com.openminis.app.novex.domain.*
import kotlinx.coroutines.runBlocking
import novex.content.*
import novex.runtime.*
import novex.storage.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35])
class ImportedWorldEntryTest {
    @Test fun importedSingleModuleCanStartAt128KWithoutClaimingFullRead()=runBlocking {
        var binding=CardBinding()
        val cards=IntegratedCards(RuntimeEnvironment.getApplication(),{binding.encode()})
        val fixture=System.getenv("NOVEX_QA_WORLD")?.takeIf {java.io.File(it).isFile}
        val card=if(fixture!=null) ExchangeLab.read(java.io.File(fixture).toPath(),cards.store.contents) else {
            val ref=cards.store.contents.allocator()()
            cards.store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){"世界中有山川和城镇。".repeat(20000).byteInputStream()}
            ContentDocument("large-import",CardKind.WORLD,"Imported world",listOf(ContentModule("m","",listOf(ContentBlock.Text("b",ref)))))
        }
        cards.store.save(card,null,ChangeSource.HUMAN,"import")
        binding=CardBinding(primary=SourceSelection(card.id))
        for(budget in listOf(100000,64000,8192)) {
            val material=cards.candidates("chat","request-$budget","开始剧情",emptyList(),budget,BPETokenizer::countTokens,true){
                org.json.JSONObject().put("modules",org.json.JSONArray(card.modules.map {it.id})).toString()
            }
            val composed=NovexContextComposer.compose("开始剧情",budget,material,BPETokenizer::countTokens)
            assertTrue(material.all {candidate-> composed.fragments.any {it.sourceId==candidate.sourceId && !it.partial && it.text==candidate.content.trim()} })
            assertTrue(composed.usedTokens<=budget)
            assertTrue(material.any {it.sourceId=="new-card-reading"})
        }
        println("world-fixture="+if(fixture!=null)"private-original" else "synthetic")
    }
}
