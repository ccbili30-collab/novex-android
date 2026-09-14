package novex.runtime

import novex.content.*
import novex.storage.*
import org.json.JSONObject
import java.io.InputStream
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** 对话图片自行持有字节；转存到卡片后卡片持有另一份独立资源。 */
data class ConversationImage(val id:String,val name:String,val resource:CardResource,val selected:Boolean)
class ConversationImages(private val root:Path) {
    private fun directory(chat:String):Path {
        require(chat.isNotBlank())
        val key=MessageDigest.getInstance("SHA-256").digest(chat.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        return root.resolve(key).also {Files.createDirectories(it)}
    }
    private fun contents(chat:String)=StagedContentFiles(directory(chat).resolve("contents"))
    fun add(chat:String,name:String,input:InputStream):ConversationImage {
        val resource=ImageFiles(contents(chat)).receive(input)
        return ConversationImage(resource.id,name.ifBlank {"对话图片"},resource,false).also {write(chat,it)}
    }
    fun list(chat:String):List<ConversationImage> = Files.list(directory(chat)).use {paths->
        paths.filter {it.fileName.toString().startsWith("image-") && it.fileName.toString().endsWith(".json")}
            .map {readFile(it)}.collect(java.util.stream.Collectors.toList())
    }.sortedBy {it.id}
    fun read(chat:String,id:String):ConversationImage {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return readFile(directory(chat).resolve("image-$id.json")).also {require(it.id==id)}
    }
    fun select(chat:String,id:String,selected:Boolean)=write(chat,read(chat,id).copy(selected=selected))
    fun open(chat:String,id:String):InputStream=contents(chat).open(read(chat,id).resource.content)
    fun thumbnail(chat:String,id:String):ContentRef=read(chat,id).resource.content
    fun modelImage(chat:String,id:String):novex.model.WireImage {
        val value=read(chat,id)
        return novex.model.WireImage(value.resource.mediaType,open(chat,id).use {java.util.Base64.getEncoder().encodeToString(it.readBytes())})
    }
    private fun readFile(path:Path):ConversationImage {
        val value=JSONObject(Utf8Files.read(path))
        return ConversationImage(value.getString("id"),value.getString("name"),CardResource(value.getString("id"),ContentRef(value.getString("content")),value.getString("mediaType")),value.getBoolean("selected"))
    }
    private fun write(chat:String,image:ConversationImage) {
        val value=JSONObject().put("id",image.id).put("name",image.name).put("content",image.resource.content.value).put("mediaType",image.resource.mediaType).put("selected",image.selected)
        val dir=directory(chat);val temp=dir.resolve("pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use {channel->
                val buffer=ByteBuffer.wrap(value.toString().toByteArray(Charsets.UTF_8));while(buffer.hasRemaining())channel.write(buffer);channel.force(true)
            }
            Files.move(temp,dir.resolve("image-${image.id}.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally {Files.deleteIfExists(temp)}
    }
}
