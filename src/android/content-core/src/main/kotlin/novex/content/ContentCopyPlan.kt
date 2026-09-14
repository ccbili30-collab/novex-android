package novex.content

data class ContentTransfer(val source: ContentRef, val destination: ContentRef)

/**
 * 这是待执行复制，不是已经保存的卡片。
 * 接入存储时须先完成全部内容转存，再原子发布候选文档；失败不能归库。
 */
data class ContentCopyPlan(
    val candidate: ContentDocument,
    val objectIds: Map<String, String>,
    val transfers: List<ContentTransfer>,
)

object ContentCopies {
    /**
     * 两卡及世界内部角色共用同一结构复制。编号生成器只分配编号，不写内容。
     * 原始扩展保持原件引用语义；未知扩展内部的字段不擅自解释或改写。
     */
    fun plan(
        source: ContentDocument,
        nextObjectId: () -> String,
        nextContentRef: () -> ContentRef,
    ): ContentCopyPlan = transferPlan(source,nextObjectId,nextContentRef,false)

    /** 同一对象迁往独立存储位置：保留对象编号，仅重定位正文和资源。不会发布或覆盖正式作品。 */
    fun relocate(source:ContentDocument,nextContentRef:()->ContentRef):ContentCopyPlan =
        transferPlan(source,{error("重定位不分配对象编号")},nextContentRef,true)

    private fun transferPlan(source:ContentDocument,nextObjectId:()->String,nextContentRef:()->ContentRef,preserveIds:Boolean):ContentCopyPlan {
        source.validate()
        val sourceIds = linkedSetOf<String>()
        val sourceRefs = linkedSetOf<ContentRef>()
        fun collect(card: ContentDocument) {
            sourceIds += card.id
            card.resources.forEach { sourceIds += it.id; sourceRefs += it.content }
            card.extensions.values.forEach { sourceRefs += it }
            card.modules.flattenModules().forEach { module ->
                sourceIds += module.id
                module.blocks.forEach { block ->
                    sourceIds += block.id
                    when (block) {
                        is ContentBlock.Text -> sourceRefs += block.content
                        is ContentBlock.Image -> block.caption?.let { sourceRefs += it }
                    }
                }
            }
            card.internalCharacters.forEach(::collect)
        }
        collect(source)
        val allocatedIds = mutableSetOf<String>()
        val ids = sourceIds.associateWith { original ->
            if(preserveIds)original else nextObjectId().also { generated ->
                require(generated.isNotBlank()) { "复制目标编号不能为空" }
                require(generated !in sourceIds && allocatedIds.add(generated)) { "复制目标编号冲突" }
            }
        }
        val allocatedRefs = mutableSetOf<ContentRef>()
        val refs = sourceRefs.associateWith {
            nextContentRef().also { generated ->
                require(generated !in sourceRefs && allocatedRefs.add(generated)) { "复制内容引用冲突" }
            }
        }
        fun copy(card: ContentDocument): ContentDocument = card.copy(
            id = ids.getValue(card.id),
            modules = card.modules.mapModules { module ->
                module.copy(id = ids.getValue(module.id), characterIds = module.characterIds.map(ids::getValue), blocks = module.blocks.map { block ->
                    when (block) {
                        is ContentBlock.Text -> block.copy(id = ids.getValue(block.id), content = refs.getValue(block.content))
                        is ContentBlock.Image -> block.copy(
                            id = ids.getValue(block.id), resourceId = ids.getValue(block.resourceId),
                            caption = block.caption?.let(refs::getValue),
                        )
                    }
                })
            },
            resources = card.resources.map { it.copy(id = ids.getValue(it.id), content = refs.getValue(it.content)) },
            internalCharacters = card.internalCharacters.map(::copy),
            extensions = card.extensions.mapValues { refs.getValue(it.value) },
            appearance = CardAppearance(
                avatarResourceId = card.appearance.avatarResourceId?.let(ids::getValue),
                coverResourceId = card.appearance.coverResourceId?.let(ids::getValue),
                readingLayout = card.appearance.readingLayout,
            ),
        )
        return ContentCopyPlan(
            candidate = copy(source).validate(),
            objectIds = ids,
            transfers = refs.map { (old, new) -> ContentTransfer(old, new) },
        )
    }
}
