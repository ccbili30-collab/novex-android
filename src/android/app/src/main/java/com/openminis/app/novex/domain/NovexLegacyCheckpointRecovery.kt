package com.openminis.app.novex.domain

/** Recover only legacy saves whose recorded source message is on the selected persisted branch. */
internal object NovexLegacyCheckpointRecovery {
    fun recover(store: NovexConversationWorkspaceStore, scope: NovexConversationWorkspaceScope): List<NovexCheckpointRecord> {
        val failed = mutableListOf<NovexCheckpointRecord>()
        val files = store as? FileNovexConversationWorkspaceStore ?: return emptyList()
        files.inspectConversation(scope.conversationId).flatMap { it.entries }.filter { entry ->
            entry.workspaceRef.area == NovexWorkspaceArea.SAVES &&
                entry.workspaceRef.relativePath.startsWith("checkpoint-") &&
                entry.workspaceRef.branchId.matches(Regex("assistant_[0-9]+")) &&
                entry.provenance.messageId in scope.visibleBranchIds &&
                entry.provenance.branchId == entry.workspaceRef.branchId &&
                entry.provenance.conversationId == scope.conversationId
        }.forEach { entry ->
            try {
                val messageId = requireNotNull(entry.provenance.messageId)
                val destination = scope.copy(writeBranchId = messageId)
                val path = entry.workspaceRef.relativePath
                if (store.inspect(destination).entries.any {
                        it.workspaceRef.area == NovexWorkspaceArea.SAVES && it.workspaceRef.relativePath == path &&
                            it.workspaceRef.branchId == messageId
                    }) return@forEach
                val legacyScope = scope.copy(writeBranchId = entry.workspaceRef.branchId)
                val raw = store.readBytes(legacyScope, entry.workspaceRef).toString(Charsets.UTF_8)
                require(NovexFrozenContextCodec.digest(raw) == entry.sha256) { "旧存档校验失败，未迁移" }
                val checkpoint = NovexPlaythroughCheckpointCodec.decode(raw)
                require(checkpoint.conversationId == scope.conversationId && checkpoint.branchId == entry.workspaceRef.branchId) {
                    "旧存档归属不符，未迁移"
                }
                NovexPlaythroughCheckpointWriter(store).save(destination,
                    checkpoint.copy(branchId = messageId, legacyPayloadJson = raw),
                    entry.provenance.copy(branchId = messageId))
                // Keep the original file and index as evidence; subsequent reads reuse the recovered save.
            } catch (failure: Exception) {
                failed += NovexCheckpointRecord(entry, null, "旧存档未能恢复：${failure.message}")
            }
        }
        return failed
    }
}
