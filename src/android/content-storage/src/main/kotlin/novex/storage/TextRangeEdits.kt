package novex.storage

import novex.content.ContentRef
import novex.content.ContentTransfer
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** 以码点计数替换半开区间。仅缓冲输入片段和新增文字；当前仍写出完整新正文。 */
internal class TextRangeEdits(private val files:StagedContentFiles) {
    fun replace(reference:ContentRef,start:Long,end:Long,replacement:String):ContentRef {
        require(start>=0 && end>=start){"文字修改范围无效"}
        // 拒绝未完成的 UTF-16 代理项，不以替代字符悄悄改变用户输入。
        val encoded=Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(replacement))
        val inserted=ByteArray(encoded.remaining()).also {encoded.get(it)}
        val destination=files.allocator()()
        files.receive(listOf(ContentTransfer(reference,destination))) {
            val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val reader=InputStreamReader(files.open(reference),decoder).buffered()
            object:InputStream() {
                var position=0L
                var insertionDone=false
                val character=ByteArray(4)
                var pending=ByteArray(0)
                var size=0
                var offset=0
                var ended=false
                fun point():Int {
                    val first=reader.read()
                    if(first<0)return -1
                    if(!Character.isHighSurrogate(first.toChar())) {
                        require(!Character.isLowSurrogate(first.toChar())){"正文字符无效"}
                        return first
                    }
                    val second=reader.read()
                    require(second>=0 && Character.isLowSurrogate(second.toChar())){"正文字符不完整"}
                    return Character.toCodePoint(first.toChar(),second.toChar())
                }
                fun fill():Boolean {
                    while(offset==size) {
                        if(ended)return false
                        if(!insertionDone && position==start) {
                            while(position<end) {
                                require(point()>=0){"文字修改范围超过正文末尾"}
                                position++
                            }
                            insertionDone=true;pending=inserted;size=inserted.size;offset=0
                            if(size>0)return true
                        }
                        val cp=point()
                        if(cp<0) {
                            require(insertionDone){"文字修改范围超过正文末尾"}
                            ended=true;return false
                        }
                        position++
                        pending=character;offset=0
                        size=when {
                            cp<0x80->{character[0]=cp.toByte();1}
                            cp<0x800->{character[0]=(0xc0 or (cp shr 6)).toByte();character[1]=(0x80 or (cp and 63)).toByte();2}
                            cp<0x10000->{character[0]=(0xe0 or (cp shr 12)).toByte();character[1]=(0x80 or ((cp shr 6) and 63)).toByte();character[2]=(0x80 or (cp and 63)).toByte();3}
                            else->{character[0]=(0xf0 or (cp shr 18)).toByte();character[1]=(0x80 or ((cp shr 12) and 63)).toByte();character[2]=(0x80 or ((cp shr 6) and 63)).toByte();character[3]=(0x80 or (cp and 63)).toByte();4}
                        }
                    }
                    return true
                }
                override fun read():Int=if(fill())pending[offset++].toInt() and 255 else -1
                override fun read(buffer:ByteArray,off:Int,len:Int):Int {
                    require(off>=0 && len>=0 && off<=buffer.size-len)
                    if(len==0)return 0
                    var n=0
                    while(n<len && fill()) {
                        val take=minOf(len-n,size-offset)
                        pending.copyInto(buffer,off+n,offset,offset+take)
                        offset+=take;n+=take
                    }
                    return if(n==0)-1 else n
                }
                override fun close()=reader.close()
            }
        }
        return destination
    }
}
