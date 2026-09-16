package novex.runtime

import novex.content.*
import novex.storage.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-saved-with-ids] 保存结果回传新建编号：新增模块/新建文字块后 Saved 携带
 * 对应 created_* 编号；纯文本替换（无新对象）返回空集。模型由此直链下一步，
 * 免 read_card 回读（对话包实测回读占建卡往返的一半）。
 */
class SavedWithCreatedIdsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun freshCard(): Pair<CardStore, String> {
        val store = CardStore(temporary.newFolder().toPath())
        val id = "card"
        CardDrafts(store).create(ContentDocument(id, CardKind.CHARACTER, "角色", listOf(
            ContentModule("m", "经历", emptyList()),
        )))
        return store to id
    }

    private fun submit(store: CardStore, version: String, edit: CardToolEdit): CardToolResult.Saved {
        val coordinator = CardToolCoordinator(store, TurnJournal(temporary.newFolder().toPath()))
        return coordinator.submit(CardToolRequest("chat", "call", ManagementTarget("card"), version, edit),
            CardToolPolicy(setOf(ManagementTarget("card")))) as CardToolResult.Saved
    }

    @Test fun addModuleReportsCreatedModuleId() {
        val (store, _) = freshCard()
        val initial = CardDrafts(store).read("card")!!
        val saved = submit(store, initial.version, CardToolEdit.AddModule("出身"))
        assertEquals(listOf<String>(), saved.createdBlocks)
        assertEquals(1, saved.createdModules.size)
        // 编号真实存在于保存后的结构里。
        val savedDoc = ContentTargets.find(store.open(saved.rootId)!!.content, "card")
        assertEquals(2, savedDoc.modules.count { it.id == saved.createdModules.single() || it.id == "m" })
    }

    @Test fun writeNewBlockReportsCreatedBlockId() {
        val (store, _) = freshCard()
        val initial = CardDrafts(store).read("card")!!
        val saved = submit(store, initial.version, CardToolEdit.WriteText("m", null, "段落", "模型补充"))
        assertEquals(emptyList<String>(), saved.createdModules)
        assertEquals(1, saved.createdBlocks.size)
    }

    @Test fun replacingExistingBlockReportsNothingNew() {
        val (store, _) = freshCard()
        val first = submit(store, CardDrafts(store).read("card")!!.version, CardToolEdit.WriteText("m", null, "段落", "第一版"))
        val second = submit(store, "saved:${first.revision}", CardToolEdit.WriteText("m", first.createdBlocks.single(), "段落", "第二版"))
        assertEquals(emptyList<String>(), second.createdModules)
        assertEquals(emptyList<String>(), second.createdBlocks)
    }
}
