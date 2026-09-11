package novex.content

/** 定位独立作品或世界内部角色，禁止通过目标编号跳到其他作品。 */
object ContentTargets {
    fun find(root: ContentDocument, targetId: String): ContentDocument =
        if (root.id == targetId) root else requireNotNull(root.internalCharacters.find { it.id == targetId }) { "目标卡片不在当前作品中" }

    fun replace(root: ContentDocument, target: ContentDocument): ContentDocument {
        require(find(root, target.id).kind == target.kind) { "编辑不能改变卡类" }
        return (if (root.id == target.id) target else root.copy(internalCharacters = root.internalCharacters.map {
            if (it.id == target.id) target else it
        })).validate()
    }
}
