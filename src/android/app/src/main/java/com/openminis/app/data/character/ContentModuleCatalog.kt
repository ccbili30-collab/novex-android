package com.openminis.app.data.character

enum class ContentModuleScope {
    WORLD,
    CHARACTER_VERSION,
    INTERACTIVE_FICTION,
}

data class ContentModuleDefinition(
    val type: ContentModuleType,
    val displayName: String,
    val repeatable: Boolean = false,
)

/**
 * The single interface for module availability, naming and repetition rules.
 * World and character callers intentionally share this catalog so adding a
 * new module kind cannot leave the editor, renderer and repository disagreeing.
 */
object ContentModuleCatalog {
    private val worldDefinitions = listOf(
        ContentModuleDefinition(ContentModuleType.TIMELINE, "时间线"),
        ContentModuleDefinition(ContentModuleType.ERA_EVENT, "时代与事件"),
        ContentModuleDefinition(ContentModuleType.MAP, "地图"),
        ContentModuleDefinition(ContentModuleType.REGION, "地区设定"),
        ContentModuleDefinition(ContentModuleType.FACTION, "势力设定"),
        ContentModuleDefinition(ContentModuleType.RACE, "种族设定"),
        ContentModuleDefinition(ContentModuleType.CUSTOM, "自定义模块", repeatable = true),
    )

    private val characterDefinitions = listOf(
        ContentModuleDefinition(ContentModuleType.QUOTES, "多形态语录"),
        ContentModuleDefinition(ContentModuleType.WORLD_EXPERIENCE, "世界经历"),
        ContentModuleDefinition(ContentModuleType.ATTRIBUTE_PANEL, "属性面板"),
        ContentModuleDefinition(ContentModuleType.EQUIPMENT, "随身装备"),
        ContentModuleDefinition(ContentModuleType.TALENT_SKILL, "天赋技能"),
        ContentModuleDefinition(ContentModuleType.APPEARANCE_PERSONALITY, "外貌性格"),
        ContentModuleDefinition(ContentModuleType.INTEREST, "兴趣爱好"),
        ContentModuleDefinition(ContentModuleType.CUSTOM, "自定义模块", repeatable = true),
    )

    private val interactiveFictionDefinitions = listOf(
        ContentModuleDefinition(ContentModuleType.GAME_PLAYER_IDENTITY, "玩家身份"),
        ContentModuleDefinition(ContentModuleType.GAME_OPENING, "开局说明"),
        ContentModuleDefinition(ContentModuleType.GAME_NARRATIVE_RULES, "叙事规则"),
        ContentModuleDefinition(ContentModuleType.GAME_POWER_SYSTEM, "力量体系"),
        ContentModuleDefinition(ContentModuleType.GAME_ATTRIBUTES, "属性面板"),
        ContentModuleDefinition(ContentModuleType.GAME_SKILLS, "技能"),
        ContentModuleDefinition(ContentModuleType.GAME_EQUIPMENT, "装备"),
        ContentModuleDefinition(ContentModuleType.GAME_ITEMS, "物品"),
        ContentModuleDefinition(ContentModuleType.GAME_QUESTS, "任务"),
        ContentModuleDefinition(ContentModuleType.GAME_CHECKS, "检定规则"),
        ContentModuleDefinition(ContentModuleType.GAME_ENDINGS, "结局"),
        ContentModuleDefinition(ContentModuleType.GAME_CHARACTER_STATUS, "角色档案"),
        ContentModuleDefinition(ContentModuleType.GAME_QUICK_ACTIONS, "快捷操作"),
        ContentModuleDefinition(ContentModuleType.CUSTOM, "自定义模块", repeatable = true),
    )

    private val definitionsByType = (worldDefinitions + characterDefinitions + interactiveFictionDefinitions)
        .associateBy(ContentModuleDefinition::type)

    fun definitions(scope: ContentModuleScope): List<ContentModuleDefinition> = when (scope) {
        ContentModuleScope.WORLD -> worldDefinitions
        ContentModuleScope.CHARACTER_VERSION -> characterDefinitions
        ContentModuleScope.INTERACTIVE_FICTION -> interactiveFictionDefinitions
    }

    fun definition(type: ContentModuleType): ContentModuleDefinition =
        requireNotNull(definitionsByType[type]) { "未知内容模块类型：$type" }

    fun scopeFor(ownerType: ModuleOwnerType): ContentModuleScope? = when (ownerType) {
        ModuleOwnerType.WORLD -> ContentModuleScope.WORLD
        ModuleOwnerType.CHARACTER_VERSION -> ContentModuleScope.CHARACTER_VERSION
        ModuleOwnerType.INTERACTIVE_FICTION -> ContentModuleScope.INTERACTIVE_FICTION
        ModuleOwnerType.CONTENT_MODULE -> null
    }

    fun availableToAdd(
        scope: ContentModuleScope,
        existingTypes: Collection<ContentModuleType>,
    ): List<ContentModuleDefinition> = definitions(scope).filter { definition ->
        definition.repeatable || definition.type !in existingTypes
    }

    fun requireCanAdd(
        scope: ContentModuleScope,
        type: ContentModuleType,
        existingTypes: Collection<ContentModuleType>,
    ): ContentModuleDefinition {
        val definition = definition(type)
        require(definition in definitions(scope)) { "该对象不支持${definition.displayName}" }
        require(definition.repeatable || type !in existingTypes) {
            "${definition.displayName}已经存在，每个对象只能添加一个"
        }
        return definition
    }
}
