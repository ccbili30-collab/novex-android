package com.openminis.app.provider

import com.openminis.app.logging.AppLogger

/**
 * [T-model-release-ranking] 模型发布序排名（血统清剿 P3.7 就地真重写；
 * Rank 字段、comparator 语义、解析容忍度为行为冻结面）。对齐 iOS
 * `ModelReleaseIndex`（Providers/ModelReleaseIndex.swift）。
 *
 * 为什么要有它：选单前几名是陈旧模型不只是难看，是积极危害。OpenMinis#83
 * 报「GPT-5.3 CodeX Spark 无法调用工具」，真因是 Codex 后端在 ChatGPT
 * 账号上拒收该模型（`400 … not supported`），而拒收呈现为一条**空**助手
 * 回合。新用户从列表里挑了个死模型，得出「应用坏了」的结论。同一批 12
 * 个 Codex id 按发布日期排一下，6 个可调用的全部置顶。
 *
 * 注意——只排名不设闸。日期与可用性的相关性是 OpenAI 当前留存政策的
 * 副产品，不是契约。绝不能从发布日期反推「可调用」。
 */
object ModelReleaseIndex {
    private const val TAG = "ModelReleaseIndex"

    /** 单个模型的排名输入；`releaseDay == null` 沉底。 */
    data class Rank(
        val releaseDay: Int?,
        val outputCostPerMTok: Double,
        val contextWindow: Int,
        val displayName: String,
    )

    /**
     * 最新在前，其次贵者（≈更大更强），再上下文大者，末以名字收尾——
     * 顺序稳定、读与读之间绝无抖动。无日期的模型沉到所有有日期者之下但
     * **不隐藏**——自定义、本地、中转托管的模型本来就没有目录条目。
     */
    val comparator: Comparator<Rank> = Comparator { a, b ->
        val dayA = a.releaseDay
        val dayB = b.releaseDay
        if (dayA != dayB) {
            return@Comparator when {
                dayA == null -> 1
                dayB == null -> -1
                else -> dayB.compareTo(dayA)
            }
        }
        if (a.outputCostPerMTok != b.outputCostPerMTok) {
            return@Comparator b.outputCostPerMTok.compareTo(a.outputCostPerMTok)
        }
        if (a.contextWindow != b.contextWindow) {
            return@Comparator b.contextWindow.compareTo(a.contextWindow)
        }
        a.displayName.compareTo(b.displayName, ignoreCase = true)
    }

    /** 目录条目的三个排名事实。 */
    private class CatalogFact(val day: Int, val outputCost: Double?, val context: Int?)

    private var factsByFullId: Map<String, CatalogFact> = emptyMap()
    private var factsByTail: Map<String, CatalogFact> = emptyMap()
    private var indexReady = false

    @Synchronized
    private fun ensureIndex() {
        if (indexReady) return
        val byFull = HashMap<String, CatalogFact>()
        val byTail = HashMap<String, CatalogFact>()
        for (provider in ModelsDevApi.registrySnapshot().values) {
            for ((rawId, model) in provider.models) {
                val day = parseReleaseDay(model.releaseDate) ?: continue
                val fact = CatalogFact(day, model.outputCost, model.contextWindow)
                val id = rawId.lowercase()
                // 同一模型被大量供应商转发（24 家都在挂 glm-5.2），日期互有
                // 出入。取最新——拖后腿的中转不许把当红模型拽下榜。
                byFull.keepNewest(id, fact)
                byTail.keepNewest(id.substringAfterLast('/'), fact)
            }
        }
        factsByFullId = byFull
        factsByTail = byTail
        indexReady = true
        AppLogger.info(TAG, "release index built: full=${byFull.size} tail=${byTail.size}")
    }

    /** 同键冲突时保留日期更新的一方。 */
    private fun HashMap<String, CatalogFact>.keepNewest(key: String, fact: CatalogFact) {
        val existing = this[key]
        if (existing == null || existing.day < fact.day) this[key] = fact
    }

