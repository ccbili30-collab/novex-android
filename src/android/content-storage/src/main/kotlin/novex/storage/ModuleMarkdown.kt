package novex.storage

import novex.content.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Markdown 是编辑/呈现形式；图片落盘仍为卡片资源和稳定图片块，复制不需要重写正文里的资源编号。 */
class ModuleMarkdown(private val store:CardStore) {
    private val image=Regex("^!\\[((?:\\\\.|[^\\]\\\\])*)]\\(card-image:([^#)]+)#([^)]+)\\)$")
    private fun encodeId(id:String)=java.net.URLEncoder.encode(id,"UTF-8").replace("+","%20")
    private fun decodeId(id:String)=java.net.URLDecoder.decode(id,"UTF-8")
    private fun escape(text:String)=text.replace("\\","\\\\").replace("]","\\]").replace("\n","\\n").replace("\r","\\r")
    private fun unescape(text:String):String=buildString {
        var index=0
        while(index<text.length){val c=text[index++];if(c=='\\' && index<text.length){val n=text[index++];append(when(n){'n'->'\n';'r'->'\r';else->n})}else append(c)}
    }
    private fun publish(path:Path):ContentRef {
        val ref=store.contents.allocator()()
        store.contents.receive(listOf(ContentTransfer(ContentRef("markdown"),ref))){Files.newInputStream(path)}
        return ref
    }
    fun compose(module:ContentModule):ContentRef {
        if(module.blocks.size==1 && module.blocks[0] is ContentBlock.Text)return (module.blocks[0] as ContentBlock.Text).content
        val pending=Files.createTempFile(store.directory,"module-markdown-",".tmp")
        try {
            Files.newOutputStream(pending).buffered().use {sink->
                var last=-1
                val output=object:java.io.FilterOutputStream(sink) {
                    override fun write(value:Int){out.write(value);last=value and 255}
                    override fun write(bytes:ByteArray,offset:Int,count:Int){out.write(bytes,offset,count);if(count>0)last=bytes[offset+count-1].toInt() and 255}
                }
                var previousText=false
                module.blocks.forEach {block->
                when(block) {
                    is ContentBlock.Text->{if(previousText)output.write("\n\n".toByteArray());store.contents.open(block.content).use {it.copyTo(output,65536)}}
                    is ContentBlock.Image->{
                        val caption=block.caption?.let {store.contents.open(it).bufferedReader(Charsets.UTF_8).use {r->r.readText()}}.orEmpty()
                        if(last!=-1 && last!=10)output.write(10)
                        output.write("![${escape(caption)}](card-image:${encodeId(block.resourceId)}#${encodeId(block.id)})\n".toByteArray(Charsets.UTF_8))
                    }
                }
                previousText=block is ContentBlock.Text
            }}
            return publish(pending)
        } finally {Files.deleteIfExists(pending)}
    }
    fun replaceRange(ref:ContentRef,start:Long,end:Long,text:String):ContentRef=TextRangeEdits(store.contents).replace(ref,start,end,text)
    fun marker(resourceId:String,blockId:String=UUID.randomUUID().toString())="![](card-image:${encodeId(resourceId)}#${encodeId(blockId)})"
    fun parse(card:ContentDocument,module:ContentModule,ref:ContentRef):List<ContentBlock> {
        val blocks=mutableListOf<ContentBlock>()
        val textIds=module.blocks.filterIsInstance<ContentBlock.Text>().map {it.id}.iterator()
        val used=mutableSetOf<String>()
        val imageOccurrences=mutableMapOf<String,Int>()
        val segment=Files.createTempFile(store.directory,"module-segment-",".tmp")
        var output=Files.newOutputStream(segment).buffered()
        var bytes=0L
        fun finishText() {
            output.close()
            if(bytes>0){val id=if(textIds.hasNext())textIds.next() else UUID.randomUUID().toString();require(used.add(id));blocks+=ContentBlock.Text(id,publish(segment))}
            output=Files.newOutputStream(segment).buffered();bytes=0
        }
        try {
            store.contents.open(ref).bufferedReader(Charsets.UTF_8).use {reader->
                // 保留换行字节；只把完整的本卡图片标记转成图片块。其他 Markdown 原样保存。
                val line=StringBuilder()
                var fence:Char?=null
                var fenceLength=0
                fun accept() {
                    val raw=line.toString();line.setLength(0)
                    val clean=raw.removeSuffix("\n").removeSuffix("\r")
                    val trimmed=clean.trimStart()
                    val fenceChar=trimmed.firstOrNull()?.takeIf {it=='`' || it=='~'}
                    val run=if(fenceChar==null)0 else trimmed.takeWhile {it==fenceChar}.length
                    val wasFenced=fence!=null
                    if(run>=3 && clean.length-trimmed.length<=3) {
                        if(fence==null){fence=fenceChar;fenceLength=run}
                        else if(fence==fenceChar && run>=fenceLength && trimmed.drop(run).isBlank())fence=null
                    }
                    val match=if(wasFenced || fence!=null)null else image.matchEntire(clean)
                    if(match==null){val data=raw.toByteArray(Charsets.UTF_8);output.write(data);bytes+=data.size}
                    else {
                        finishText()
                        val resource=decodeId(match.groupValues[2]);val requestedId=decodeId(match.groupValues[3])
                        require(card.resources.any {it.id==resource && it.mediaType.startsWith("image/")}){"图片不属于当前卡片，请先添加到卡片素材"}
                        val existing=card.modules.flattenModules().flatMap {it.blocks}.find {it.id==requestedId}
                        val occurrence=(imageOccurrences[requestedId]?:0)+1
                        imageOccurrences[requestedId]=occurrence
                        val id=if(requestedId in used || (existing!=null && module.blocks.none {it.id==requestedId && it is ContentBlock.Image}))
                            UUID.nameUUIDFromBytes(("markdown-image:"+module.id+":"+requestedId+":"+occurrence).toByteArray(Charsets.UTF_8)).toString() else requestedId
                        require(used.add(id))
                        val caption=unescape(match.groupValues[1])
                        val captionRef=caption.takeIf {it.isNotEmpty()}?.let {captionText->
                            val target=store.contents.allocator()()
                            store.contents.receive(listOf(ContentTransfer(ContentRef("caption"),target))){ByteArrayInputStream(captionText.toByteArray(Charsets.UTF_8))};target
                        }
                        blocks+=ContentBlock.Image(id,resource,captionRef)
                    }
                }
                val buffer=CharArray(8192)
                while(true){val count=reader.read(buffer);if(count<0)break;require(count>0);for(i in 0 until count){line.append(buffer[i]);if(buffer[i]=='\n')accept()}}
                if(line.isNotEmpty())accept()
            }
            finishText()
            if(blocks.isEmpty())blocks+=ContentBlock.Text(module.blocks.filterIsInstance<ContentBlock.Text>().firstOrNull()?.id?:UUID.randomUUID().toString(),ref)
            return blocks
        } finally {output.close();Files.deleteIfExists(segment)}
    }
}
