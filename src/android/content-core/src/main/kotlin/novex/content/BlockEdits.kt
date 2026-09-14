package novex.content

/** 可在一个候选变更内移动多块；任何一项失败都不返回部分修改后的文档。 */
sealed interface BlockEdit {
    data class Insert(val moduleId: String, val block: ContentBlock, val beforeBlockId: String? = null) : BlockEdit
    data class Replace(val blockId: String, val replacement: ContentBlock) : BlockEdit
    data class Remove(val blockId: String) : BlockEdit
    data class Move(val blockId: String, val moduleId: String, val beforeBlockId: String? = null) : BlockEdit
}

internal fun editBlocks(document: ContentDocument, edits: List<BlockEdit>): ContentDocument {
    var tree = document.modules
    val modules = tree.flattenModules().toMutableList()
    fun moduleIndex(id: String): Int = modules.indexOfFirst { it.id == id }.also {
        require(it >= 0) { "目标模块不存在：$id" }
    }
    fun locate(id: String): Pair<Int, Int> {
        modules.forEachIndexed { moduleIndex, module ->
            val blockIndex = module.blocks.indexOfFirst { it.id == id }
            if (blockIndex >= 0) return moduleIndex to blockIndex
        }
        error("目标内容块不存在：$id")
    }
    fun remove(id: String): ContentBlock {
        val (moduleIndex, blockIndex) = locate(id)
        val blocks = modules[moduleIndex].blocks.toMutableList()
        val removed = blocks.removeAt(blockIndex)
        modules[moduleIndex] = modules[moduleIndex].copy(blocks = blocks.toList())
        return removed
    }
    fun insert(moduleId: String, block: ContentBlock, beforeId: String?) {
        require(modules.none { m -> m.blocks.any { it.id == block.id } }) { "内容块编号已存在" }
        val index = moduleIndex(moduleId)
        val blocks = modules[index].blocks.toMutableList()
        val at = beforeId?.let { id -> blocks.indexOfFirst { it.id == id }.also {
            require(it >= 0) { "定位内容块不在目标模块中" }
        } } ?: blocks.size
        blocks.add(at, block)
        modules[index] = modules[index].copy(blocks = blocks.toList())
    }
    edits.forEach { edit ->
        when (edit) {
            is BlockEdit.Insert -> insert(edit.moduleId, edit.block, edit.beforeBlockId)
            is BlockEdit.Remove -> remove(edit.blockId)
            is BlockEdit.Replace -> {
                require(edit.replacement.id == edit.blockId) { "替换内容不能更换稳定编号" }
                val (moduleIndex, blockIndex) = locate(edit.blockId)
                val blocks = modules[moduleIndex].blocks.toMutableList()
                blocks[blockIndex] = edit.replacement
                modules[moduleIndex] = modules[moduleIndex].copy(blocks = blocks.toList())
            }
            is BlockEdit.Move -> {
                if (edit.blockId == edit.beforeBlockId) {
                    val (source, _) = locate(edit.blockId)
                    require(modules[source].id == edit.moduleId) { "不能在其他模块定位到自己" }
                } else insert(edit.moduleId, remove(edit.blockId), edit.beforeBlockId)
            }
        }
    }
    return document.copy(modules = tree.mapModules { original -> original.copy(blocks = modules.single { it.id == original.id }.blocks) }).validate()
}
