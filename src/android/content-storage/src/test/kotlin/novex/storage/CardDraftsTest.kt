package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class CardDraftsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val card = ContentDocument("card", CardKind.CHARACTER, "角色",
        modules = listOf(ContentModule("module", "经历", emptyList())))

    @Test fun `创建草稿退出重开仍在而正式库尚无空卡`() {
        val root = temporary.newFolder().toPath(); val drafts = CardDrafts(CardStore(root))
        val draft = drafts.create(card)
        val reopened = CardStore(root)
        assertEquals(draft, CardDrafts(reopened).read(card.id))
        assertEquals(1, CardDrafts(reopened).list().size)
        assertTrue(reopened.list().isEmpty())
        assertNull(reopened.open(card.id))
    }

    @Test fun `编辑和预览同一草稿保留位置且保存前正式展示不变`() {
        val root = temporary.newFolder().toPath(); val store = CardStore(root)
        store.save(card, null, ChangeSource.HUMAN, "initial")
        val drafts = CardDrafts(store); val first = drafts.begin(card.id)
        val position = EditorPosition("module", selectionStart = 3, selectionEnd = 6, scrollOffset = 240.0)
        val changed = drafts.update(card.id, first.version, card.copy(name = "草稿中的角色"), position)
        val reopened = CardDrafts(CardStore(root))
        assertEquals(changed, reopened.read(card.id))
        assertEquals(card, store.open(card.id)!!.content)
        val saved = reopened.commit(card.id, changed.version)
        assertEquals(changed.content, CardStore(root).open(card.id)!!.content)
        assertEquals(changed.version, saved.revision)
        assertNull(reopened.read(card.id))
        assertEquals(saved, reopened.commit(card.id, changed.version))
    }

    @Test fun `延迟更新被拒绝且外部正式修改不会覆盖未保存草稿`() {
        val root = temporary.newFolder().toPath(); val store = CardStore(root)
        store.save(card, null, ChangeSource.HUMAN, "initial")
        val drafts = CardDrafts(store); val first = drafts.begin(card.id)
        val updated = drafts.update(card.id, first.version, card.copy(name = "人的草稿"), EditorPosition())
        assertThrows(DraftConflict::class.java) { drafts.update(card.id, first.version, card, EditorPosition()) }
        store.save(card.copy(name = "较新的正式内容"), "initial", ChangeSource.AI, "other")
        assertThrows(RevisionConflict::class.java) { drafts.commit(card.id, updated.version) }
        assertEquals(updated, drafts.read(card.id))
        assertEquals("other", store.open(card.id)!!.revision)
    }

    @Test fun `保存成功清理前中断重开能识别已提交且不重复保存`() {
        val root = temporary.newFolder().toPath(); val store = CardStore(root); val drafts = CardDrafts(store)
        val draft = drafts.create(card)
        assertThrows(IOException::class.java) { drafts.commit(card.id, draft.version) { throw IOException("清理前中断") } }
        val reopened = CardDrafts(CardStore(root))
        assertNull(reopened.read(card.id))
        assertEquals(draft.version, reopened.commit(card.id, draft.version).revision)
        assertEquals(1, store.list().size)
    }

    @Test fun `主动放弃只移除指定草稿不影响正式版本`() {
        val store = CardStore(temporary.newFolder().toPath()); store.save(card, null, ChangeSource.HUMAN, "initial")
        val drafts = CardDrafts(store); val first = drafts.begin(card.id)
        val changed = drafts.update(card.id, first.version, card.copy(name = "草稿"), EditorPosition())
        assertThrows(DraftConflict::class.java) { drafts.discard(card.id, first.version) }
        drafts.discard(card.id, changed.version)
        assertEquals(card, store.open(card.id)!!.content)
        assertNull(drafts.read(card.id))
    }
}
