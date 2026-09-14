package novex.content

/** 遍历仅作查找/目录，不改变实际父子结构；所有持久变更仍返回完整树。 */
fun List<ContentModule>.flattenModules(): List<ContentModule> = buildList {
    fun visit(modules: List<ContentModule>) { modules.forEach { add(it); visit(it.children) } }
    visit(this@flattenModules)
}
fun List<ContentModule>.findModule(id: String): ContentModule? {
    for (module in this) {
        if (module.id == id) return module
        module.children.findModule(id)?.let { return it }
    }
    return null
}
fun List<ContentModule>.mapModules(transform: (ContentModule) -> ContentModule): List<ContentModule> =
    map { transform(it.copy(children = it.children.mapModules(transform))) }
fun List<ContentModule>.parentOfModule(id: String): String? {
    require(findModule(id) != null) { "目标模块不存在：$id" }
    return flattenModules().firstOrNull { it.children.any { child -> child.id == id } }?.id
}
fun List<ContentModule>.updateModule(id: String, transform: (ContentModule) -> ContentModule): List<ContentModule> {
    require(findModule(id) != null) { "目标模块不存在：$id" }
    fun update(items: List<ContentModule>): List<ContentModule> = items.map {
        if (it.id == id) transform(it).also { replacement -> require(replacement.id == id) { "不能更换模块编号" } }
        else it.copy(children = update(it.children))
    }
    return update(this)
}
fun List<ContentModule>.removeModule(id: String): List<ContentModule> {
    require(findModule(id) != null) { "目标模块不存在：$id" }
    fun remove(items: List<ContentModule>): List<ContentModule> = items.filterNot { it.id == id }.map { it.copy(children = remove(it.children)) }
    return remove(this)
}
fun List<ContentModule>.insertModule(parentId: String?, module: ContentModule, beforeId: String?): List<ContentModule> {
    require(findModule(module.id) == null) { "模块编号已存在" }
    fun insert(siblings: List<ContentModule>): List<ContentModule> = siblings.toMutableList().apply {
        val at = beforeId?.let { id -> indexOfFirst { it.id == id }.also { require(it >= 0) { "定位模块不属于目标父级" } } } ?: size
        add(at, module)
    }
    return if (parentId == null) insert(this) else updateModule(parentId) { it.copy(children = insert(it.children)) }
}
fun List<ContentModule>.placeModule(id: String, parentId: String?, beforeId: String?): List<ContentModule> {
    val module = requireNotNull(findModule(id)) { "目标模块不存在" }
    require(parentId != id && (parentId == null || module.children.findModule(parentId) == null)) { "不能把模块放入自己或后代" }
    if (beforeId == id) {
        require(parentOfModule(id) == parentId) { "不能在其他父级定位到自己" }
        return this
    }
    return removeModule(id).insertModule(parentId, module, beforeId)
}
