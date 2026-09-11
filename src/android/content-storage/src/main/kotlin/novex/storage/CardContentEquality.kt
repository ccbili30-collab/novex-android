package novex.storage

import novex.content.*
import java.security.MessageDigest

/** 忽略内容引用的重新分配和编辑位置，比较真实结构与字节；长正文按流计算，不整卡装入内存。 */
class CardContentEquality(private val store:CardStore) {
    private val files=store.contents
    fun same(left:ContentDocument,right:ContentDocument):Boolean {
        if(left==right)return true
        val fingerprints=mutableMapOf<ContentRef,ContentRef>()
        fun digest(ref:ContentRef):ContentRef=fingerprints.getOrPut(ref) {
            val hash=MessageDigest.getInstance("SHA-256")
            files.open(ref).use { input ->
                val buffer=ByteArray(65536)
                while(true){val count=input.read(buffer);if(count<0)break;require(count>0);hash.update(buffer,0,count)}
            }
            ContentRef(hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)})
        }
        fun canonical(card:ContentDocument):ContentDocument=card.copy(
            // 文字分块是旧编辑形式；可见正文相同不能因为重新分块就要求用户保存。
            modules=card.modules.mapModules {module->module.copy(blocks=listOf(ContentBlock.Text(module.id+"-body",digest(ModuleMarkdown(store).compose(module)))))},
            resources=card.resources.map {it.copy(content=digest(it.content))},
            extensions=card.extensions.mapValues {digest(it.value)},
            internalCharacters=card.internalCharacters.map(::canonical))
        return canonical(left)==canonical(right)
    }
}
