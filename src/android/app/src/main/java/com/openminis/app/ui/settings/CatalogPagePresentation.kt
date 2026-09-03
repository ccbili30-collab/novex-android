package com.openminis.app.ui.settings

import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile

internal data class CharacterFact(
    val label: String,
    val value: String,
)

internal data class CharacterOverviewRow(
    val title: String,
    val summary: String,
)

/** Keeps sparse character pages compact instead of rendering rows of empty placeholders. */
internal fun visibleCharacterFacts(profile: CharacterVersionProfile): List<CharacterFact> = buildList {
    profile.tags.takeIf(List<String>::isNotEmpty)?.let { add(CharacterFact("标签", it.joinToString(" · "))) }
    profile.gender.trim().takeIf(String::isNotEmpty)?.let { add(CharacterFact("性别", it)) }
    profile.age.trim().takeIf(String::isNotEmpty)?.let { add(CharacterFact("年龄", it)) }
    profile.race.trim().takeIf(String::isNotEmpty)?.let { add(CharacterFact("种族", it)) }
    profile.occupation.trim().takeIf(String::isNotEmpty)?.let { add(CharacterFact("职业", it)) }
    profile.summary.trim().takeIf(String::isNotEmpty)?.let { add(CharacterFact("简介", it)) }
}

/** A sparse, scan-friendly presentation for the character display page. */
internal fun characterOverviewRows(profile: CharacterVersionProfile): List<CharacterOverviewRow> = buildList {
    profile.summary.trim().takeIf(String::isNotEmpty)?.let {
        add(CharacterOverviewRow("人物简介", it))
    }
    val facts = buildList {
        if (profile.tags.isNotEmpty()) add(profile.tags.joinToString(" · "))
        profile.gender.trim().takeIf(String::isNotEmpty)?.let { add(it) }
        profile.age.trim().takeIf(String::isNotEmpty)?.let { add(it) }
        profile.race.trim().takeIf(String::isNotEmpty)?.let { add(it) }
        profile.occupation.trim().takeIf(String::isNotEmpty)?.let { add(it) }
    }
    if (facts.isNotEmpty()) add(CharacterOverviewRow("基本信息", facts.joinToString(" · ")))
    if (profile.customAttributes.isNotEmpty()) add(
        CharacterOverviewRow(
            "自定义属性",
            profile.customAttributes.joinToString(" · ") { "${it.name} ${it.value}".trim() },
        ),
    )
    if (profile.relationships.isNotEmpty()) add(
        CharacterOverviewRow(
            "角色关系",
            profile.relationships.joinToString(" · ") {
                listOf(it.characterName, it.relationship).filter(String::isNotBlank).joinToString(" ")
            },
        ),
    )
}

/** Keeps the reusable-version structure visible without making it the page's primary content. */
internal fun characterVersionSelectorLabel(
    kind: CharacterVersionKind,
    label: String,
    variantCount: Int,
): String = buildString {
    append(if (kind == CharacterVersionKind.ORIGINAL) "本体" else label.ifBlank { "分身" })
    if (variantCount > 0) append(" · ").append(variantCount).append(" 个分身")
}
