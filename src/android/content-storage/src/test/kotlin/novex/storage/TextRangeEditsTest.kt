package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.random.Random

class TextRangeEditsTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun put(files:StagedContentFiles,text:String):ContentRef {
        val ref=files.allocator()()
        files.receive(listOf(ContentTransfer(ContentRef("source"),ref))){text.byteInputStream()}
        return ref
    }
    private fun read(files:StagedContentFiles,ref:ContentRef)=files.open(ref).bufferedReader().use {it.readText()}

    @Test fun `随机增删替换与独立字符串结果一致且原文不变`() {
        val files=StagedContentFiles(temporary.newFolder().toPath());val edit=TextRangeEdits(files)
        val original="甲😀é\r\n零\u0000𐀀".repeat(900)
        val source=put(files,original);var ref=source;var expected=original
        val random=Random(936)
        repeat(80) {
            val length=expected.codePointCount(0,expected.length)
            val start=random.nextInt(length+1);val end=random.nextInt(start,length+1)
            val inserted=listOf("", "新😀字", "é\n")[it%3]
            val a=expected.offsetByCodePoints(0,start);val b=expected.offsetByCodePoints(0,end)
            expected=expected.substring(0,a)+inserted+expected.substring(b)
            ref=edit.replace(ref,start.toLong(),end.toLong(),inserted)
            assertEquals(expected,read(files,ref))
        }
        assertEquals(original,read(files,source))
        ref=edit.replace(ref,0,expected.codePointCount(0,expected.length).toLong(),"")
        assertEquals("",read(files,ref))
        assertEquals("末尾😀",read(files,edit.replace(ref,0,0,"末尾😀")))
    }

    @Test fun `错误范围编码与被篡改正文不发布新资源`() {
        val root=temporary.newFolder().toPath();val files=StagedContentFiles(root);val edit=TextRangeEdits(files)
        val ref=put(files,"甲😀乙")
        fun batches()=Files.list(root.resolve("batches")).use {it.count()}
        val before=batches()
        for(range in listOf(-1L to 0L,2L to 1L,4L to 4L,0L to 4L)) {
            assertThrows(IllegalArgumentException::class.java){edit.replace(ref,range.first,range.second,"新")}
            assertEquals(before,batches())
        }
        assertThrows(java.nio.charset.MalformedInputException::class.java){edit.replace(ref,0,0,"\uD800")}
        val parts=ref.value.split('/')
        Files.write(root.resolve("batches").resolve(parts[0]).resolve(parts[1]),"甲😀丙".toByteArray())
        assertThrows(java.io.IOException::class.java){edit.replace(ref,0,1,"新")}
        assertEquals(before,batches())
        assertEquals(0L,Files.list(root.resolve("pending")).use {it.count()})
    }

    @Test fun `共同入口保留内部角色范围规则编号和正式旧版`() {
        val store=CardStore(temporary.newFolder().toPath());val drafts=CardDrafts(store);val editor=CardEditor(store)
        val ref=put(store.contents,"前😀后")
        val module=ContentModule("m","经历",listOf(ContentBlock.Text("b",ref)),listOf("标签"),ModuleUse.Always)
        val role=ContentDocument("role",CardKind.CHARACTER,"角色",listOf(module))
        val root=ContentDocument("world",CardKind.WORLD,"世界",internalCharacters=listOf(role))
        var draft=drafts.create(root);drafts.commit("world",draft.version);draft=drafts.begin("world")
        val command=EditorCommand.ReplaceTextRange("m","b",ref,1,2,"新🌍",EditorPosition())
        val edited=editor.apply("world",draft.version,"role",command,ChangeSource.AI)
        assertEquals(root,store.open("world")!!.content)
        assertEquals(root.modules,edited.content.modules)
        val changed=edited.content.internalCharacters.single().modules.single()
        assertEquals(module.copy(blocks=changed.blocks),changed)
        assertEquals("b",changed.blocks.single().id)
        assertEquals("前新🌍后",read(store.contents,(changed.blocks.single() as ContentBlock.Text).content))
        assertThrows(DraftConflict::class.java){editor.apply("world",draft.version,"role",command)}
        assertThrows(IllegalArgumentException::class.java){editor.apply("world",edited.version,"role",command)}
        assertThrows(IllegalArgumentException::class.java){editor.apply("world",edited.version,"world",command)}
        assertEquals(edited,drafts.read("world"))
        assertEquals(ChangeSource.AI,drafts.commit("world",edited.version).source)
        assertEquals("前😀后",read(store.contents,ref))
    }

    @Test fun `编辑范围位置持久化且旧草稿默认从头打开`() {
        val root=temporary.newFolder().toPath();val store=CardStore(root);val drafts=CardDrafts(store)
        val ref=put(store.contents,"甲".repeat(12000))
        val initial=drafts.create(ContentDocument("card",CardKind.CHARACTER,"角色",listOf(ContentModule("m","正文",listOf(ContentBlock.Text("b",ref))))))
        val position=EditorPosition("m","b",32,45,textOffset=4096,textLength=5000)
        val changed=CardEditor(store).apply("card",initial.version,"card",EditorCommand.Position(position))
        assertEquals(position,CardDrafts(CardStore(root)).read("card")!!.position)
        assertThrows(IllegalArgumentException::class.java){CardEditor(store).apply("card",changed.version,"card",EditorCommand.Position(position.copy(textOffset=-1)))}
        assertEquals(changed,drafts.read("card"))
        val name=java.security.MessageDigest.getInstance("SHA-256").digest("card".toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        val file=root.resolve("drafts").resolve(name)
        val json=org.json.JSONObject(Files.readString(file))
        json.getJSONObject("position").remove("textOffset");json.getJSONObject("position").remove("textLength")
        Files.writeString(file,json.toString())
        val legacy=CardDrafts(CardStore(root)).read("card")!!.position
        assertEquals(0L,legacy.textOffset);assertEquals(4096,legacy.textLength)
    }
}
