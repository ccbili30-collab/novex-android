package com.openminis.app.ui.novex

/** Business vocabulary supplied to the shared content-page skeleton. */
internal enum class NovexContentPageKind {
    WORLD,
    CHARACTER,
    INTERACTIVE_FICTION,
}

internal data class NovexContentPageCapabilities(
    val display: Boolean,
    val edit: Boolean,
    val preview: Boolean,
    val optionalArtwork: Boolean,
    val orderedModules: Boolean,
)

internal data class NovexContentPageContract(
    val singularLabel: String,
    val capabilities: NovexContentPageCapabilities,
)

private val sharedContentCapabilities = NovexContentPageCapabilities(
    display = true,
    edit = true,
    preview = true,
    optionalArtwork = true,
    orderedModules = true,
)

internal fun novexContentPageContract(kind: NovexContentPageKind): NovexContentPageContract =
    NovexContentPageContract(
        singularLabel = when (kind) {
            NovexContentPageKind.WORLD -> "世界"
            NovexContentPageKind.CHARACTER -> "角色"
            NovexContentPageKind.INTERACTIVE_FICTION -> "文游"
        },
        capabilities = sharedContentCapabilities,
    )
