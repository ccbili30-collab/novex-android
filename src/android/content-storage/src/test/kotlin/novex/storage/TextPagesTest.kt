package novex.storage

import novex.content.*
import java.io.FilterInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TextPagesTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun put(files:StagedContentFiles,text:String):ContentRef {
        val ref=files.allocator()()
        files.receive(listOf(ContentTransfer(ContentRef("input"),ref))){text.byteInputStream()}
        return ref
    }
    @Test fun `连续分页减少实际读取量且全文完整`() {
        val files=StagedContentFiles(temporary.newFolder().toPath())
        val text="中文😀abc\n".repeat(40000);val ref=put(files,text)
        var bytes=0L;var opened=0;var closed=0
        fun reader()=TextPages {reference,offset->
            opened++
            object:FilterInputStream(files.openAt(reference,offset)) {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    val n=`in`.read(buffer,offset,length);if(n>0)bytes+=n;return n
                }
                override fun close(){closed++;super.close()}
            }
        }
        fun readAll(reuse:Boolean):String {
            val shared=reader();var next:Long?=0;val out=StringBuilder()
            while(next!=null){val page=(if(reuse)shared else reader()).read(ref,next,4096);out.append(page.text);next=page.next}
            return out.toString()
        }
        assertEquals(text,readAll(false));val rescanned=bytes
        bytes=0;assertEquals(text,readAll(true));val indexed=bytes
        println("text_bytes=${text.toByteArray().size} fresh_reader_bytes=$rescanned reused_reader_bytes=$indexed")
        assertTrue("实际流读取量应显著减少",indexed*8<rescanned)
        assertEquals(opened,closed)
    }
    @Test fun `索引前后随机读取不切断表情且不冒称全文件校验`() {
        val files=StagedContentFiles(temporary.newFolder().toPath())
        val text="甲😀é\n".repeat(4000);val ref=put(files,text);val reader=TextPages(files)
        reader.read(ref,0,10000)
        for(start in listOf(4095,4096,8191,13001,0,7000)) {
            val page=reader.read(ref,start.toLong(),13)
            val a=text.offsetByCodePoints(0,start);val b=text.offsetByCodePoints(a,13)
            assertEquals(text.substring(a,b),page.text)
        }
        assertFalse(reader.read(ref,15000,10000).entireFileRead)
        assertTrue(TextPages(files).read(ref,0,30000).entireFileRead)
        assertThrows(IllegalArgumentException::class.java){reader.read(ref,30000,4)}
    }
    @Test fun `分段读取保留原有完整校验及非法字符拒绝`() {
        val root=temporary.newFolder().toPath();val files=StagedContentFiles(root)
        val ref=put(files,"甲".repeat(10000));val reader=TextPages(files)
        reader.read(ref,0,8192)
        val parts=ref.value.split('/')
        val body=root.resolve("batches").resolve(parts[0]).resolve(parts[1])
        val changed=java.nio.file.Files.readAllBytes(body);"乙".toByteArray().copyInto(changed)
        java.nio.file.Files.write(body,changed)
        assertEquals("内容校验失败",assertThrows(java.io.IOException::class.java){TextPages(files).read(ref,0,30000)}.message)
        java.nio.file.Files.write(body,byteArrayOf(1,2))
        assertThrows(java.io.IOException::class.java){reader.read(ref,8192,10)}
        val invalid=files.allocator()()
        files.receive(listOf(ContentTransfer(ContentRef("bad"),invalid))){byteArrayOf(0xc0.toByte(),0x80.toByte()).inputStream()}
        assertThrows(java.nio.charset.MalformedInputException::class.java){TextPages(files).read(invalid,0,10)}
    }
}
