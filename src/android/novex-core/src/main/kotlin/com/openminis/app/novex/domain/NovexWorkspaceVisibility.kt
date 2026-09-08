package com.openminis.app.novex.domain

/** User-visible imported originals govern access to their parsed copies as well. */
class NovexWorkspaceVisibility(private val store: NovexConversationWorkspaceStore,
    private val visibleImports: Set<String>) : NovexConversationWorkspaceStore by store {
    private fun visible(entry: NovexWorkspaceEntry): Boolean = when {
        entry.workspaceRef.area == NovexWorkspaceArea.SOURCES && entry.workspaceRef.relativePath.startsWith("imports/") ->
            entry.workspaceRef.value in visibleImports
        entry.workspaceRef.relativePath.startsWith(NovexWorkspaceBrowser.PARSED_PREFIX) ->
            entry.provenance.sourceRefs.any { it.value in visibleImports }
        else -> true
    }
    override fun inspect(scope: NovexConversationWorkspaceScope) = store.inspect(scope).let {
        it.copy(entries = it.entries.filter(::visible))
    }
    override fun find(scope: NovexConversationWorkspaceScope, ref: NovexWorkspaceFileRef) = store.find(scope, ref)?.takeIf(::visible)
    override fun readBytes(scope: NovexConversationWorkspaceScope, ref: NovexWorkspaceFileRef): ByteArray {
        requireNotNull(find(scope, ref)) { "文件已移出可用仓库，不能继续读取" }
        return store.readBytes(scope, ref)
    }
}
