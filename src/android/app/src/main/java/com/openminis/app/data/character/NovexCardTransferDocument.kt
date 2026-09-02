package com.openminis.app.data.character

import org.json.JSONArray
import org.json.JSONObject

sealed interface NovexCardImportDocument {
    val sourceId: String
    val name: String
    val originalJson: String
}

data class NovexWorldImportDocument(
    override val sourceId: String,
    override val name: String,
    val tags: List<String>,
    val overview: String,
    val coverPath: String?,
    val logoPath: String?,
    val backgroundPath: String?,
    val modules: List<NovexModuleImportDocument>,
    val characterVersionLinks: List<NovexCharacterVersionImportLink>,
    override val originalJson: String,
) : NovexCardImportDocument

data class NovexCharacterImportDocument(
    override val sourceId: String,
    override val name: String,
    val summary: String,
    val versions: List<NovexCharacterVersionImportDocument>,
    override val originalJson: String,
) : NovexCardImportDocument

data class NovexCharacterVersionImportDocument(
    val sourceId: String,
    val kind: CharacterVersionKind,
    val label: String,
    val profileJson: String,
    val avatarPath: String?,
    val pageBackgroundPath: String?,
    val modules: List<NovexModuleImportDocument>,
    val worldLinks: List<NovexWorldImportLink>,
)

data class NovexModuleImportDocument(
    val sourceId: String,
    val type: ContentModuleType,
    val originalType: String,
    val title: String,
    val presentation: String,
    val document: ContentModuleDocument,
    val imagePath: String? = null,
    val itemImagePaths: Map<String, String> = emptyMap(),
)

data class NovexCharacterVersionImportLink(
    val sourceCharacterId: String,
    val sourceVersionId: String,
    val fallbackCharacterName: String,
    val fallbackVersionName: String,
    val roleInWorld: String,
)

data class NovexWorldImportLink(
    val sourceWorldId: String,
    val fallbackWorldName: String,
    val roleInWorld: String,
)

data class NovexValidatedCardImport(
    val packageId: String,
    val displayName: String,
    val document: NovexCardImportDocument,
    val media: Map<String, NovexCardMedia>,
)

/** Converts the portable package shape into the stable Novex domain import shape. */
object NovexCardTransferParser {
    fun parse(preview: NovexCardPackagePreview): NovexValidatedCardImport {
        val root = JSONObject(preview.documentJson)
        val document = when (preview.kind) {
            NovexCardKind.WORLD -> parseWorld(root, preview.documentJson)
            NovexCardKind.CHARACTER -> parseCharacter(root, preview.documentJson)
        }
        val media = preview.media.associateBy(NovexCardMedia::path)
        require(media.size == preview.media.size) { "卡包媒体路径不能重复" }
        referencedMedia(document).forEach { path -> require(path in media) { "主文档引用了未声明媒体：$path" } }
        return NovexValidatedCardImport(preview.packageId, preview.displayName, document, media)
    }

    private fun parseWorld(root: JSONObject, raw: String): NovexWorldImportDocument {
        require(root.optString("documentType") == "novex.world") { "世界卡主文档类型无效" }
        val modules = orderedObjects(root, "modules", "moduleOrder").map(::parseModule)
        return NovexWorldImportDocument(
            sourceId = root.requireSourceId(),
            name = root.optString("name").trim().also { require(it.isNotBlank()) { "世界名称不能为空" } },
            tags = root.optJSONArray("tags").strings(),
            overview = root.optString("overview"),
            coverPath = root.mediaPath("media", "cover"),
            logoPath = root.mediaPath("media", "logo"),
            backgroundPath = root.mediaPath("media", "background"),
            modules = modules,
            characterVersionLinks = root.optJSONArray("characterVersionLinks").objects().map { item ->
                NovexCharacterVersionImportLink(
                    sourceCharacterId = item.optString("sourceCharacterId"),
                    sourceVersionId = item.optString("sourceVersionId"),
                    fallbackCharacterName = item.optString("fallbackCharacterName"),
                    fallbackVersionName = item.optString("fallbackVersionName"),
                    roleInWorld = item.optString("roleInWorld"),
                )
            },
            originalJson = raw,
        )
    }

