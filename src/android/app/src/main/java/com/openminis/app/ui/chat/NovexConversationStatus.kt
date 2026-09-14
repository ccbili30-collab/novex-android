package com.openminis.app.ui.chat

import com.openminis.app.novex.domain.*

data class NovexConversationStatus(
    val answer: String = "Nova（诺瓦）",
    val game: String? = null,
    val sources: Int = 0,
    val mode: NovexExecutionMode = NovexExecutionMode.DEFAULT,
    val editable: Boolean = true,
) {
    companion object {
        fun read(configuration: NovexConversationConfigurationSnapshot): NovexConversationStatus {
            com.openminis.app.cards.CardBinding.decode(configuration.cardBindingJson)?.let {binding->
                return NovexConversationStatus(if(binding.primary!=null)"已采用卡片" else "Nova（诺瓦）",null,
                    (listOfNotNull(binding.primary)+binding.backgrounds).distinct().size,configuration.executionMode,configuration.unreadableConfiguration==null)
            }
            val sources = NovexEffectiveFrozenContext.sources(configuration)
            val answer = when (val identity = configuration.answerIdentity) {
                AnswerIdentity.Nova -> "Nova（诺瓦）"
                is AnswerIdentity.PersonaPreset -> identity.label
                is AnswerIdentity.CharacterVersion -> sources.firstOrNull { it.actorVersionId == identity.versionId }
                    ?.candidates?.firstOrNull { it.kind == ContextSourceKind.ANSWER_IDENTITY }?.label ?: "已采用的角色"
            }
            return NovexConversationStatus(answer, configuration.activeInteractiveFiction?.title,
                sources.filter { source -> source.candidates.any { it.content.isNotBlank() } }.distinctBy { it.target }.size,
                configuration.executionMode, configuration.unreadableConfiguration==null)
        }
    }
}
