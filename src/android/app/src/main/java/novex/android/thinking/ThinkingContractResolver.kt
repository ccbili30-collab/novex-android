package novex.android.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject

/**
 * 端点与机型一切与思考相关的已知量。显式传入而非从 provider 读取——解析器保持
 * 纯函数，不用网络桩即可测。
 */
data class ThinkingResolveContext(
    val modelId: String,
    /**
     * 所属供应商实例 id，或 null。设定时解析器把该实例的用户自定义规则
     * （[ThinkingContractResolver.customRulesFor]）排在内置表之上；null → 仅
     * 内置表，行为与无自定义层的基线逐字节一致。
     */
    val instanceId: String? = null,
    val supportsReasoning: Boolean?,
    val declaredEffortValues: List<String>?,
    /**
     * [OpenMinis#163] 目录肯定地声明该机型没有档位（会思考，但不收
     * `reasoning_effort`）。与 `declaredEffortValues == null`（目录没听说过它）
     * 是两回事——只有肯定情形才抑制该字段。默认 false，既有构造点行为不变。
     *
     * 仅与 [isXAI] 联合生效（见 ReasoningEffort 分支里的跳过判定）。
     */
    val declaresNoEffortTiers: Boolean = false,
    val level: ThinkingLevel,
    val maxTokens: Int,
    /**
     * 厂商判定，由调用方按 base URL 解析。解析器从不自己解析 URL——URL 嗅探
     * 收拢在一处，未来以用户自定义作用域替换时不 touching 本文件。
     */
    val isOpenRouter: Boolean,
    val usesUnifiedReasoningEffort: Boolean,
    val isMistral: Boolean,
    val isDashScope: Boolean,
    /**
     * [OpenMinis#163] 端点是 xAI 自家 API（api.x.ai），不是仅仅转发 grok 系
     * 机型的中转。把空档位跳过限定在真正观测到 400 的厂商。默认 false。
     */
    val isXAI: Boolean = false,
    /**
     * 厂商成文的关闭档位；null = 关闭时省略整个字段。这已是调用方做过的
     * 允许清单决策（iOS ff60c818）。
     */
    val offEffort: String?,
)

/**
 * 为什么落在某个线形态。设计 §8 / GH OpenMinis#100：解析结果必须可检视，否则
 * 用户可编辑的规则层只是把一个黑箱换成更复杂的黑箱。
 */
data class ThinkingResolveTrace(
    val matchedRuleLabel: String,
    val matchedRuleKind: ThinkingContract.Kind,
    val formatSource: String,
    val emittedKeys: List<String>,
    val clampedFrom: String? = null,
    val clampedTo: String? = null,
) {
    /** AppLogger("Thinking") 的单行形态。 */
    val logLine: String
        get() = buildList {
            add("rule=$matchedRuleLabel")
            add("kind=$matchedRuleKind")
            add("src=$formatSource")
            if (clampedFrom != null && clampedTo != null && clampedFrom != clampedTo) {
                add("clamp=$clampedFrom->$clampedTo")
            }
            add("keys=[${emittedKeys.sorted().joinToString(",")}]")
        }.joinToString(" ")
}

/**
 * 思考参数的规则解析器（P3.2 自有实现，替换上游 provider/thinking 包的解析器）。
 * 行为与被替换实现逐字节一致——金表快照（ThinkingWireGoldenSnapshotTest 等）与
 * 回归测试钉死输出，迁移期间不得出现任何线形态差异。
 *
 * 解析模型（设计 §4）分两阶段：
 *  阶段 A——规则表自上而下，第一个 scope 命中即胜出、停止。顺序即优先级。底部
 *           PROVIDER_TYPE_DEFAULT 的 scope 是 AllModels，阶段 A 永不落空。
 *  阶段 B——命中规则的 wireFormat 为 null 时落入回退链。与阶段 A 刻意分开：
 *           跨规则合并字段会让「这个值为什么从那里来」在 trace 里不可回答。
 */
object ThinkingContractResolver {

    /**
     * 进程级自定义规则缓存，按供应商实例 id 键控，每表按存储（优先级）序。
     * [apply] 是从请求装配路径进入的同步调用，而规则本体在 Room（异步），故由
     * 仓库层在加载与每次变更后推送至此。缓存未命中给空表 ⇒ 仅内置行为，绝不
     * 给错形态。
     */
    @Volatile
    private var customRulesCache: Map<String, List<ThinkingContract>> = emptyMap()

