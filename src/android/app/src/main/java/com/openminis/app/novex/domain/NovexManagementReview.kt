package com.openminis.app.novex.domain

import org.json.JSONObject

/** A prepared write is reviewed as actual content, not just an opaque proposal identifier. */
fun NovexManagementPlan.reviewText(): String = buildString {
    appendLine(summary)
    impact.forEach { appendLine(it) }
    fun body(label: String, raw: String?) {
        if (raw == null) return
        val text = runCatching {
            val value = JSONObject(raw)
            if (value.optString("kind") == "article") value.optString("text") else value.toString(2)
        }.getOrDefault(raw)
        appendLine("\n$label\n$text")
    }
    fun modules(values: List<NovexModuleDraft>) {
        values.forEachIndexed { index, module -> body("${index + 1}. ${module.name}", module.contentJson) }
    }
    changes.forEach { change -> when (change) {
        is NovexManagedChange.AddModule -> body("新增模块：${change.name}", change.contentJson)
        is NovexManagedChange.UpdateModule -> {
            body("原正文", expectedModuleContents[change.moduleId])
            change.name?.let { appendLine("新名称：$it") }
            body("修改后的正文", change.contentJson)
        }
        is NovexManagedChange.CreateWorld -> { body("世界介绍：${change.name}", change.overview); modules(change.modules) }
        is NovexManagedChange.CreateCharacter -> { body("角色资料：${change.name}", change.profileJson); modules(change.modules) }
        is NovexManagedChange.CreateCharacterVersion -> body("角色版本：${change.label}", change.profileJson)
        is NovexManagedChange.CreateInteractiveFiction -> {
            body("文游介绍：${change.name}", change.summary)
            body("玩家身份", change.playerIdentity)
            modules(change.modules)
        }
        is NovexManagedChange.MoveModule -> appendLine("调整后位于第 ${change.toIndex + 1} 项")
        else -> Unit // Relationship targets and deletion impact are already included in the immutable summary.
    } }
}.trim()
