package com.openminis.app.novex.domain

/** A focused editor of existing world references, shared by native UI and agent operations. */
class NovexGameWorldbooks(
    private val workspace: NovexWorkspace,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) {
    suspend fun load(projectId: String): List<NovexCardReference> =
        workspace.referencesFrom(NovexContentAddress.interactiveFiction(projectId)).filter(::isWorldbook)

    suspend fun save(projectId: String, expected: List<NovexCardReference>, selected: List<NovexCardReference>): List<NovexCardReference> {
        val owner = NovexContentAddress.interactiveFiction(projectId)
        require(selected.map { it.id }.distinct().size == selected.size) { "重复的世界书引用" }
        require(selected.map { it.target }.distinct().size == selected.size) { "同一世界书范围只需选择一次" }
        require(selected.all { it.source == owner && isWorldbook(it) }) { "只能在这里调整这张文游使用的世界书" }
        transaction {
            require(workspace.interactiveFiction(projectId) != null) { "文游已不存在" }
            require(load(projectId) == expected) { "世界书选择已在别处更新，请重新打开后调整；本次选择尚未保存" }
            val visibleWorlds = workspace.worlds().mapTo(mutableSetOf()) { it.world.id }
            selected.forEach { reference ->
                val old = expected.singleOrNull { it.id == reference.id }
                if (old == null || old.target != reference.target || reference.enabled) {
                    require(reference.target.subject.id in visibleWorlds) { "请选择世界库里已保存的世界书" }
                    require(workspace.referenceStatus(reference.target) == NovexReferenceTargetStatus.AVAILABLE) { "世界书或选中的模块已不存在，请重新选择" }
                }
            }
            expected.filter { previous -> selected.none { it.id == previous.id } }.forEach {
                workspace.apply(NovexCommand.RemoveCardReference(it.id, owner))
            }
            selected.forEach { reference ->
                if (reference !in expected) workspace.apply(NovexCommand.PutCardReference(reference))
            }
            require(load(projectId).associateBy { it.id } == selected.associateBy { it.id }) { "世界书选择未完整保存" }
        }
        return load(projectId)
    }

    companion object {
        fun isWorldbook(reference: NovexCardReference): Boolean = reference.sourceModuleId == null &&
            reference.target.subject.kind == NovexContentKind.WORLD &&
            reference.purpose in setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES)
    }
}
