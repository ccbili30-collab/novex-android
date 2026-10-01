package com.openminis.app.provider

import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ThinkingLevel

/**
 * [T-android-thinking-level-arch] 各模型思考档上限的声明式目录（血统清剿
 * P3.7 就地真重写；匹配规则与档位为行为冻结面，ThinkingLevelTest 钉死）。
 * 加模型 = 加一条规则；退役 = 删一条规则。不碰任何其他代码路径——没命中
 * 规则的模型回落到 [catalogMaxThinkingLevel] 保守的 supportsReasoning
 * 默认。内容与 iOS ThinkingLevelCatalog.swift 对齐（对每个模型能力的理解
 * 相同），表达用 Kotlin 习惯。
 */
object ThinkingLevelCatalog {

    private class CappedRule(
        val matches: (String) -> Boolean,
        val ceiling: ThinkingLevel,
    )

    private val familyCeilings: List<CappedRule> = buildList {
        // GPT-5.6 家族：sol / terra / luna 都到 MAX。ULTRA 是客户端的
        // 「Max + 编排」概念，从来不是 wire 上的 effort——effort 层把 MAX
        // 与 ULTRA 一律映射为 "max"。与 iOS ThinkingLevelCatalog.swift
        // 保持同步。
        add(cap({ it.startsWith("gpt-5.6-sol") || it.startsWith("gpt-5.6-terra") }, ThinkingLevel.MAX))
        add(cap({ it.startsWith("gpt-5.6-luna") }, ThinkingLevel.MAX))
        add(cap({ it.startsWith("gpt-5.5") }, ThinkingLevel.XHIGH))
        // 已知封顶在 high 的第三方模型。
        // MiMo 在野外同时存在两种 id 拼法：目录文档写 "MiMo-2.5"，线上
        // API（api.xiaomimimo.com /v1/models）返回 "mimo-v2.5" /
        // "mimo-v2.5-pro"——旧的 "mimo-2.5" 子串匹配漏掉它们，钳制把
        // xhigh 直接放行给了会对其回 400 的后端。匹配家族，不匹配一种
        // 拼法（对齐 iOS 72968c4f）。
        add(cap({ it.contains("mimo") || it.contains("agnes") }, ThinkingLevel.HIGH))
        // 字节 seed（火山方舟 "seed-1.6…"/"seed-2.0…"、OpenRouter
        // "bytedance-seed/…"）：拒 xhigh，报 "Invalid reasoning_effort:
        // xhigh"。方舟的阶梯封顶 high。
        add(cap({ it.contains("seed-") || it.contains("bytedance-seed") }, ThinkingLevel.HIGH))
        // Anthropic Opus 4.x 自适应思考家族。旧的逐版本
        // startsWith("claude-opus-4.7"/"claude-opus-4.6") 从未命中：
        // LLMModel.id 的次版本号用连字符分隔（claude-opus-4-8 /
        // claude-opus-4-6），不是点号——每个 Claude Opus 都漏进 XHIGH
        // 默认，而 Opus 4.8 连规则都没有。先做点→连字符归一，再一条前缀
        // 匹配盖住 4.6/4.7/4.8 与未来的 4.x（对齐 iOS normalizedHasPrefix）。
        add(cap({ versionAgnosticPrefix(it, "claude-opus-4") }, ThinkingLevel.MAX))
    }

    private fun cap(match: (String) -> Boolean, ceiling: ThinkingLevel) =
        CappedRule(match, ceiling)

    /** 前缀匹配，版本分隔符的 "." 与 "-" 视为等价——点式/连字符式 id 通吃。 */
    private fun versionAgnosticPrefix(id: String, prefix: String): Boolean =
        id.replace('.', '-').startsWith(prefix)

    /**
     * 目录声明的上限。null = 目录不覆盖该模型——调用方应回落到
     * supportsReasoning 默认。
     */
    fun declaredMaxLevel(modelId: String): ThinkingLevel? {
        val lid = modelId.lowercase()
        return familyCeilings.firstOrNull { it.matches(lid) }?.ceiling
    }
}

/**
 * [T-android-thinking-level-arch] 「这个模型的思考能开多高？」——只经内置
 * 层解析（用户覆盖见 [ModelEntry.effectiveMaxThinkingLevel]）：
 *   1. supportsReasoning == false → OFF（先于目录规则判——家族规则放宽
 *      也不许抬升无推理成员的上限）；
 *   2. ThinkingLevelCatalog 规则；
 *   3. true/null → XHIGH（保守默认：推理模型不该被意外压到 GPT-5.6 之前
 *      各供应商都接受的档位之下）。
 */
val LLMModel.catalogMaxThinkingLevel: ThinkingLevel
    get() {
        // 不会推理的模型无论目录家族规则怎么说都是 OFF——家族规则按 id
        // 子串匹配，放宽的规则（如 "mimo" 盖住 mimo-v2.5）不得抬升该家族
        // 无推理成员（mimo-v2.5-tts/-asr）的上限。[T-fallback-thinking-preclamp]
        if (supportsReasoning == false) return ThinkingLevel.OFF
        return ThinkingLevelCatalog.declaredMaxLevel(id) ?: ThinkingLevel.XHIGH
    }

/**
 * [T-android-thinking-level-arch] 全应用其余部分请教的四层解析：条目上的
 * 用户手动覆盖（最高优先）赢过目录/默认。`entry.model` 已把
 * ModelOverrides 折进基础模型，读上限时从解析后的模型上取。
 */
val ModelEntry.effectiveMaxThinkingLevel: ThinkingLevel
    get() = overrides.maxThinkingLevel ?: model.catalogMaxThinkingLevel
