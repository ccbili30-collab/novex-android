package novex.storage

import novex.content.*
import java.io.InputStream

/** 自有图片资源写入。识别字节头和完整转存；像素解码由呈现平台验证，不冒称模型看图。 */
class CardImages(private val store: CardStore) {
    fun replace(cardId:String,expectedVersion:String,moduleId:String,blockId:String,input:InputStream,targetId:String?=null):CardDraft = input.use { source ->
        val resource=receive(source)
        CardEditor(store).apply(cardId,expectedVersion,targetId?:cardId,EditorCommand.ReplaceImage(resource,moduleId,blockId))
    }
    fun add(cardId:String,expectedVersion:String,moduleId:String,afterBlockId:String?,input:InputStream,targetId:String?=null):CardDraft {
        val resource=receive(input)
        return CardEditor(store).apply(cardId,expectedVersion,targetId?:cardId,EditorCommand.AdoptImage(resource,moduleId,afterBlockId))
    }

    fun receive(source:InputStream):CardResource=ImageFiles(store.contents).receive(source)
}
