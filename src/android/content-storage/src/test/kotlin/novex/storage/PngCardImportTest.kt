package novex.storage

import novex.content.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.util.Base64
import java.util.zip.CRC32
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class PngCardImportTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun image(vararg entries:Pair<String,ByteArray>):ByteArray {
        val base=ByteArrayOutputStream().also {ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB),"png",it)}.toByteArray()
        val out=ByteArrayOutputStream();out.write(base,0,base.size-12)
        entries.forEach {(key,body)->
            val bytes=key.toByteArray(Charsets.ISO_8859_1)+byteArrayOf(0)+Base64.getEncoder().encode(body)
            val type="tEXt".toByteArray();val crc=CRC32().apply {update(type);update(bytes)}
            DataOutputStream(out).apply {writeInt(bytes.size);write(type);write(bytes);writeInt(crc.value.toInt())}
        }
        out.write(base,base.size-12,12);return out.toByteArray()
    }
    @Test fun `图片卡原文大于旧块限制仍完整保存并导出还原`() {
        val store=CardStore(temporary.newFolder().toPath())
        val raw=("{\"未知扩展\":\""+"长原文🌊".repeat(1100000)+"\"}").toByteArray()
        val png=image("chara" to raw)
        assertTrue(png.size>16*1024*1024)
        val draft=CardFiles(store).prepare(png.inputStream(),"外部角色",CardKind.CHARACTER)
        assertTrue(store.list().isEmpty());assertEquals("",draft.content.modules.single().name)
        CardDrafts(store).commit(draft.content.id,draft.version)
        val archive=temporary.root.toPath().resolve("png.zip");CardFiles(store).export(draft.content.id,archive)
        val restored=CardStore(temporary.newFolder().toPath())
        val copy=CardFiles(restored).prepare(java.nio.file.Files.newInputStream(archive),"还原",CardKind.WORLD).content
        val text=copy.modules.single().blocks.single() as ContentBlock.Text
        assertArrayEquals(raw,restored.contents.open(text.content).use {it.readBytes()})
        assertArrayEquals(png,restored.contents.open(copy.resources.single().content).use {it.readBytes()})
        assertEquals(copy.resources.single().id,copy.appearance.avatarResourceId)
        assertEquals(CardKind.CHARACTER,copy.kind)
    }
    @Test fun `新版原文优先但原图全部保留并尊重导入目的地`() {
        val store=CardStore(temporary.newFolder().toPath());val png=image("chara" to "旧版".toByteArray(),"ccv3" to "新版完整原文".toByteArray())
        val card=CardFiles(store).prepare(png.inputStream(),"外部世界",CardKind.WORLD).content
        val text=card.modules.single().blocks.single() as ContentBlock.Text
        assertEquals("新版完整原文",store.contents.open(text.content).bufferedReader().use {it.readText()})
        assertEquals(CardKind.WORLD,card.kind)
        assertArrayEquals(png,store.contents.open(card.resources.single().content).use {it.readBytes()})
    }
    private fun wrapped(type:String,payload:ByteArray):ByteArray {
        val base=image();val out=ByteArrayOutputStream();out.write(base,0,base.size-12)
        val typeBytes=type.toByteArray();val crc=CRC32().apply {update(typeBytes);update(payload)}
        DataOutputStream(out).apply {writeInt(payload.size);write(typeBytes);write(payload);writeInt(crc.value.toInt())}
        out.write(base,base.size-12,12);return out.toByteArray()
    }
    private fun compressed(bytes:ByteArray)=ByteArrayOutputStream().also {out->java.util.zip.DeflaterOutputStream(out).use {it.write(bytes)}}.toByteArray()
    @Test fun `压缩及国际化封装保留完整原文和图片`() {
        val raw="{\"name\":\"中文角色🌊\",\"extra\":\"不丢失\"}".toByteArray()
        val encoded=Base64.getEncoder().encode(raw)
        val key="chara".toByteArray()+byteArrayOf(0)
        for((type,payload) in listOf("zTXt" to (key+byteArrayOf(0)+compressed(encoded)),
            "iTXt" to (key+byteArrayOf(0,7)+"zh".toByteArray()+byteArrayOf(0)+"人物".toByteArray()+byteArrayOf(0)+encoded),
            "iTXt" to (key+byteArrayOf(1,0,0,0)+compressed(encoded)))) {
            val store=CardStore(temporary.newFolder().toPath());val png=wrapped(type,payload)
            val draft=CardFiles(store).prepare(png.inputStream(),"图片卡",CardKind.CHARACTER)
            val block=draft.content.modules.single().blocks.single() as ContentBlock.Text
            assertArrayEquals(raw,store.contents.open(block.content).use {it.readBytes()})
            assertArrayEquals(png,store.contents.open(draft.content.resources.single().content).use {it.readBytes()})
        }
    }
    @Test fun `压缩尾部截断多余数据和国际化坏头部均不能成功`() {
        val key="chara".toByteArray()+byteArrayOf(0)
        val zipped=compressed(Base64.getEncoder().encode("原文".toByteArray()))
        for((type,payload) in listOf("zTXt" to (key+byteArrayOf(0)+zipped.copyOf(zipped.size-2)),
            "zTXt" to (key+byteArrayOf(0)+zipped+byteArrayOf(17)),
            "zTXt" to (key+byteArrayOf(1)+zipped),
            "iTXt" to (key+byteArrayOf(2,0,0,0)+zipped),
            "iTXt" to (key+byteArrayOf(1,0)+"missing separator".toByteArray()))) {
            val store=CardStore(temporary.newFolder().toPath())
            assertThrows(Exception::class.java){CardFiles(store).prepare(wrapped(type,payload).inputStream(),"错误",CardKind.CHARACTER)}
            assertTrue(CardDrafts(store).list().isEmpty());assertTrue(store.list().isEmpty())
        }
    }
    @Test fun `残缺校验错误重复原文普通图片和非法编码不产生草稿`() {
        val valid=image("chara" to "原文".toByteArray())
        val corrupt=valid.copyOf().apply {this[29]=(this[29].toInt() xor 1).toByte()}
        for(bytes in listOf(valid.copyOf(valid.size-3),corrupt,image(),image("chara" to byteArrayOf(0xc3.toByte(),0x28)),image("chara" to byteArrayOf(65),"chara" to byteArrayOf(66)))) {
            val store=CardStore(temporary.newFolder().toPath())
            assertThrows(Exception::class.java){CardFiles(store).prepare(bytes.inputStream(),"无效",CardKind.CHARACTER)}
            assertTrue(store.list().isEmpty());assertTrue(CardDrafts(store).list().isEmpty())
        }
    }
}
