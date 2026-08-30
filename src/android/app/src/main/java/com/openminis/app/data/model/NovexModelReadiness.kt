package com.openminis.app.data.model

/** True only when a configured model can actually be selected for a chat. */
fun ProviderConfig.hasUsableNovexModel(): Boolean {
    val enabledProviderIds = instances.asSequence()
        .filter { it.isEnabled }
        .map { it.id }
        .toSet()
    if (enabledProviderIds.isEmpty()) return false

    val usableEntryIds = modelEntries.asSequence()
        .filter { !it.isHidden && it.providerInstanceId in enabledProviderIds }
        .map { it.id }
        .toSet()
    if (usableEntryIds.isEmpty()) return false

    return modelGroups.any { group ->
        group.memberEntryIds.any { it in usableEntryIds }
    }
}
