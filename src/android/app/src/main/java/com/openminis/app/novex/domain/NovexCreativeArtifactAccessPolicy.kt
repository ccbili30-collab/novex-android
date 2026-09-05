package com.openminis.app.novex.domain

/** Shared authorization rule for reading or reusing an image artifact in one conversation. */
fun isCreativeArtifactAccessibleToConversation(
    originConversationId: String,
    attachments: List<CreativeArtifactAttachment>,
    configuration: NovexConversationConfigurationSnapshot,
    conversationId: String,
): Boolean {
    if (originConversationId == conversationId) return true
    val authorizedOwners = buildSet {
        configuration.backgroundSettings.forEach { add(it.subject) }
        configuration.managedSubjects.forEach { add(it.subject) }
        configuration.activeInteractiveFiction?.let { fiction ->
            add(NovexContentAddress.interactiveFiction(fiction.projectId))
        }
        val identity = configuration.answerIdentity
        if (identity is AnswerIdentity.CharacterVersion) {
            add(NovexContentAddress.characterVersion(identity.versionId))
        }
    }
    return attachments.any { it.owner in authorizedOwners }
}
