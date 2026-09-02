package com.openminis.app.data.character

/** Coordinates root/version data with shared modules and protected media references. */
class CharacterLibraryService(
    private val catalog: CharacterCatalogRepository,
    private val modules: ContentModuleRepository,
    private val media: MediaAssetRepository,
) {
    suspend fun importDocument(
        document: CharacterLibraryDocument,
        now: Long = System.currentTimeMillis(),
    ): CharacterAggregate {
        val originalDocument = document.versions.single { it.kind == CharacterVersionKind.ORIGINAL }
        val created = catalog.createCharacter(
            name = document.name,
            originalLabel = originalDocument.label,
            originalProfileJson = originalDocument.profileJson,
            now = now,
        )
        addModules(created.original.id, originalDocument.modules, now)
        val variants = document.versions.filter { it.kind == CharacterVersionKind.VARIANT }.map { version ->
            catalog.createVariant(
                characterId = created.character.id,
                label = version.label,
                profileJson = version.profileJson,
                now = now,
            ).also { addModules(it.id, version.modules, now) }
        }
        return CharacterAggregate(created.character, created.original, variants)
    }

    suspend fun exportDocument(characterId: String): CharacterLibraryDocument {
        val aggregate = requireNotNull(catalog.character(characterId)) { "角色不存在" }
        return CharacterLibraryDocument(
            name = aggregate.character.name,
            versions = aggregate.allVersions.map { version ->
                CharacterVersionDocument(
                    kind = version.kind,
                    label = version.label,
                    profileJson = version.profileJson,
                    modules = modules.list(ModuleOwner.characterVersion(version.id)).map { module ->
                        CharacterModuleDocument(
                            type = module.type,
                            name = module.name,
                            contentJson = module.contentJson,
                            collapsed = module.collapsed,
                        )
                    },
                )
            },
        )
    }

    suspend fun duplicateCharacter(
        characterId: String,
        now: Long = System.currentTimeMillis(),
    ): CharacterAggregate {
        val source = requireNotNull(catalog.character(characterId)) { "角色不存在" }
        val copy = catalog.duplicateCharacter(characterId, now)
        source.allVersions.zip(copy.allVersions).forEach { (sourceVersion, copiedVersion) ->
            val sourceOwner = ModuleOwner.characterVersion(sourceVersion.id)
            val copiedOwner = ModuleOwner.characterVersion(copiedVersion.id)
            copyVersionContents(sourceOwner, copiedOwner, now)
        }
        return copy
    }

    /** Copies one reusable version and swaps only the requesting world's membership. */
    suspend fun saveAsWorldVariant(
        sourceVersionId: String,
        worldId: String,
        now: Long = System.currentTimeMillis(),
    ): CharacterVersionEntity {
        val source = requireNotNull(catalog.version(sourceVersionId)) { "角色版本不存在" }
        val world = requireNotNull(catalog.world(worldId)) { "世界不存在" }
        val currentVersions = catalog.versionsForWorld(worldId)
        val position = currentVersions.indexOfFirst { it.id == source.id }
        require(position >= 0) { "当前世界未关联这个角色版本" }
        val variant = catalog.createVariant(
            characterId = source.characterId,
            label = "${world.name}分身",
            profileJson = source.profileJson,
            now = now,
        )
        val sourceOwner = ModuleOwner.characterVersion(source.id)
        val variantOwner = ModuleOwner.characterVersion(variant.id)
        copyVersionContents(sourceOwner, variantOwner, now)
        catalog.removeVersionFromWorld(worldId, source.id)
        catalog.addVersionToWorld(worldId, variant.id, position, now)
        return variant
    }

    suspend fun deleteVariant(versionId: String) {
        val version = requireNotNull(catalog.version(versionId)) { "角色版本不存在" }
        require(version.kind == CharacterVersionKind.VARIANT) { "不能删除角色本体" }
        val owner = ModuleOwner.characterVersion(version.id)
        deleteModules(owner)
        media.removeAll(owner)
        catalog.deleteVersion(version.id)
    }

    suspend fun deleteCharacter(characterId: String) {
        val aggregate = requireNotNull(catalog.character(characterId)) { "角色不存在" }
        aggregate.allVersions.forEach { version ->
            val owner = ModuleOwner.characterVersion(version.id)
            deleteModules(owner)
            media.removeAll(owner)
        }
        catalog.deleteCharacter(characterId)
    }

    private suspend fun addModules(
        versionId: String,
        documents: List<CharacterModuleDocument>,
        now: Long,
    ) {
        val owner = ModuleOwner.characterVersion(versionId)
        documents.forEach { module ->
            modules.add(
                owner = owner,
                type = module.type,
                name = module.name,
                contentJson = module.contentJson,
                collapsed = module.collapsed,
                now = now,
            )
        }
    }

    private suspend fun copyVersionContents(
        sourceOwner: ModuleOwner,
        targetOwner: ModuleOwner,
        now: Long,
    ) {
        val sourceModules = modules.list(sourceOwner)
        val copiedModules = modules.copyAll(sourceOwner, targetOwner, now)
        sourceModules.zip(copiedModules).forEach { (sourceModule, copiedModule) ->
            media.assetFor(ModuleOwner.contentModule(sourceModule.id), MediaAssetSlot.MODULE_IMAGE)?.let { asset ->
                media.attach(ModuleOwner.contentModule(copiedModule.id), MediaAssetSlot.MODULE_IMAGE, asset.id)
            }
        }
        listOf(MediaAssetSlot.CHARACTER_AVATAR, MediaAssetSlot.CHARACTER_PAGE_BACKGROUND).forEach { slot ->
            media.assetFor(sourceOwner, slot)?.let { asset -> media.attach(targetOwner, slot, asset.id) }
        }
    }

    private suspend fun deleteModules(owner: ModuleOwner) {
        modules.list(owner).forEach { module ->
            media.removeAll(ModuleOwner.contentModule(module.id))
            modules.delete(module.id)
        }
    }
}