    /** 整表替换（仓库加载配置后调用一次）。 */
    @Synchronized
    fun setAllCustomRules(byInstance: Map<String, List<ThinkingContract>>) {
        customRulesCache = byInstance
    }

    /** 替换某实例的自定义规则（增/改/删/排序后调用）。 */
    @Synchronized
    fun setCustomRules(instanceId: String, rules: List<ThinkingContract>) {
        customRulesCache = customRulesCache.toMutableMap().apply {
            if (rules.isEmpty()) remove(instanceId) else put(instanceId, rules)
        }
    }

    /** 该实例的自定义规则（优先级序），无则空表。 */
    fun customRulesFor(instanceId: String?): List<ThinkingContract> =
        instanceId?.let { customRulesCache[it] } ?: emptyList()

    /**
     * 内置规则表，优先级序——刻意不按字母序。最特异的判定必须最先被咨询；
     * Mistral 打头是因为它的规则是压过下面一切形态的总禁令。
     */
    fun builtInRules(ctx: ThinkingResolveContext): List<ThinkingContract> = buildList {
        // Mistral — GH OpenMinis#87 / iOS 4592ca9b / 29065ca0。总禁令：请求拒
        // reasoning（422 extra_forbidden），AssistantMessage 闭 schema 拒
        // reasoning_content。必须压过一切。
        if (ctx.isMistral) {
            add(
                ThinkingContract(
                    kind = ThinkingContract.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingContract.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.OmitEverything,
                    reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.NEVER),
                    label = "mistral-official",
                ),
            )
        }

        // OpenRouter — 嵌套 reasoning:{effort}，关闭时省略（强制思考后端不拒
        // effort:"none"）。
        if (ctx.isOpenRouter) {
            add(
                ThinkingContract(
                    kind = ThinkingContract.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingContract.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffortNested(offValue = null),
                    label = "openrouter",
                ),
            )
        }

        // 从这里往下顺序承重。它逐分支复刻被替换 when 链的求值序：
        //     o*/gpt-5* → qwen||isDashScope → 自思考跳过 → 通用回退
        // usesUnifiedReasoningEffort 只在 deepseek-v4 与自思考分支内部被咨询
        // ——OpenAI 原生与 qwen 分支从不看它。
        //
        // 首版注册表曾把统一网关规则抬到这两条之上，静默改变了两个真实案例：
        // DashScope 上的 gpt-5 id 开始发 enable_thinking+thinking_budget 而非
        // reasoning_effort，（iOS 镜像同理）Ark/Azure/Venice 上的 qwen id 反向
        // 翻转。正是本设计要防的用户不可见静默劣化。网关规则必须排在这两条
        // 之下。金表没抓住它是因为矩阵每行只变一个维度；交叉行（qwen×unified、
        // gpt5×dashscope、mimo×unified）随后与修复一同补上。

