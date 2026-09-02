package com.openminis.app.ui.settings

import com.openminis.app.data.character.CharacterVersionProfile

internal data class CharacterFact(
    val label: String,
    val value: String,
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

/** One compact line is enough for a module list; the full body belongs to its detail page. */
internal fun moduleListSummary(text: String, maxCharacters: Int = 48): String {
    val normalized = text.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" ")
    if (normalized.isBlank()) return "尚未填写内容"
    if (normalized.length <= maxCharacters) return normalized
    return normalized.take(maxCharacters).trimEnd('。', '，', '；', '、', ' ') + "…"
}
