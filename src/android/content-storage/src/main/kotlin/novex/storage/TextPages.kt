package novex.storage

import novex.content.ContentRef
import java.io.InputStream
import java.io.PushbackReader
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.TreeMap

/** entireFileRead 仅在本次确实从零读取并完成整文件校验时为真。 */
data class TextPage(val text:String,val start:Long,val next:Long?,val entireFileRead:Boolean)

/** 不可变正文的有界稀疏位置索引；不保存正文副本、不持有跨调用文件句柄。 */
class TextPages internal constructor(private val open:(ContentRef,Long)->InputStream) {
    constructor(contents:StagedContentFiles):this(contents::openAt)
    private val positions=LinkedHashMap<ContentRef,TreeMap<Long,Long>>(8,0.75f,true)
    @Synchronized fun read(reference:ContentRef,start:Long,count:Int):TextPage {
        require(start>=0 && count>0){"分页位置或长度无效"}
        val index=positions.getOrPut(reference){TreeMap<Long,Long>().apply {put(0,0)}}
        while(positions.size>8)positions.remove(positions.keys.first())
        val checkpoint=index.floorEntry(start)
        var cursor=checkpoint.key
        var bytePosition=checkpoint.value
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return PushbackReader(InputStreamReader(open(reference,bytePosition),decoder).buffered(),1).use {reader->
            fun point():Int {
                val first=reader.read();if(first<0)return -1
                val cp=if(Character.isHighSurrogate(first.toChar())) {
                    val second=reader.read();require(second>=0 && Character.isLowSurrogate(second.toChar())){"正文字符不完整"}
                    Character.toCodePoint(first.toChar(),second.toChar())
                } else {require(!Character.isLowSurrogate(first.toChar())){"正文字符无效"};first}
                bytePosition+=when {cp<0x80->1;cp<0x800->2;cp<0x10000->3;else->4}
                cursor++
                if(cursor%4096L==0L) {
                    index[cursor]=bytePosition
                    // 元数据缓存上限，不限制正文容量；始终保留从头重新建立索引的入口。
                    if(index.size>2048)index.remove(index.higherKey(0L))
                }
                return cp
            }
            while(cursor<start)require(point()>=0){"分页位置超过正文末尾"}
            val output=StringBuilder();var read=0
            while(read<count) {
                val cp=point()
                if(cp<0)return@use TextPage(output.toString(),start,null,checkpoint.value==0L)
                output.appendCodePoint(cp);read++
            }
            val hasMore=point()>=0
            TextPage(output.toString(),start,if(hasMore)Math.addExact(start,read.toLong()) else null,!hasMore && checkpoint.value==0L)
        }
    }
}
