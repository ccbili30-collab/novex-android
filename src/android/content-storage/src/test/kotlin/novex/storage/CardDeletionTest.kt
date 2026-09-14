package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardDeletionTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun `删除后重开库不可见并拒绝迟到写入与迁移复活但保留原内容`() {
        val root=temporary.newFolder().toPath();val store=CardStore(root)
        val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("source"),ref))){"原文与图片归属保留".byteInputStream()}
        val card=RawTextImport.prepareUtf8(ref,store.contents,CardKind.CHARACTER,"角色")
        store.save(card,null,ChangeSource.AI,"first")
        store.delete(card.id,"first")
        val reopened=CardStore(root)
        assertNull(reopened.open(card.id));assertTrue(reopened.list().isEmpty());assertTrue(reopened.isDeleted(card.id))
        assertEquals(card,reopened.deletedCard(card.id)!!.content)
        assertEquals("原文与图片归属保留",reopened.contents.open(ref).bufferedReader().use{it.readText()})
        assertThrows(IllegalStateException::class.java){reopened.save(card,"first",ChangeSource.AI,"late")}
        assertThrows(IllegalStateException::class.java){reopened.save(card,null,ChangeSource.IMPORT,"migration")}
        reopened.delete(card.id,"first")
    }
    @Test fun `旧版本删除不能抹掉已保存新修改`() {
        val store=CardStore(temporary.newFolder().toPath());val card=ContentDocument("role",CardKind.CHARACTER,"旧名")
        store.save(card,null,ChangeSource.HUMAN,"first")
        store.save(card.copy(name="新名"),"first",ChangeSource.AI,"second")
        assertThrows(RevisionConflict::class.java){store.delete(card.id,"first")}
        assertEquals("新名",store.open(card.id)!!.content.name)
    }
    @Test fun `内部角色被AI占用时不能删除整个世界`() {
        val store=CardStore(temporary.newFolder().toPath())
        val role=ContentDocument("role",CardKind.CHARACTER,"角色")
        val world=ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(role))
        store.save(world,null,ChangeSource.HUMAN,"first")
        store.acquireEdit(world.id,role.id,"AI").use {
            assertThrows(CardOccupied::class.java){store.delete(world.id,"first")}
        }
        assertNotNull(store.open(world.id))
        store.delete(world.id,"first");assertNull(store.open(world.id))
    }
}