    /** 丢弃缓存，让刷新后的目录重新建索引。 */
    @Synchronized
    fun invalidate() {
        indexReady = false
        factsByFullId = emptyMap()
        factsByTail = emptyMap()
    }

    /** 取模型的排名键。未知 id 得 `releaseDay = null`（沉底）。 */
    fun rank(modelId: String, displayName: String, contextWindow: Int?): Rank {
        ensureIndex()
        val fact = resolveFact(modelId)
        return Rank(
            releaseDay = fact?.day,
            outputCostPerMTok = fact?.outputCost ?: 0.0,
            contextWindow = contextWindow ?: fact?.context ?: 0,
            displayName = displayName,
        )
    }

    private fun resolveFact(raw: String): CatalogFact? {
        val cleaned = normalizeId(raw)
        // 1. 先查全 id。`aion-labs/aion-2.0`、`amazon/nova-lite-v1` 这类
        //    条目只以带命名空间的形式存在；先查尾段会静默丢掉它们
        //    （真机 868 模型目录实测：58% → 80% 解析率）。
        factsByFullId[cleaned]?.let { return it }

        val tail = stripVendorDotPrefix(cleaned.substringAfterLast('/'))
        factsByTail[tail]?.let { return it }

        val undated = stripSnapshotSuffix(tail)
        if (undated != tail) factsByTail[undated]?.let { return it }

        // 沿家族上溯：gpt-5.6-sol → gpt-5.6 → gpt。
        var stem = undated
        while ('-' in stem) {
            stem = stem.substringBeforeLast('-')
            factsByTail[stem]?.let { return it }
        }

        // 刻意不做模糊/子串兜底：早期原型曾把
        // `us.anthropic.claude-opus-4-5-20251101-v1:0` 匹到 `claude-opus-5`
        // ——差六个月的另一个模型。错误的日期比没有更糟：它把陈旧模型顶
        // 到榜首，恰是本件要防的事故。
        return null
    }

    /** 归一化：去空白/大小写/`:deployment`/`@slot`、剥地区前缀。 */
    private fun normalizeId(raw: String): String {
        var s = raw.trim().lowercase().substringBefore(':').substringBefore('@')
        for (region in listOf("us.", "eu.", "apac.", "global.")) {
            if (s.startsWith(region)) {
                s = s.removePrefix(region)
                break
            }
        }
        return s
    }

    /** 剥开头的 `vendor.` 命名空间，但绝不碰版本点（`gpt-5.6`）。 */
    private fun stripVendorDotPrefix(s: String): String {
        val dot = s.indexOf('.')
        if (dot <= 0) return s
        val head = s.substring(0, dot)
        if (!head.all { it.isLetter() }) return s
        return s.substring(dot + 1)
    }

    /** 去掉尾部 `-20YYMMDD` 快照戳。 */
    private fun stripSnapshotSuffix(s: String): String {
        if (s.length <= 9) return s
        val stamp = s.takeLast(9)
        if (stamp[0] != '-') return s
        val digits = stamp.drop(1)
        if (digits.length != 8 || !digits.all { it.isDigit() } || !digits.startsWith("20")) return s
        return s.dropLast(9)
    }

    /**
     * 把 `YYYY-MM-DD` 或 `YYYY-MM` 解析成可排序的日序数。其余形态返回
     * null 而不是猜——181 条内置条目用短式，严格解析器会把它们全部
     * 静默沉底。
     */
    fun parseReleaseDay(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split('-')
        if (parts.size !in 2..3) return null
        val year = parts[0].takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val day = if (parts.size == 3) {
            parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
        } else {
            1
        }
        // 只用于排序——朴素的年/月/日 packing 与真实日期同序，犯不着为此
        // 拖一个日历进来。
        return year * 10_000 + month * 100 + day
    }
}