    private fun parseCharacter(root: JSONObject, raw: String): NovexCharacterImportDocument {
        require(root.optString("documentType") == "novex.character") { "角色卡主文档类型无效" }
        val orderedVersions = orderedObjects(root, "versions", "versionOrder")
        val versions = orderedVersions.map { version -> parseVersion(root, version, raw) }
        require(versions.count { it.kind == CharacterVersionKind.ORIGINAL } == 1) {
            "角色卡必须包含且只能包含一个本体"
        }
        return NovexCharacterImportDocument(
            sourceId = root.requireSourceId(),
            name = root.optString("name").trim().also { require(it.isNotBlank()) { "角色名称不能为空" } },
            summary = root.optString("summary"),
            versions = versions,
            originalJson = raw,
        )
    }

    private fun parseVersion(
        characterRoot: JSONObject,
        version: JSONObject,
        characterRaw: String,
    ): NovexCharacterVersionImportDocument {
        val sourceId = version.optString("id").trim()
        require(sourceId.isNotBlank()) { "角色版本编号不能为空" }
        val kind = when (version.optString("kind")) {
            "origin" -> CharacterVersionKind.ORIGINAL
            "variant" -> CharacterVersionKind.VARIANT
            else -> error("角色版本类型无效：$sourceId")
        }
        val allModules = orderedObjects(version, "modules", "moduleOrder")
        val relationshipItems = allModules
            .filter { it.optString("type") == "relationships" }
            .flatMap { it.optJSONObject("content")?.optJSONArray("items").objects() }
        val profileSource = version.optJSONObject("profile") ?: JSONObject()
        val normalizedProfile = JSONObject(profileSource.toString()).apply {
            put("name", profileSource.optString("displayName"))
            put("tags", version.optJSONArray("tags") ?: JSONArray())
            put("gender", profileSource.optString("gender"))
            put("age", profileSource.optString("age"))
            put("race", profileSource.optString("race"))
            put("occupation", profileSource.optString("occupation"))
            put("summary", profileSource.optString("introduction"))
            put("customAttributes", JSONArray().apply {
                version.optJSONArray("customAttributes").objects().forEach { item ->
                    put(JSONObject().put("name", item.optString("key")).put("value", item.optString("value")))
                }
            })
            put("relationships", JSONArray().apply {
                relationshipItems.forEach { item ->
                    put(
                        JSONObject()
                            .put("characterName", item.optString("fallbackName"))
                            .put("relationship", item.optString("relation"))
                            .put("description", item.optString("description")),
                    )
                }
            })
            put("_novexSourceId", sourceId)
            put("_novexCharacterSourceId", characterRoot.requireSourceId())
            if (kind == CharacterVersionKind.ORIGINAL) put("_novexCharacterDocument", characterRaw)
        }
        return NovexCharacterVersionImportDocument(
            sourceId = sourceId,
            kind = kind,
            label = version.optString("name").trim().ifBlank {
                if (kind == CharacterVersionKind.ORIGINAL) "本体" else "分身"
            },
            profileJson = CharacterVersionProfile.fromJson(normalizedProfile.toString()).toJson(),
            avatarPath = version.mediaPath("media", "avatar"),
            pageBackgroundPath = version.mediaPath("media", "pageBackground"),
            modules = allModules.filterNot { it.optString("type") == "relationships" }.map(::parseModule),
            worldLinks = version.optJSONArray("worldLinks").objects().map { item ->
                NovexWorldImportLink(
                    sourceWorldId = item.optString("sourceWorldId"),
                    fallbackWorldName = item.optString("fallbackWorldName"),
                    roleInWorld = item.optString("roleInWorld"),
                )
            },
        )
    }

