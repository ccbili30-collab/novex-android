package novex.runtime

import novex.content.*
import novex.conversation.*
import novex.storage.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class BudgetedMaterialsTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun setup(use:ModuleUse?=null):Pair<CardStore,RequestMaterialDraft> {
        val store=CardStore(temporary.newFolder().toPath())
        val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){"甲乙丙丁".repeat(500000).byteInputStream()}
        val card=ContentDocument("world",CardKind.WORLD,"大世界",listOf(ContentModule("module","原文",listOf(ContentBlock.Text("block",ref)),use=use)))
        store.save(card,null,ChangeSource.HUMAN,"initial")
        val materials=RequestMaterials(store)
        val draft=materials.prepare(listOf(SourceSelection(card.id)),emptySet(),emptyList(),TriggerWindow(1,setOf(MessageRole.USER)))
        // [T-stage1-tags] use=null 未配置模块已默认进材料流（ROUTED_
        // ACTIVATION），无需再走 selectAutomatic——prepare 产物即含该
        // 模块的分页读取面。
        return store to draft
    }
    @Test fun largeSelectedModuleUsesTheSamePagesAsTheReadingToolAndKeepsOriginal() {
        val (store,draft)=setup()
        val read=BudgetedMaterials(store).read(draft,4096,{it.length},true).single()
        assertEquals(4096,read.text.length);assertEquals(4096L,read.next)
        assertEquals("甲乙丙丁",TextPages(store.contents).read(read.source.reference,read.next!!,4).text)
        assertEquals(1,store.open("world")!!.content.modules.size)
        assertEquals(read,BudgetedMaterials(store).read(draft,4096,{it.length},true).single())
    }
    @Test fun requiredAndReadOnlyNeverPretendAPartialReadIsComplete() {
        val (store,required)=setup(ModuleUse.Always)
        assertThrows(IllegalArgumentException::class.java){BudgetedMaterials(store).read(required,100,{it.length},true)}
        val (other,automatic)=setup()
        assertThrows(IllegalArgumentException::class.java){BudgetedMaterials(other).read(automatic,100,{it.length},false)}
    }
    @Test fun exhaustedBudgetLeavesReadableReferenceAndNoFabricatedBody() {
        val (store,draft)=setup()
        val read=BudgetedMaterials(store).read(draft,0,{it.length},true).single()
        assertEquals("",read.text);assertEquals(0L,read.next)
    }
    @Test fun recoveredSelectionReadsRealPagesAcrossSourcesAndPreservesContinuation() {
        val store=CardStore(temporary.newFolder().toPath())
        val modules=(1..3).map { i ->
            val ref=store.contents.allocator()()
            store.contents.receive(listOf(ContentTransfer(ContentRef("input"),ref))){("资料$i".repeat(1000)).byteInputStream()}
            // [T-stage1-tags] 显式 standby 保持 UNCONFIGURED 候选身份——
            // use=null 的未配置模块如今默认进材料流（ROUTED_ACTIVATION），
            // 不再是选择器候选；恢复选料路径需要真实候选。
            ContentModule("m$i","资料$i",listOf(ContentBlock.Text("b$i",ref)),routing=novex.content.ModuleRouting.STANDBY)
        }
        val card=ContentDocument("world",CardKind.WORLD,"世界",modules)
        store.save(card,null,ChangeSource.HUMAN,"initial")
        val materials=RequestMaterials(store)
        val base=materials.prepare(listOf(SourceSelection(card.id)),emptySet(),emptyList(),TriggerWindow(1,setOf(MessageRole.USER)))
        val draft=materials.selectAutomatic(base,modules.map {it.id}.toSet(),recovered=true)
        val pages=BudgetedMaterials(store).read(draft,300,{it.length},true)
        assertEquals(3,pages.size)
        assertEquals(300,pages.sumOf {it.text.length})
        pages.forEachIndexed {i,page ->
            assertTrue(page.text.startsWith("资料${i+1}"))
            assertEquals(100L,page.next)
            assertEquals(100,page.text.length)
            assertTrue(TextPages(store.contents).read(page.source.reference,page.next!!,10).text.isNotEmpty())
        }
        assertTrue(draft.plan.decisions.all {it.reason==AdoptionReason.RECOVERY_READ})
    }
}
