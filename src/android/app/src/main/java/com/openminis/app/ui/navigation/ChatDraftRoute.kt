package com.openminis.app.ui.navigation

import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexContentKind
import com.openminis.app.novex.domain.ManagedAccess
import com.openminis.app.novex.domain.NovexConversationCommand
import com.openminis.app.novex.domain.NovexConversationConfiguration
import com.openminis.app.novex.domain.NovexConversationConfigurationSnapshot

/** Context that must survive when a conversation creates another draft. */
internal data class ChatDraftContext(
    val worldId: String? = null,
    val characterId: String? = null,
    val characterVersionId: String? = null,
    val personaId: String? = null,
    val managedSubjects: List<NovexContentAddress> = emptyList(),
)

internal fun buildChatDraftId(
    draftId: String,
    context: ChatDraftContext = ChatDraftContext(),
): String = buildString {
    append("__new__").append(draftId)
    when {
        !context.characterVersionId.isNullOrBlank() -> {
            append("__version__").append(context.characterVersionId)
            context.worldId?.takeIf(String::isNotBlank)?.let {
                append("__world__").append(it)
            }
        }
        !context.characterId.isNullOrBlank() -> append("__char__").append(context.characterId)
        !context.worldId.isNullOrBlank() -> append("__world__").append(context.worldId)
    }
    context.personaId?.takeIf(String::isNotBlank)?.let {
        append("__persona__").append(it)
    }
    context.managedSubjects.distinct().forEach { subject ->
        append("__").append(subject.kind.draftMarker).append("__").append(subject.id)
    }
}

internal fun managedSubjectsFromChatDraftId(draftId: String): List<NovexContentAddress> =
    listOfNotNull(
        draftId.draftMarkerValue(NovexContentKind.WORLD.draftMarker)
            ?.let(NovexContentAddress::world),
        draftId.draftMarkerValue(NovexContentKind.CHARACTER_VERSION.draftMarker)
            ?.let(NovexContentAddress::characterVersion),
        draftId.draftMarkerValue(NovexContentKind.INTERACTIVE_FICTION.draftMarker)
            ?.let(NovexContentAddress::interactiveFiction),
        draftId.draftMarkerValue(NovexContentKind.CREATIVE_ARTIFACT.draftMarker)
            ?.let(NovexContentAddress::creativeArtifact),
    )

internal fun applyDraftManagedSubjects(
    draftId: String,
    configuration: NovexConversationConfigurationSnapshot,
): NovexConversationConfigurationSnapshot = managedSubjectsFromChatDraftId(draftId).fold(
    NovexConversationConfiguration.open(configuration),
) { configured, subject ->
    configured.apply(NovexConversationCommand.MountSubject(subject, ManagedAccess.EDIT))
}.snapshot

internal fun String.draftMarkerValue(name: String): String? =
    substringAfter("__${name}__", "").substringBefore("__").takeIf(String::isNotEmpty)

private val NovexContentKind.draftMarker: String
    get() = when (this) {
        NovexContentKind.WORLD -> "managedWorld"
        NovexContentKind.CHARACTER_VERSION -> "managedVersion"
        NovexContentKind.INTERACTIVE_FICTION -> "managedGame"
        NovexContentKind.CREATIVE_ARTIFACT -> "managedArtifact"
    }
