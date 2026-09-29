package com.openminis.app.ui.noven

import com.openminis.app.cards.CardBinding
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.ui.sessions.hasNovexContext
import novex.content.CardKind
import novex.content.ContentDocument
import novex.content.ContentRef
import novex.content.ContentTargets
import novex.storage.CardStore

internal const val NOVEN_NOVA_TAG = "Nova"

/** 会话卡片标签需要的卡片信息；查不到的卡返回 null 并跳过。 */
internal data class NovenCardFace(val name: String, val kind: CardKind)

/** 会话列表行用到的卡片面信息：标签 + primary 缩略图引用。 */
internal data class SessionCardFace(
    val tags: List<String>,
    val thumbnail: ContentRef? = null,
)

/**
 * 从 novexConfigurationJson 里取 cardBinding。与 SessionHomePolicy.hasNovexContext
 * 读取同一字段同一键名；解析失败视为无绑定。
 */
internal fun ChatSessionEntity.sessionCardBinding(): CardBinding? = runCatching {
    val raw = novexConfigurationJson?.takeIf(String::isNotBlank) ?: return null
    val binding = org.json.JSONObject(raw).optJSONObject("cardBinding") ?: return null
    CardBinding.decode(binding.toString())
}.getOrNull()

/**
 * 会话行上的卡片标签：
 *  - primary 世界根卡 → "<名> · GM"；角色卡或世界内角色 → "<名> · 扮演"
 *  - backgrounds → "背景 · <名>"；managed → "管理 · <名>"
 *  - 查不到的卡跳过
 *  - 没有任何绑定且没有其它上下文 → ["Nova"]
 *  - 旧式会话（legacy world/character 字段，无 cardBinding）→ 空列表
 */
internal fun sessionCardTags(
    session: ChatSessionEntity,
    lookup: (rootId: String, targetId: String) -> NovenCardFace?,
): List<String> {
    val binding = session.sessionCardBinding()
    val bound = binding != null &&
        (binding.primary != null || binding.backgrounds.isNotEmpty() || binding.managed.isNotEmpty())
    if (!bound) {
        return if (session.hasNovexContext()) emptyList() else listOf(NOVEN_NOVA_TAG)
    }
    val tags = ArrayList<String>()
    binding?.primary?.let { primary ->
        lookup(primary.rootId, primary.targetId)?.let { face ->
            tags += if (face.kind == CardKind.WORLD) "${face.name} · GM" else "${face.name} · 扮演"
        }
    }
    binding?.backgrounds?.forEach { source ->
        lookup(source.rootId, source.targetId)?.let { face -> tags += "背景 · ${face.name}" }
    }
    binding?.managed?.forEach { target ->
        lookup(target.rootId, target.targetId)?.let { face -> tags += "管理 · ${face.name}" }
    }
    return tags
}

/** 行内最多显示 [limit] 个标签，超出折叠成 "+N"（返回可见标签与隐藏数）。 */
internal fun trimSessionTags(tags: List<String>, limit: Int = 2): Pair<List<String>, Int> =
    if (tags.size <= limit) tags to 0 else tags.take(limit) to tags.size - limit

/** primary 目标的封面/头像资源引用，用作会话行缩略图；没有返回 null。 */
internal fun sessionPrimaryThumbnail(session: ChatSessionEntity, store: CardStore): ContentRef? {
    val primary = session.sessionCardBinding()?.primary ?: return null
    val root = runCatching { store.open(primary.rootId)?.content }.getOrNull() ?: return null
    val target = runCatching { ContentTargets.find(root, primary.targetId) }.getOrNull() ?: return null
    val imageId = target.appearance.coverResourceId ?: target.appearance.avatarResourceId ?: return null
    return target.resources.firstOrNull { it.id == imageId }?.content
}

/**
 * 名字目录：按 (rootId, targetId) 查名称与类型。世界内角色需要打开根卡读
 * internalCharacters。在 IO 线程一次性构建使用；库变化时由调用方重建。
 */
internal fun novenCardLookup(store: CardStore): (String, String) -> NovenCardFace? {
    val roots = HashMap<String, ContentDocument?>()
    fun root(id: String): ContentDocument? =
        roots.getOrPut(id) { runCatching { store.open(id)?.content }.getOrNull() }
    return { rootId, targetId ->
        val document = root(rootId)
        val target = document?.let {
            runCatching { ContentTargets.find(it, targetId) }.getOrNull()
        }
        target?.let { NovenCardFace(it.name, it.kind) }
    }
}

/** 一次构建整份会话卡片面信息（标签 + 缩略图引用）。 */
internal fun sessionCardFaces(
    sessions: List<ChatSessionEntity>,
    store: CardStore,
): Map<String, SessionCardFace> {
    val lookup = novenCardLookup(store)
    return sessions.associate { session ->
        session.id to SessionCardFace(
            tags = sessionCardTags(session, lookup),
            thumbnail = sessionPrimaryThumbnail(session, store),
        )
    }
}

/**
 * 「我的」页统计：某张根卡被多少段会话引用（primary、背景、管理任一角色都算，
 * 同一会话内对同一根卡的多次引用只计一次）。
 */
internal fun cardSessionReferenceCounts(sessions: List<ChatSessionEntity>): Map<String, Int> {
    val counts = HashMap<String, Int>()
    sessions.forEach { session ->
        val binding = session.sessionCardBinding() ?: return@forEach
        val roots = buildSet {
            binding.primary?.let { add(it.rootId) }
            binding.backgrounds.forEach { add(it.rootId) }
            binding.managed.forEach { add(it.rootId) }
        }
        roots.forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return counts
}
