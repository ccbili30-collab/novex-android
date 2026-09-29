package com.openminis.app.ui.noven

import com.openminis.app.data.db.ChatSessionEntity
import novex.content.CardKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCardTagsTest {

    private fun session(
        id: String = "s1",
        config: String? = null,
        worldId: String? = null,
        characterId: String? = null,
    ) = ChatSessionEntity(
        id = id,
        modelId = "m",
        createdAt = 0,
        updatedAt = 0,
        worldId = worldId,
        characterId = characterId,
        novexConfigurationJson = config,
    )

    private fun binding(
        primary: Pair<String, String>? = null,
        backgrounds: List<Pair<String, String>> = emptyList(),
        managed: List<Pair<String, String>> = emptyList(),
    ): String {
        fun array(items: List<Pair<String, String>>) =
            items.joinToString(",", "[", "]") { (r, t) -> """{"root":"$r","target":"$t"}""" }
        return """{"cardBinding":{"primary":${array(listOfNotNull(primary))},""" +
            """"backgrounds":${array(backgrounds)},"managed":${array(managed)}}}"""
    }

    private fun lookup(vararg faces: Triple<String, String, NovenCardFace>): (String, String) -> NovenCardFace? {
        val map = faces.associate { (it.first to it.second) to it.third }
        return { root, target -> map[root to target] }
    }

    private fun world(name: String) = NovenCardFace(name, CardKind.WORLD)
    private fun character(name: String) = NovenCardFace(name, CardKind.CHARACTER)

    @Test
    fun primaryWorldProducesGmTag() {
        val tags = sessionCardTags(
            session(config = binding(primary = "w1" to "w1")),
            lookup(Triple("w1", "w1", world("晨雾岛"))),
        )
        assertEquals(listOf("晨雾岛 · GM"), tags)
    }

    @Test
    fun primaryCharacterProducesRoleplayTag() {
        // 世界内角色与普通角色卡都按“扮演”处理。
        val tags = sessionCardTags(
            session(config = binding(primary = "w1" to "c9")),
            lookup(Triple("w1", "c9", character("阿澈"))),
        )
        assertEquals(listOf("阿澈 · 扮演"), tags)
    }

    @Test
    fun backgroundsAndManagedAppendInOrder() {
        val tags = sessionCardTags(
            session(
                config = binding(
                    primary = "w1" to "w1",
                    backgrounds = listOf("b1" to "b1"),
                    managed = listOf("w1" to "c9"),
                ),
            ),
            lookup(
                Triple("w1", "w1", world("晨雾岛")),
                Triple("b1", "b1", world("北境")),
                Triple("w1", "c9", character("阿澈")),
            ),
        )
        assertEquals(listOf("晨雾岛 · GM", "背景 · 北境", "管理 · 阿澈"), tags)
    }

    @Test
    fun missingCardIsSkipped() {
        val tags = sessionCardTags(
            session(config = binding(primary = "gone" to "gone")),
            lookup(),
        )
        // 绑定存在但查不到卡：产出空列表而不是 Nova。
        assertEquals(emptyList<String>(), tags)
    }

    @Test
    fun noBindingAndNoContextShowsNova() {
        assertEquals(listOf("Nova"), sessionCardTags(session(config = null), lookup()))
        // 空绑定 JSON 同样回落 Nova。
        assertEquals(listOf("Nova"), sessionCardTags(session(config = binding()), lookup()))
    }

    @Test
    fun legacySessionWithoutBindingShowsNoTags() {
        val tags = sessionCardTags(session(config = null, worldId = "legacy-world"), lookup())
        assertEquals(emptyList<String>(), tags)
    }

    @Test
    fun rowShowsTwoTagsPlusOverflowCount() {
        assertEquals(
            listOf("a", "b") to 1,
            trimSessionTags(listOf("a", "b", "c")),
        )
        assertEquals(listOf("a", "b") to 0, trimSessionTags(listOf("a", "b")))
        assertEquals(listOf("a") to 0, trimSessionTags(listOf("a")))
    }

    @Test
    fun referenceCountsDeduplicateRootsWithinASession() {
        val sessions = listOf(
            session(
                id = "s1",
                config = binding(
                    primary = "w1" to "w1",
                    backgrounds = listOf("w1" to "c9"),
                    managed = listOf("w1" to "c9"),
                ),
            ),
            session(id = "s2", config = binding(backgrounds = listOf("w1" to "w1", "b1" to "b1"))),
            session(id = "s3", config = null),
        )
        assertEquals(mapOf("w1" to 2, "b1" to 1), cardSessionReferenceCounts(sessions))
    }
}
