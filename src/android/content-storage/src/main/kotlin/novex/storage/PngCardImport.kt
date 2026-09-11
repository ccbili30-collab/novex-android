package novex.storage

import novex.content.*
import java.io.*
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/** 图片卡兼容边界：只抽取原文和持有原图片，不向核心引入外部字段模型。 */
internal object PngCardImport {
    private val signature=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,13,10,26,10)
    private fun skipTerminated(input:InputStream) {
        while(true) {
            val value=input.read();require(value>=0){"图片国际化文字头部残缺"}
            if(value==0)return
        }
    }
    private class VerifiedInflater(input:InputStream):InflaterInputStream(input) {
        fun verifyEnd(){require(inf.finished() && inf.remaining==0){"图片压缩数据尾部无效"}}
    }
    fun prepare(original:ContentRef,files:StagedContentFiles,kind:CardKind,name:String):ContentDocument {
        val texts=mutableMapOf<String,ContentRef>()
        files.open(original).use {raw->
            val input=DataInputStream(BufferedInputStream(raw,65536))
            val header=ByteArray(8);input.readFully(header)
            require(header.contentEquals(signature)){"图片卡头部不完整"}
            var first=true;var imageData=false
            while(true) {
                val length=input.readInt().toLong() and 0xffffffffL
                require(length<=Int.MAX_VALUE){"图片数据块长度无效"}
                val typeBytes=ByteArray(4);input.readFully(typeBytes)
                require(typeBytes.all {(it.toInt() and 255) in 65..90 || (it.toInt() and 255) in 97..122}){"图片数据块类型无效"}
                val type=String(typeBytes,Charsets.US_ASCII)
                if(first)require(type=="IHDR" && length==13L){"图片缺少有效头部"}
                else require(type!="IHDR"){"图片头部重复"}
                first=false
                val crc=CRC32();crc.update(typeBytes)
                val chunk=object:InputStream() {
                    var remaining=length
                    override fun read():Int {
                        if(remaining==0L)return -1
                        val value=input.read();if(value<0)throw EOFException("图片数据块残缺")
                        remaining--;crc.update(value);return value
                    }
                    override fun read(b:ByteArray,off:Int,len:Int):Int {
                        if(len==0)return 0
                        if(remaining==0L)return -1
                        val count=input.read(b,off,minOf(len.toLong(),remaining).toInt())
                        if(count<0)throw EOFException("图片数据块残缺")
                        remaining-=count;crc.update(b,off,count);return count
                    }
                    override fun close() {} // 分块读取器不关闭整个图片流。
                }
                if(type in setOf("tEXt","zTXt","iTXt")) {
                    val keyword=ByteArrayOutputStream();var terminated=false
                    repeat(80) {
                        if(!terminated) {
                            val value=chunk.read();require(value>=0){"图片文字块缺少分隔符"}
                            if(value==0)terminated=true else keyword.write(value)
                        }
                    }
                    require(terminated && keyword.size() in 1..79){"图片文字块名称无效"}
                    val key=keyword.toString("ISO-8859-1")
                    if(key=="chara" || key=="ccv3") {
                        require(key !in texts){"图片包含重复角色数据，未导入"}
                        val compressed=when(type) {
                            "zTXt"->{require(chunk.read()==0){"图片文字压缩方法不支持"};true}
                            "iTXt"->{
                                val flag=chunk.read();require(flag in 0..1){"图片文字压缩标志无效"}
                                val method=chunk.read()
                                require(method>=0 && (flag==0 || method==0)){"图片文字压缩方法不支持"}
                                skipTerminated(chunk);skipTerminated(chunk)
                                flag==1
                            }
                            else->false
                        }
                        val inflater=if(compressed)VerifiedInflater(chunk) else null
                        val payload=inflater?:chunk
                        val ref=files.allocator()()
                        try {
                            // 解码器可能在填充符处结束；继续读原流才能核验压缩尾部。
                            val unclosed=object:FilterInputStream(payload){override fun close() {}}
                            files.receive(listOf(ContentTransfer(ContentRef("png-text"),ref))) {
                                Base64.getDecoder().wrap(unclosed)
                            }
                            require(payload.read()==-1){"图片角色数据尾部无效"}
                            inflater?.verifyEnd()
                            require(chunk.remaining==0L){"图片角色数据尾部无效"}
                        } finally {inflater?.close()}
                        texts[key]=ref
                    }
                }
                val buffer=ByteArray(65536)
                while(chunk.read(buffer)>=0)Unit
                require(input.readInt().toLong() and 0xffffffffL == crc.value){"图片数据校验失败，未导入"}
                if(type=="IDAT")imageData=true
                if(type=="IEND") {
                    require(length==0L && imageData){"图片缺少完整图像数据"}
                    require(input.read()==-1){"图片结束后存在额外数据，未导入"}
                    break
                }
            }
        }
        val text=requireNotNull(texts["ccv3"]?:texts["chara"]){"这张图片未包含可读取的角色原文，目前支持 chara／ccv3 文字数据块"}
        val card=RawTextImport.prepareUtf8(text,files,kind,name)
        val resource=CardResource(UUID.randomUUID().toString(),original,"image/png")
        return card.copy(resources=listOf(resource),appearance=CardAppearance(avatarResourceId=resource.id),
            extensions=card.extensions+mapOf("novex.import.png" to original)).validate()
    }
}
