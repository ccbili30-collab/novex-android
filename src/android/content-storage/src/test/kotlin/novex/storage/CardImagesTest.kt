package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class CardImagesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `已有图片重复展示只新增块不复制资源且拒绝错误定位`() {
        val store=CardStore(temporary.newFolder().toPath());val editor=CardEditor(store)
        val first=initial(store)
        val added=CardImages(store).add("card",first.version,"module","before",ByteArrayInputStream(png()))
        val original=added.content.modules.single().blocks[1] as ContentBlock.Image
        val removed=editor.apply("card",added.version,"card",EditorCommand.RemoveBlock("module",original.id))
        val reused=editor.apply("card",removed.version,"card",EditorCommand.InsertOwnedImage("module",original.resourceId,"before"))
        val block=reused.content.modules.single().blocks[1] as ContentBlock.Image
        assertNotEquals(original.id,block.id);assertEquals(original.resourceId,block.resourceId)
        assertEquals(added.content.resources,reused.content.resources)
        assertEquals(listOf("before",block.id,"after"),reused.content.modules.single().blocks.map {it.id})
        assertThrows(IllegalArgumentException::class.java){editor.apply("card",reused.version,"card",EditorCommand.InsertOwnedImage("module","foreign","before"))}
        assertThrows(IllegalArgumentException::class.java){editor.apply("card",reused.version,"card",EditorCommand.InsertOwnedImage("module",original.resourceId,"missing"))}
        assertEquals(reused,CardDrafts(store).read("card"))
        val again=editor.apply("card",reused.version,"card",EditorCommand.InsertOwnedImage("module",original.resourceId,block.id),ChangeSource.AI)
        assertEquals(1,again.content.resources.size);assertEquals(2,again.content.modules.single().blocks.filterIsInstance<ContentBlock.Image>().size)
    }
    @Test fun `清除封面头像只解除用途不删除原资源及模块展示`() {
        val store=CardStore(temporary.newFolder().toPath());val draft=initial(store);val editor=CardEditor(store)
        var current=CardImages(store).add("card",draft.version,"module",null,ByteArrayInputStream(png()))
        val image=current.content.modules.single().blocks.last() as ContentBlock.Image
        current=editor.apply("card",current.version,"card",EditorCommand.Appearance(image.resourceId,true))
        current=editor.apply("card",current.version,"card",EditorCommand.Appearance(image.resourceId,false))
        val original=current.content
        current=editor.apply("card",current.version,"card",EditorCommand.Appearance(null,true))
        assertEquals(image.resourceId,current.content.appearance.avatarResourceId);assertNull(current.content.appearance.coverResourceId)
        current=editor.apply("card",current.version,"card",EditorCommand.Appearance(null,false))
        assertEquals(original.copy(appearance=CardAppearance()),current.content)
        val saved=CardDrafts(store).commit("card",current.version)
        assertArrayEquals(png(),store.contents.open(saved.content.resources.single().content).use {it.readBytes()})
    }
    @Test fun `替换图片保留块位置说明及原封面资源并可还原`() {
        val store=CardStore(temporary.newFolder().toPath());val initial=initial(store)
        val added=CardImages(store).add("card",initial.version,"module","before",ByteArrayInputStream(png()))
        val block=added.content.modules.single().blocks[1] as ContentBlock.Image
        val described=CardEditor(store).apply("card",added.version,"card",EditorCommand.WriteCaption("module",block.id,"图文","保留说明",EditorPosition("module",block.id)))
        val covered=CardEditor(store).apply("card",described.version,"card",EditorCommand.Appearance(block.resourceId,true))
        val replacement=CardImages(store).replace("card",covered.version,"module",block.id,ByteArrayInputStream(png()))
        val result=replacement.content.modules.single().blocks[1] as ContentBlock.Image
        assertEquals(block.id,result.id);assertNotEquals(block.resourceId,result.resourceId)
        assertEquals(listOf("before",block.id,"after"),replacement.content.modules.single().blocks.map {it.id})
        assertEquals("保留说明",store.contents.open(result.caption!!).bufferedReader().use {it.readText()})
        assertEquals(block.resourceId,replacement.content.appearance.coverResourceId)
        assertEquals(2,replacement.content.resources.size)
        assertThrows(DraftConflict::class.java){CardImages(store).replace("card",covered.version,"module",block.id,ByteArrayInputStream(png()))}
        assertEquals(replacement,CardDrafts(store).read("card"))
        CardDrafts(store).commit("card",replacement.version)
        val archive=temporary.root.toPath().resolve("replacement.zip");CardFiles(store).export("card",archive)
        val destination=CardStore(temporary.newFolder().toPath())
        val restored=CardFiles(destination).prepare(Files.newInputStream(archive),"还原",CardKind.WORLD).content
        val restoredBlock=restored.modules.single().blocks[1] as ContentBlock.Image
        assertNotEquals(restored.appearance.coverResourceId,restoredBlock.resourceId)
        assertEquals(2,restored.resources.size)
        assertEquals("保留说明",destination.contents.open(restoredBlock.caption!!).bufferedReader().use {it.readText()})
    }
    private fun initial(store: CardStore): CardDraft {
        val allocate = store.contents.allocator();val a = allocate();val b = allocate()
        store.contents.receive(listOf(ContentTransfer(ContentRef("a"),a),ContentTransfer(ContentRef("b"),b))) { ByteArrayInputStream(it.value.toByteArray()) }
        return CardDrafts(store).create(ContentDocument("card",CardKind.CHARACTER,"角色",
            listOf(ContentModule("module","图文",listOf(ContentBlock.Text("before",a),ContentBlock.Text("after",b))))))
    }

    @Test fun `图片插入文字之间且删除外部来源后仍可保存导出还原`() {
        val root=temporary.newFolder().toPath();val store=CardStore(root);val draft=initial(store)
        val bytes=png();val source=temporary.root.toPath().resolve("picture.png");Files.write(source,bytes)
        val added=CardImages(store).add(draft.content.id,draft.version,"module","before",Files.newInputStream(source))
        Files.delete(source)
        assertEquals(listOf("before",added.position.blockId,"after"),added.content.modules.single().blocks.map { it.id })
        val image=added.content.modules.single().blocks[1] as ContentBlock.Image
        val withAppearance=ContentChanges.apply(added.content,ContentChange.SetAppearance(CardAppearance(image.resourceId,image.resourceId)))
        val current=CardDrafts(store).update(added.content.id,added.version,withAppearance,added.position)
        CardDrafts(store).commit(current.content.id,current.version)
        val archive=temporary.root.toPath().resolve("card.zip");CardFiles(store).export(current.content.id,archive)
        val target=CardStore(temporary.newFolder().toPath())
        val imported=CardFiles(target).prepare(Files.newInputStream(archive),"ignored",CardKind.WORLD)
        CardDrafts(target).commit(imported.content.id,imported.version)
        val reopened=target.open(imported.content.id)!!.content
        val copiedImage=reopened.modules.single().blocks[1] as ContentBlock.Image
        assertEquals(copiedImage.resourceId,reopened.appearance.avatarResourceId)
        assertEquals(copiedImage.resourceId,reopened.appearance.coverResourceId)
        assertArrayEquals(bytes,target.contents.open(reopened.resources.single().content).use { it.readBytes() })
    }

    @Test fun `移除图片块不删除资源或封面引用`() {
        val store=CardStore(temporary.newFolder().toPath());val draft=initial(store)
        val added=CardImages(store).add("card",draft.version,"module",null,ByteArrayInputStream(png()))
        val image=added.content.modules.single().blocks.last() as ContentBlock.Image
        val appearance=ContentChanges.apply(added.content,ContentChange.SetAppearance(CardAppearance(coverResourceId=image.resourceId)))
        val removed=ContentChanges.apply(appearance,ContentChange.EditBlocks(listOf(BlockEdit.Remove(image.id))))
        val current=CardDrafts(store).update("card",added.version,removed,EditorPosition("module"))
        val saved=CardDrafts(store).commit("card",current.version)
        assertEquals(2,saved.content.modules.single().blocks.size)
        assertEquals(image.resourceId,saved.content.appearance.coverResourceId)
        assertArrayEquals(png(),store.contents.open(saved.content.resources.single().content).use { it.readBytes() })
    }

    @Test fun `无效图片头和过期草稿不能改变当前模块`() {
        val store=CardStore(temporary.newFolder().toPath());val draft=initial(store)
        assertThrows(IllegalArgumentException::class.java) { CardImages(store).add("card",draft.version,"module",null,ByteArrayInputStream("not image".toByteArray())) }
        assertThrows(DraftConflict::class.java) { CardImages(store).add("card","stale","module",null,ByteArrayInputStream(png())) }
        assertEquals(draft,CardDrafts(store).read("card"))
    }

    @Test fun `世界内角色新增图片只属于目标角色且随世界保存`() {
        val store=CardStore(temporary.newFolder().toPath())
        val role=ContentDocument("role",CardKind.CHARACTER,"守塔人",listOf(ContentModule("module","图像",emptyList())))
        val sibling=ContentDocument("sibling",CardKind.CHARACTER,"船长")
        val world=ContentDocument("world",CardKind.WORLD,"港口",internalCharacters=listOf(role,sibling))
        val draft=CardDrafts(store).create(world)
        val added=CardImages(store).add("world",draft.version,"module",null,ByteArrayInputStream(png()),"role")
        assertTrue(added.content.resources.isEmpty())
        assertEquals(sibling,added.content.internalCharacters[1])
        val target=added.content.internalCharacters.first()
        val image=target.modules.single().blocks.single() as ContentBlock.Image
        assertEquals(image.resourceId,target.resources.single().id)
        assertArrayEquals(png(),store.contents.open(target.resources.single().content).use { it.readBytes() })
        CardDrafts(store).commit("world",added.version)
        assertEquals(target,store.open("world")!!.content.internalCharacters.first())
    }

    /** 有效的两像素测试图片，不依赖平台图像库。 */
    private fun png(): ByteArray {
        val result=ByteArrayOutputStream();val out=DataOutputStream(result)
        out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type: String,data: ByteArray) {
            val name=type.toByteArray(Charsets.US_ASCII);val crc=CRC32();crc.update(name);crc.update(data)
            out.writeInt(data.size);out.write(name);out.write(data);out.writeInt(crc.value.toInt())
        }
        val header=ByteArrayOutputStream();DataOutputStream(header).use { it.writeInt(2);it.writeInt(1);it.write(byteArrayOf(8,2,0,0,0)) }
        chunk("IHDR",header.toByteArray())
        val pixels=ByteArrayOutputStream();DeflaterOutputStream(pixels).use { it.write(byteArrayOf(0,255.toByte(),0,0,0,100,255.toByte())) }
        chunk("IDAT",pixels.toByteArray());chunk("IEND",byteArrayOf());return result.toByteArray()
    }
}