    private fun parseModule(module: JSONObject): NovexModuleImportDocument {
        val sourceId = module.optString("id").trim()
        require(sourceId.isNotBlank()) { "模块编号不能为空" }
        val originalType = module.optString("type")
        val type = when (originalType) {
            "timeline" -> ContentModuleType.TIMELINE
            "eraEvents" -> ContentModuleType.ERA_EVENT
            "map" -> ContentModuleType.MAP
            "regions" -> ContentModuleType.REGION
            "factions" -> ContentModuleType.FACTION
            "races" -> ContentModuleType.RACE
            "quotes" -> ContentModuleType.QUOTES
            "worldExperience" -> ContentModuleType.WORLD_EXPERIENCE
            "attributePanel" -> ContentModuleType.ATTRIBUTE_PANEL
            "equipment" -> ContentModuleType.EQUIPMENT
            "skills" -> ContentModuleType.TALENT_SKILL
            "appearancePersonality" -> ContentModuleType.APPEARANCE_PERSONALITY
            "interests" -> ContentModuleType.INTEREST
            else -> ContentModuleType.CUSTOM
        }
        val presentation = module.optString("presentation")
        val content = module.optJSONObject("content") ?: JSONObject()
        var imagePath: String? = null
        val itemImagePaths = linkedMapOf<String, String>()
        val document = when (presentation) {
            "article" -> ContentModuleDocument.Article(content.optString("text"))
            "singleImage" -> {
                imagePath = content.optJSONObject("image")?.optString("path")?.takeIf(String::isNotBlank)
                ContentModuleDocument.SingleImage(content.optString("description"))
            }
            "horizontalCards", "compactList", "quoteCards" -> ContentModuleDocument.Collection(
                content.optJSONArray("items").objects().map { item ->
                    val id = item.optString("id")
                    item.optJSONObject("image")?.optString("path")?.takeIf(String::isNotBlank)?.let { path ->
                        if (id.isNotBlank()) itemImagePaths[id] = path
                    }
                    ContentModuleCollectionItem(
                        id = id,
                        name = item.optString("name").ifBlank {
                            item.optString("form").ifBlank { item.optString("fallbackName") }
                        },
                        summary = item.optString("summary").ifBlank {
                            item.optString("text").ifBlank { item.optString("relation") }
                        },
                        description = item.optString("description"),
                        visualKey = id.takeIf { it in itemImagePaths },
                        preservedJson = item.toString(),
                    )
                },
            )
            else -> ContentModuleDocument.Unsupported(
                originalType = originalType.ifBlank { "unknown" },
                presentation = presentation.takeIf(String::isNotBlank),
                contentJson = content.toString(),
            )
        }
        return NovexModuleImportDocument(
            sourceId = sourceId,
            type = type,
            originalType = originalType,
            title = module.optString("title").trim().ifBlank { originalType.ifBlank { "自定义模块" } },
            presentation = presentation,
            document = document,
            imagePath = imagePath,
            itemImagePaths = itemImagePaths,
        )
    }

    private fun referencedMedia(document: NovexCardImportDocument): Set<String> = buildSet {
        fun addModules(modules: List<NovexModuleImportDocument>) = modules.forEach { module ->
            module.imagePath?.let(::add)
            addAll(module.itemImagePaths.values)
        }
        when (document) {
            is NovexWorldImportDocument -> {
                document.coverPath?.let(::add)
                document.logoPath?.let(::add)
                document.backgroundPath?.let(::add)
                addModules(document.modules)
            }
            is NovexCharacterImportDocument -> document.versions.forEach { version ->
                version.avatarPath?.let(::add)
                version.pageBackgroundPath?.let(::add)
                addModules(version.modules)
            }
        }
    }

    private fun orderedObjects(root: JSONObject, valuesKey: String, orderKey: String): List<JSONObject> {
        val values = root.optJSONArray(valuesKey).objects()
        val byId = values.associateBy { it.optString("id") }
        val orderedIds = root.optJSONArray(orderKey).strings()
        require(orderedIds.distinct().size == orderedIds.size) { "$orderKey 不能包含重复编号" }
        require(orderedIds.all(byId::containsKey) && byId.keys.all(orderedIds::contains)) {
            "$orderKey 必须完整对应 $valuesKey"
        }
        return orderedIds.map(byId::getValue)
    }

    private fun JSONObject.requireSourceId(): String = optString("sourceId").trim().also {
        require(it.isNotBlank()) { "来源编号不能为空" }
    }

    private fun JSONObject.mediaPath(containerKey: String, slotKey: String): String? =
        optJSONObject(containerKey)?.optJSONObject(slotKey)?.optString("path")?.takeIf(String::isNotBlank)

    private fun JSONArray?.objects(): List<JSONObject> = buildList {
        val array = this@objects ?: return@buildList
        repeat(array.length()) { index -> array.optJSONObject(index)?.let(::add) }
    }

    private fun JSONArray?.strings(): List<String> = buildList {
        val array = this@strings ?: return@buildList
        repeat(array.length()) { index -> array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add) }
    }
}
