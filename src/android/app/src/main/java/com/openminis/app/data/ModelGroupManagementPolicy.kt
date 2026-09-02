package com.openminis.app.data

import com.openminis.app.data.model.ProviderConfig

/** Explicit alternatives to drag-and-drop on the phone model-groups page. */
internal enum class ModelGroupMove {
    TOP,
    UP,
    DOWN,
    BOTTOM,
}

/** Configuration references that are cleared when their group is deleted. */
internal enum class ModelGroupBinding {
    DEFAULT_PRIMARY,
    DEFAULT_SUB,
    VOICE_INPUT,
    VOICE_OUTPUT,
    VISION,
    AGENT_LOOP,
    IMAGE_GENERATION,
}

internal data class ModelGroupRemovalImpact(
    val groupExists: Boolean,
    val bindings: Set<ModelGroupBinding>,
)

internal fun ProviderConfig.modelGroupRemovalImpact(groupId: String): ModelGroupRemovalImpact {
    val bindings = buildSet {
        if (defaultPrimaryGroupId == groupId) add(ModelGroupBinding.DEFAULT_PRIMARY)
        if (defaultSubGroupId == groupId) add(ModelGroupBinding.DEFAULT_SUB)
        if (voiceInputGroupId == groupId) add(ModelGroupBinding.VOICE_INPUT)
        if (voiceOutputGroupId == groupId) add(ModelGroupBinding.VOICE_OUTPUT)
        if (visionGroupId == groupId) add(ModelGroupBinding.VISION)
        if (groupId in agentLoopGroupIds) add(ModelGroupBinding.AGENT_LOOP)
        if (groupId in imageGenerationGroupIds) add(ModelGroupBinding.IMAGE_GENERATION)
    }
    return ModelGroupRemovalImpact(
        groupExists = modelGroups.any { it.id == groupId },
        bindings = bindings,
    )
}

/** Remove a group without deleting its provider/model entries. */
internal fun ProviderConfig.removeModelGroupAndBindings(groupId: String): ModelGroupRemovalImpact {
    val impact = modelGroupRemovalImpact(groupId)
    modelGroups.removeAll { it.id == groupId }
    if (defaultPrimaryGroupId == groupId) defaultPrimaryGroupId = null
    if (defaultSubGroupId == groupId) defaultSubGroupId = null
    if (voiceInputGroupId == groupId) voiceInputGroupId = null
    if (voiceOutputGroupId == groupId) voiceOutputGroupId = null
    if (visionGroupId == groupId) visionGroupId = null
    agentLoopGroupIds.removeAll { it == groupId }
    imageGenerationGroupIds.removeAll { it == groupId }
    return impact
}

/** Repository ordering contract: unknown ids are ignored and omitted known ids are appended. */
internal fun normalizeModelGroupOrder(current: List<String>, newOrder: List<String>): List<String> {
    val known = current.toSet()
    val seen = LinkedHashSet<String>()
    val reordered = ArrayList<String>(current.size)
    for (id in newOrder) {
        if (id !in known || !seen.add(id)) continue
        reordered.add(id)
    }
    for (id in current) {
        if (seen.add(id)) reordered.add(id)
    }
    return reordered
}

/**
 * Reorder only the groups visible on the ordinary model-groups screen while
 * preserving hidden image-generation groups in their exact list slots.
 */
internal fun moveManagedModelGroup(
    allGroupIds: List<String>,
    managedGroupIds: List<String>,
    groupId: String,
    move: ModelGroupMove,
): List<String> {
    val managedOrder = managedOrderPresentIn(allGroupIds, managedGroupIds)
    val fromIndex = managedOrder.indexOf(groupId)
    if (fromIndex < 0) return allGroupIds
    val toIndex = when (move) {
        ModelGroupMove.TOP -> 0
        ModelGroupMove.UP -> (fromIndex - 1).coerceAtLeast(0)
        ModelGroupMove.DOWN -> (fromIndex + 1).coerceAtMost(managedOrder.lastIndex)
        ModelGroupMove.BOTTOM -> managedOrder.lastIndex
    }
    if (fromIndex == toIndex) return allGroupIds
    val reordered = managedOrder.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
    return mergeManagedOrder(allGroupIds, reordered)
}

internal fun reorderManagedModelGroups(
    allGroupIds: List<String>,
    managedGroupIds: List<String>,
    fromGroupId: String,
    toGroupId: String,
): List<String> {
    val managedOrder = managedOrderPresentIn(allGroupIds, managedGroupIds)
    val fromIndex = managedOrder.indexOf(fromGroupId)
    val toIndex = managedOrder.indexOf(toGroupId)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return allGroupIds
    val reordered = managedOrder.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
    return mergeManagedOrder(allGroupIds, reordered)
}

private fun managedOrderPresentIn(allGroupIds: List<String>, managedGroupIds: List<String>): List<String> {
    val managed = managedGroupIds.toSet()
    return allGroupIds.filter { it in managed }.distinct()
}

private fun mergeManagedOrder(allGroupIds: List<String>, reorderedManagedIds: List<String>): List<String> {
    val managed = reorderedManagedIds.toSet()
    val replacements = reorderedManagedIds.iterator()
    return allGroupIds.map { id ->
        if (id in managed && replacements.hasNext()) replacements.next() else id
    }
}