        // OpenAI 原生 o 系 / GPT-5.x — 根级 reasoning_effort。原判定是
        // startsWith("o") || startsWith("gpt-5")；宽 "o" 前缀逐字保留而非收窄到
        // o1/o3/o4——收窄会改变一切以 "o" 开头的 id 的行为。
        add(
            ThinkingContract(
                kind = ThinkingContract.Kind.OFFICIAL_VENDOR,
                scope = ThinkingContract.Scope.ModelPattern("o*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )
        add(
            ThinkingContract(
                kind = ThinkingContract.Kind.OFFICIAL_VENDOR,
                scope = ThinkingContract.Scope.ModelPattern("gpt-5*"),
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-native",
            ),
        )

        // Qwen / DashScope — 双发 + 严格预算不等式（25165700, a5a0de20）。
        // 原分支是 `lid.contains("qwen") || isDashScope` 且无统一网关守卫，故
        // id 命中与端点命中都保留各自原生的 enable_thinking 机制——即使在
        // Ark/Azure/Venice 上。
        if (ctx.isDashScope) {
            add(
                ThinkingContract(
                    kind = ThinkingContract.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingContract.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.QwenDual,
                    label = "qwen-dashscope",
                ),
            )
        }
        add(
            ThinkingContract(
                kind = ThinkingContract.Kind.OFFICIAL_VENDOR,
                scope = ThinkingContract.Scope.ModelPattern("*qwen*"),
                wireFormat = ThinkingWireFormat.QwenDual,
                label = "qwen-dashscope",
            ),
        )

        // 统一网关（Volcengine Ark / Azure / Venice）— iOS ba055121 + 84f5c9e1。
        // 它们在一个 OpenAI 面后重托管第三方家族，思考只认根级 reasoning_effort；
        // 厂商原生 thinking:{} 对象不被尊重，Venice 上未知根键直接 400。
        // 登记为一个概念而非三个旗标，让它们不会漂移。
        //
        // 排在 OpenAI 原生与 qwen 模式之后，恰好认领旧链 usesUnifiedReasoning
        // Effort 检查所认领的——deepseek-v4 分支与下面的自思考家族——不多不少。
        if (ctx.usesUnifiedReasoningEffort) {
            add(
                ThinkingContract(
                    kind = ThinkingContract.Kind.OFFICIAL_VENDOR,
                    scope = ThinkingContract.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                    label = "unified-gateway(ark|azure|venice)",
                ),
            )
        }

        // DeepSeek V4 厂商原生兄弟形态（iOS 847822eb, Android df776253）。仅在
        // 非统一网关时——上面的规则已认领那些。
        add(
            ThinkingContract(
                kind = ThinkingContract.Kind.OFFICIAL_VENDOR,
                scope = ThinkingContract.Scope.ModelPattern("*deepseek-v4*"),
                wireFormat = ThinkingWireFormat.DeepSeekSibling,
                reasoningEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY),
                label = "deepseek-v4-official",
            ),
        )

        // providerType 兜底：通用根级 reasoning_effort，受阶段 B 的自思考跳过
        // 约束。AllModels 保证阶段 A 恒命中。
        add(
            ThinkingContract(
                kind = ThinkingContract.Kind.PROVIDER_TYPE_DEFAULT,
                scope = ThinkingContract.Scope.AllModels,
                wireFormat = ThinkingWireFormat.ReasoningEffort(ctx.offEffort),
                label = "openai-compatible-default",
            ),
        )
    }

    /**
     * 解析并对一个请求落思考参数。请求体就地变更（调用点与被替换函数保持同一
     * 形状）；trace 返回供日志。
     */
    fun apply(body: JSONObject, ctx: ThinkingResolveContext): ThinkingResolveTrace {
        val before = body.keys().asSequence().toSet()

        // ---- 阶段 A：首命中胜出 ----
        // 用户自定义规则（存储序）排在内置表之上：自定义规则可先命中覆盖厂商
        // 默认，但永远移不掉内置规则。空自定义表使 rules == builtInRules(ctx)，
        // 与无自定义层逐字节一致。
        val rules = customRulesFor(ctx.instanceId) + builtInRules(ctx)
        val winner = rules.firstOrNull { it.scope.matches(ctx.modelId) }
            ?: return ThinkingResolveTrace(
                matchedRuleLabel = "none",
                matchedRuleKind = ThinkingContract.Kind.PROVIDER_TYPE_DEFAULT,
                formatSource = "no-match",
                emittedKeys = emptyList(),
            )

        // ---- 阶段 B：补齐规则留下的空位 ----
        var formatSource = "rule"
        val format = winner.wireFormat ?: run {
            formatSource = "providerTypeDefault"
            ThinkingWireFormat.ReasoningEffort(ctx.offEffort)
        }

        val clamp = emit(format, ctx, body)

        val emitted = body.keys().asSequence().toSet() - before
        return ThinkingResolveTrace(
            matchedRuleLabel = winner.label,
            matchedRuleKind = winner.kind,
            formatSource = formatSource,
            emittedKeys = emitted.toList(),
            clampedFrom = clamp.first,
            clampedTo = clamp.second,
        )
    }

    /**
     * 写出一个线形态的字段。每个分支逐字复刻被替换链的对应分支——含其中的
     * 守卫，守卫才是承重的证据部分。
     */
    private fun emit(
        format: ThinkingWireFormat,
        ctx: ThinkingResolveContext,
        body: JSONObject,
    ): Pair<String?, String?> {
        val lid = ctx.modelId.lowercase()

        // 严格枚举家族从不收关闭档：给 MiMo/Agnes 发 "minimal" 会杀死整个请求
        // （iOS c5efeb1e）。
        val strictEffortEnum = lid.contains("mimo") || lid.contains("agnes")
        val offEffort = if (strictEffortEnum) null else ctx.offEffort

        return when (format) {
            is ThinkingWireFormat.OmitEverything -> null to null

            is ThinkingWireFormat.ReasoningEffortNested -> {
                if (!ctx.level.isEnabled) return null to null
                val effort = wireEffort(ctx.level)
                body.put("reasoning", JSONObject().put("effort", effort))
                effort to effort
            }

            is ThinkingWireFormat.ReasoningEffort -> {
                val isOpenAINative = lid.startsWith("o") || lid.startsWith("gpt-5")
                if (!ctx.level.isEnabled) {
                    // OFF 在被替换链里是独立分派，逐字保留。次序承重：OpenAI 原生
                    // id 无条件发档位；自思考家族仅在端点是统一网关或机型声明该档
                    // 时发；其余在机型未声明不含它的集合时发。刻意不钳制——
                    // clampEffort 在「无更低声明」时向上走，["high","max"] 机型会把
                    // OFF 请求翻成 "high"，反转用户意图。
                    if (offEffort == null) return null to null
                    val isSelfReasoningFamily = listOf("deepseek", "glm", "kimi", "minimax")
                        .any { lid.contains(it) }
                    when {
                        isOpenAINative -> {
                            body.put("reasoning_effort", offEffort)
                            return offEffort to offEffort
                        }
                        isSelfReasoningFamily -> {
                            if (ctx.usesUnifiedReasoningEffort ||
                                ctx.declaredEffortValues?.contains(offEffort) == true
                            ) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        ctx.supportsReasoning != false -> {
                            if (ctx.declaredEffortValues?.contains(offEffort) != false) {
                                body.put("reasoning_effort", offEffort)
                                return offEffort to offEffort
                            }
                            return null to null
                        }
                        else -> return null to null
                    }
                }
                if (isOpenAINative) {
                    val effort = clampEffortForModel(wireEffort(ctx.level), lid)
                    body.put("reasoning_effort", effort)
                    return effort to effort
                }
                // 通用路径。遗留的自思考跳过仍键在「什么都没声明」而非家族名
                // ——iOS 22647505 在 GLM 走中继静默丢思考字段后以声明缺失替代了
                // id 子串跳过表。
                val declaresEffort = !ctx.declaredEffortValues.isNullOrEmpty()
                if (!ctx.usesUnifiedReasoningEffort && !declaresEffort &&
                    listOf("deepseek", "glm", "kimi", "minimax").any { lid.contains(it) }
                ) {
                    return null to null
                }
                // [OpenMinis#163] xAI 限定的跳过。grok-build-0.1 对 reasoning_effort
                // 回 "HTTP 400: Model grok-build-0.1 does not support parameter
                // reasoningEffort"；目录把该状态描述为 "reasoning": true +
                // "reasoning_options": []（grok-4.20-0309-reasoning 同形态）。
                //
                // 刻意不跨厂商数据驱动。同样的空档位形态出现在 2090 条内置目录
                // 条目上——poe/fastrouter/anyapi 后面的中继托管 Claude、GPT-5、
                // Qwen 与 grok——处处认它会一次性改变全部那些路线的线形态。对
                // 其中一些，省略字段可以说更正确（anthropic 用 thinking.
                // budget_tokens 不用 effort），但都没验证过，跳过停留在真正观测
                // 到 400 的厂商。以后放宽是对此条件的一行修改，配新证据。
                //
                // 刻意排在家族表之后：那张表键在「什么都没声明」，对目录沉默的
                // 中继托管 deepseek/glm/kimi/minimax id 必须继续生效。统一档位
                // 网关同样豁免——它们归一化该字段并自有模型表。
                if (ctx.isXAI && !ctx.usesUnifiedReasoningEffort &&
                    ctx.declaresNoEffortTiers && !declaresEffort
                ) {
                    return null to null
                }
                if (ctx.supportsReasoning == false) return null to null
                val requested = clampEffortForModel(wireEffort(ctx.level), lid)
                val clamped = clampEffort(requested, ctx.declaredEffortValues)
                body.put("reasoning_effort", clamped)
                requested to clamped
            }

            is ThinkingWireFormat.DeepSeekSibling -> {
                if (ctx.level.isEnabled) {
                    val requested = wireEffort(ctx.level)
                    val clamped = clampEffort(requested, ctx.declaredEffortValues)
                    body.put("thinking", JSONObject().put("type", "enabled"))
                    body.put("reasoning_effort", clamped)
                    requested to clamped
                } else {
                    body.put("thinking", JSONObject().put("type", "disabled"))
                    null to null
                }
            }

            is ThinkingWireFormat.QwenDual -> {
                // ANDROID 特有的 OFF 语义——不要与 iOS「对齐」除非有意变更行为。
                //
                // 被替换链的 OFF 走独立分派块，Qwen/DashScope 在该块里提前
                // return（保留原生 enable_thinking 机制、绝不改走 reasoning_
                // effort），OFF 时什么也不发。iOS 则每档都落入 qwen 分支，发
                // enable_thinking:false + null-budget extra_body。
                //
                // 两平台因此都把 Qwen 的思考关闭留给厂商默认，但线形态不同。
                // 本解析器是纯迁移，各平台保各自形态，金表分别钉住。统一它们
                // 是真实的 Phase 2 行为决策——这个差异直到快照存在才可见，本身
                // 就是建快照的理由。
                if (!ctx.level.isEnabled) return null to null

                val enabled = true
                var budget = when (ctx.level) {
                    ThinkingLevel.LOW -> 4096
                    ThinkingLevel.MEDIUM -> 16384
                    ThinkingLevel.HIGH -> 32768
                    ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 65536
                    ThinkingLevel.OFF -> 0
                }
                if (budget > 0 && ctx.maxTokens > 0) {
                    if (ctx.maxTokens < 2) {
                        budget = 0
                    } else {
                        val margin = maxOf(2048, ctx.maxTokens / 8)
                        val ceiling = maxOf(1, minOf(ctx.maxTokens - margin, ctx.maxTokens - 1))
                        if (budget >= ceiling) budget = ceiling
                    }
                }
                body.put("enable_thinking", enabled)
                if (budget > 0) body.put("thinking_budget", budget)
                body.put(
                    "extra_body",
                    JSONObject().apply {
                        put("enable_thinking", enabled)
                        // ANDROID 特有：无预算时省略该键，而非像 iOS 发显式
                        // JSON null。逐字保留；改动会变更病态 maxTokens<2 情形
                        // 的请求体。
                        if (budget > 0) put("thinking_budget", budget)
                    },
                )
                null to null
            }

            else -> {
                // 词表完整性形态（anthropic/gemini/布尔/嵌套/自定义路径）不经
                // 由 OpenAI 请求体落体——走到这里说明注册表命名了一个本路径产
                // 不出的形态：编程错误而非运行时条件。
                error("ThinkingWireFormat $format is not emitted on the OpenAI path")
            }
        }
    }

    // ---- Gemini / Anthropic 形状（各自请求体，不共享 OpenAI 形态）----
    //
    // 两家 provider 的请求体形态不同——Gemini 写 generationConfig.thinkingConfig，
    // Anthropic 自建 thinking 对象——故不路由经由 [apply]，而是把「形状决策」做成
    // 纯函数，各 provider 取所需。仍达成「一处拥有全部厂商思考合同」的目标，而
    // 不假装三种请求体是一种。两者行为均被快照测试逐字节钉死。
    //
    // ⚠️ 与 iOS 版在两处刻意不同（被替换的 Android 原件即如此）：3.x 在 MAX/
    // ULTRA 落 "low"（iOS 是 "high"）；未知/latest id 返回 null（iOS 是 128 地板
    // 回退表）。纯迁移——没有明确决策不要「对齐」。

    /**
     * Gemini 请求的 `generationConfig.thinkingConfig` 对象；机型完全不要思考配置
     * 时返回 null（专用 -tts/-image/-embedding/-vision 模态、2.5 Flash Lite、
     * 不匹配任何家族的 id）。
     */
    fun geminiThinkingConfig(modelId: String, level: ThinkingLevel): JSONObject? {
        // [T-gemini-tts-thinking-400 / OpenMinis#226] 专用模态优先于一切家族规则
        // 与请求档位：这些机型直接拒绝思考参数，发了就是 400（"Thinking level
        // is not supported for this model."）。
        //
        // 最先检查：这些 id 同样命中家族模式——gemini-3.1-flash-tts-preview 含
        // "gemini-3"，放后面会被遮蔽。Android 此前没有该测试，三个 Gemini TTS
        // 机型全部不可用；iOS 有一条但在家族分支之下，同样死代码。
        //
        // 整 id 小写化的理由与 iOS 相同：目录与线上 API 的大小写拼写不一致。
        // 上面的家族检查刻意保留原始 modelId 形态（纯迁移）。
        val lowerId = modelId.lowercase()
        val noThinkingSuffixes = listOf("-tts", "-image", "-embedding", "-vision")
        if (noThinkingSuffixes.any { lowerId.endsWith(it) || lowerId.contains("$it-") }) {
            return null
        }

        val isGemini3 = modelId.contains("gemini-3")
        val is25Pro = modelId.contains("gemini-2.5-pro")
        val is25Flash = modelId.contains("gemini-2.5-flash") && !modelId.contains("lite")
        val is25FlashLite = modelId.contains("gemini-2.5-flash-lite")

        if (is25FlashLite) return null

        return when {
            isGemini3 -> JSONObject().apply {
                if (level == ThinkingLevel.OFF) {
                    // 3.x Pro 不能全关；Flash 用 "minimal"、Pro 用 "low"。
                    put("thinkingLevel", if (modelId.contains("flash")) "minimal" else "low")
                } else {
                    put(
                        "thinkingLevel",
                        when (level) {
                            ThinkingLevel.LOW -> "low"
                            ThinkingLevel.MEDIUM -> "medium"
                            ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> "high"
                            else -> "low"
                        },
                    )
                    put("includeThoughts", true)
                }
            }
            is25Pro -> JSONObject().apply {
                put(
                    "thinkingBudget",
                    when (level) {
                        ThinkingLevel.OFF -> 128 // 最小值；0 被拒（df8a823d）
                        ThinkingLevel.LOW -> 2048
                        ThinkingLevel.MEDIUM -> 8192
                        ThinkingLevel.HIGH -> 16384
                        ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 32768
                    },
                )
                if (level.isEnabled) put("includeThoughts", true)
            }
            is25Flash -> JSONObject().apply {
                put(
                    "thinkingBudget",
                    when (level) {
                        ThinkingLevel.OFF -> 0
                        ThinkingLevel.LOW -> 1024
                        ThinkingLevel.MEDIUM -> 4096
                        ThinkingLevel.HIGH -> 8192
                        ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA -> 16384
                    },
                )
                if (level.isEnabled) put("includeThoughts", true)
            }
            else -> null
        }
    }

    /**
     * Anthropic 思考形态的小映射，调用方转成自家 thinking 对象：
     *   `{effort:"<tier>"}`   → adaptive thinking（Claude 4.6+）
     *   `{budget_tokens:N}`   → legacy 预算思考（≤4.5）
     *   `{disabled:true}`     → adaptive 机型在 OFF；必须显式——这些机型不发
     *                           thinking 字段时默认思考
     *   `{}`                  → 什么都不发
     *
     * 判定逻辑（版本解析 / adaptive 分线 / 预算钳制 / 档位折叠）自 P3.1c 起在
     * 自有 novex.model（AnthropicWire.thinkingShape）——本方法只做等级映射与
     * 委托。行为由快照测试钉死。
     */
    fun anthropicThinkingShape(
        modelId: String,
        supportsReasoning: Boolean?,
        level: ThinkingLevel,
        maxTokens: Int,
    ): Map<String, Any> {
        val wireLevel = if (level.isEnabled) novex.model.WireThinkingLevel.valueOf(level.name) else null
        return novex.model.AnthropicWire.thinkingShape(modelId, supportsReasoning, wireLevel, maxTokens)
    }

    /** UI 档位 → 线档位（机型钳制前）。 */
    fun wireEffort(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF, ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // ULTRA 是客户端「Max+编排」概念，绝不是合法服务端档位串（iOS b38bf3d5）。
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
    }

    /**
     * [T-android-xhigh-effort-clamp] MiMo/Agnes 拒 xhigh（400/422）；其梯子到
     * high 封顶。按家族子串匹配而非单一拼写——线上 API 是 mimo-v2.5 而文档写
     * mimo-2.5（iOS 72968c4f）。
     */
    fun clampEffortForModel(effort: String, lid: String): String =
        if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort

    /** 把请求档位吸附到机型声明集：先向下走，无更低取最低。 */
    fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }
}
