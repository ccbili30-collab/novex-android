package novex.content

import java.util.UUID

/** 文件夹的自身正文成为真实子模块；编号确定且只移动内容块，不复制正文或资源。 */
object FolderContents {
    fun organize(card:ContentDocument):ContentDocument {
        val used=(listOf(card)+card.internalCharacters).flatMap {part->
            listOf(part.id)+part.resources.map {it.id}+part.modules.flattenModules().flatMap {listOf(it.id)+it.blocks.map {block->block.id}}
        }.toMutableSet()
        fun childId(parent:String):String {
            var salt=0
            while(true) {
                val id=UUID.nameUUIDFromBytes("folder-content:${card.id}:$parent:${salt++}".toByteArray(Charsets.UTF_8)).toString()
                if(used.add(id))return id
            }
        }
        fun visit(items:List<ContentModule>):List<ContentModule> = items.map {original->
            val children=visit(original.children)
            if(children.isNotEmpty() && original.blocks.isNotEmpty()) {
                // 正文保留原编号及采用规则，使已有对话对模块的选择继续指向同一内容。
                val body=original.copy(children=emptyList(),layout=ModuleLayout.VERTICAL,characterIds=emptyList())
                ContentModule(childId(original.id),original.name,emptyList(),children=listOf(body)+children,
                    layout=original.layout,characterIds=original.characterIds)
            } else original.copy(children=children)
        }
        return card.copy(modules=visit(card.modules)).validate()
    }
    fun tree(card:ContentDocument):ContentDocument=organize(card).copy(internalCharacters=card.internalCharacters.map(::tree))
}
