package novex.android.thinking

import novex.android.data.model.ThinkingLevel
import org.json.JSONObject

/**
 * 一次解析的全部输入。显式传参而非读 provider 状态——解析器保持纯函数，无网
 * 络桩即可全矩阵测试。vendor 判定由调用方从 base URL 预解（URL 嗅探收拢在
 * 适配器一处）。
 */
data class ThinkingResolveContext(
    val modelId: String,
    /** 所属实例 id；null = 只看内置座次表（与无自定义层逐字节一致）。 */
    val instanceId: String? = null,
    val supportsReasoning: Boolean?,
    val declaredEffortValues: List<String>?,
    /**
     * [OpenMinis#163] 目录肯定声明「会思考但不收档位参数」。与声明缺失（目录
     * 没听说过该机型）是两回事，只有肯定情形抑制字段；仅与 [isXAI] 联合生效。
     */
    val declaresNoEffortTiers: Boolean = false,
    val level: ThinkingLevel,
    val maxTokens: Int,
    val isOpenRouter: Boolean,
    val usesUnifiedReasoningEffort: Boolean,
    val isMistral: Boolean,
    val isDashScope: Boolean,
    /** 端点是 xAI 自家 API（api.x.ai）而非转发 grok 系机型的中转。 */
    val isXAI: Boolean = false,
    /** [P3.3 裁军→内置] 端点是内置「前尘 API」中转预设（proxy.qianc.ltd）：
     * gemini 系思考参数完全省略的内置席由该标志命中。 */
    val isQianchenRelay: Boolean = false,
    /** 端点成文的关闭档位；null = 关闭即省略字段（允许清单决策归调用方）。 */
    val offEffort: String?,
)

/**
 * 解析结论的检视面：命中的座次、形态来源、实际发出的键与档位钳制。用户可
 * 编辑的规则层若不可检视，只是把一个黑箱换成更复杂的黑箱。
 */
data class ThinkingResolveTrace(
    val matchedRuleLabel: String,
    val matchedRuleKind: ThinkingContract.Kind,
    val formatSource: String,
    val emittedKeys: List<String>,
    val clampedFrom: String? = null,
    val clampedTo: String? = null,
) {
    /** 单行日志形态（AppLogger("Thinking")）。 */
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
 * 解析一个请求该带的思考参数（P3.2 真重写版）。
 *
 * 架构分三层，与被删上游的单一 emit 巨型分派不同：
 *  1. 座次表 [SEATS]——内置厂商规则的唯一事实来源，表序即优先序；
 *  2. 决策层——vendorProfile/各 tier 判定为纯函数，只算「发不发、发什么」，
 *     不碰请求体；
 *  3. 落笔层——[imprint] 系列把决策写进请求体并报告钳制信息。
 * 决策与落笔分离使每条守卫可单测，也让 trace 的「值从哪来」可回答。
 *
 * 行为契约：与被删上游逐字节一致（金表快照钉死）；任何线形态差异都是回归。
 */
object ThinkingContractResolver {

    // [P3.3 裁军] 自定义规则登记层（customByInstance + setAllCustomRules/
    // setCustomRules/customRulesFor，进程级 CUSTOM 缓存）随 minis-config 体系
    // 随葬删除：resolver 只跑内置座次表；Room 表 provider_thinking_rules
    // 本体保留（schema 冻结），写路径与 UI 全摘。

    // ------------------------------------------------------------------
    // 内置座次表：表序即优先序，禁止按字母序整理
    // ------------------------------------------------------------------

    /**
     * 每个座位产出一条候选规则；null 表示该座位在本端点不设座。
     *
     * 承重排序事实：
     *  - Mistral 总禁令压一切（422 extra_forbidden）；
     *  - OpenAI 原生与 qwen 两族必须排在统一网关之上——曾把网关规则抬到它们
     *    之前，DashScope 上的 gpt-5 与 Ark 上的 qwen 静默换了形态（正是本设计
     *    要防的劣化，交叉行测试随后补上）；
     *  - AllModels 兜底垫底，保证阶段一永不落空。
     */
    private val SEATS: List<(VendorProfile) -> ThinkingContract?> = listOf(
        { p -> if (p.mistral) contract("mistral-official", ThinkingWireFormat.OmitEverything, echo = silentEcho) else null },
        // [P3.3 裁军] 前尘 API 中转预设的内置席：该中转会把我方思考参数错译
        // 成 Claude thinking 触发 400，gemini 系一律完全省略（原预设的
        // CUSTOM 规则收编为内置，行为逐字节一致）。
        { p -> if (p.qianchenRelay) patternSeat("gemini-*", "qianchen-relay-gemini", null, ThinkingWireFormat.OmitEverything) else null },
        { p -> if (p.openRouter) contract("openrouter", ThinkingWireFormat.ReasoningEffortNested(offValue = null)) else null },
        { p -> patternSeat("o*", "openai-native", p.offEffort) },
        { p -> patternSeat("gpt-5*", "openai-native", p.offEffort) },
        { p -> if (p.dashScope) contract("qwen-dashscope", ThinkingWireFormat.QwenDual, ThinkingContract.Scope.AllModels) else null },
        { _ -> patternSeat("*qwen*", "qwen-dashscope", null, ThinkingWireFormat.QwenDual) },
        { p -> if (p.unifiedGateway) contract("unified-gateway(ark|azure|venice)", ThinkingWireFormat.ReasoningEffort(p.offEffort), ThinkingContract.Scope.AllModels) else null },
        { _ -> patternSeat("*deepseek-v4*", "deepseek-v4-official", null, ThinkingWireFormat.DeepSeekSibling, echo = afterToolEcho) },
        { p -> defaultSeat(p.offEffort) },
    )

    private val silentEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.NEVER)
    private val afterToolEcho = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY)

    /** 内置规则表（UI 展示用物化）。 */
    fun builtInRules(ctx: ThinkingResolveContext): List<ThinkingContract> =
        SEATS.mapNotNull { it(vendorProfile(ctx)) }

    // ------------------------------------------------------------------
    // 阶段一 + 阶段二：选座 → 落笔
    // ------------------------------------------------------------------

    fun apply(body: JSONObject, ctx: ThinkingResolveContext): ThinkingResolveTrace {
        val preexisting = body.keys().asSequence().toSet()

        // [P3.3 裁军] 原 CUSTOM 席位在此优先于内置表；现仅内置座次表参选。
        val winner = builtInRules(ctx)
            .firstOrNull { it.scope.matches(ctx.modelId) }
            ?: return ThinkingResolveTrace(
                matchedRuleLabel = "none",
                matchedRuleKind = ThinkingContract.Kind.PROVIDER_TYPE_DEFAULT,
                formatSource = "no-match",
                emittedKeys = emptyList(),
            )

        var source = "rule"
        val shape = winner.wireFormat ?: ThinkingWireFormat.ReasoningEffort(ctx.offEffort).also { source = "providerTypeDefault" }
        val (from, to) = imprint(shape, ctx, body)

        return ThinkingResolveTrace(
            matchedRuleLabel = winner.label,
            matchedRuleKind = winner.kind,
            formatSource = source,
            emittedKeys = (body.keys().asSequence().toSet() - preexisting).toList(),
            clampedFrom = from,
            clampedTo = to,
        )
    }

    // ------------------------------------------------------------------
    // 落笔层：形状 → 请求体
    // ------------------------------------------------------------------

    private fun imprint(shape: ThinkingWireFormat, ctx: ThinkingResolveContext, body: JSONObject): Pair<String?, String?> =
        when (shape) {
            ThinkingWireFormat.OmitEverything -> NO_CLAMP

            is ThinkingWireFormat.ReasoningEffortNested -> nestedEffort(ctx, body)
            is ThinkingWireFormat.ReasoningEffort -> rootEffort(ctx, body)
            ThinkingWireFormat.DeepSeekSibling -> deepSeekSibling(ctx, body)
            ThinkingWireFormat.QwenDual -> qwenDual(ctx, body)

            // 词表登记形态不落 OpenAI 请求体；走到这里是注册表接线错误。
            else -> error("ThinkingWireFormat $shape is not emitted on the OpenAI path")
        }

    private fun nestedEffort(ctx: ThinkingResolveContext, body: JSONObject): Pair<String?, String?> {
        if (!ctx.level.isEnabled) return NO_CLAMP
        val tier = tierOf(ctx.level)
        body.put("reasoning", JSONObject().put("effort", tier))
        return tier to tier
    }

    /**
     * 根级 reasoning_effort——最复杂的形态，决策全部前置为纯函数：
     *  - 关闭路径：[offTierFor] 决定发不发关闭值（刻意不钳制：钳制向上走会把
     *    OFF 翻成 high，反转用户意图）；
     *  - 开启路径：原生线按机型折叠；通用线过三道守卫（自思考家族静默、xAI
     *    空档位跳过、目录判否），再按声明集钳制。
     */
    private fun rootEffort(ctx: ThinkingResolveContext, body: JSONObject): Pair<String?, String?> {
        val lowerId = ctx.modelId.lowercase()

        if (!ctx.level.isEnabled) {
            val tier = offTierFor(ctx, lowerId, strictOffValue(ctx, lowerId, ctx.offEffort))
            if (tier != null) body.put("reasoning_effort", tier)
            return tier?.let { it to it } ?: NO_CLAMP
        }

        if (isVendorNativeEffortLine(lowerId)) {
            val tier = familyClampedTier(ctx, lowerId)
            body.put("reasoning_effort", tier)
            return tier to tier
        }

        if (guardsOff(ctx, lowerId)) return NO_CLAMP

        val want = familyClampedTier(ctx, lowerId)
        val allowed = snapToDeclared(want, ctx.declaredEffortValues)
        body.put("reasoning_effort", allowed)
        return want to allowed
    }

    /**
     * 关闭值的发射决策（次序承重，语义与被删上游一致）：
     *  1. 原生线（o 前缀与 gpt-5 前缀）无条件发；
     *  2. 自思考家族（deepseek/glm/kimi/minimax）仅在统一网关或机型声明含该档
     *     时发——中继上的这些机型交给厂商默认；
     *  3. 其余在目录未否定（声明缺失或含该档）时发。
     */
    private fun offTierFor(ctx: ThinkingResolveContext, lowerId: String, offValue: String?): String? {
        if (offValue == null) return null
        val declared = ctx.declaredEffortValues
        return when {
            isVendorNativeEffortLine(lowerId) -> offValue
            lowerId inSelfFamily SELF_REASONING_FAMILIES ->
                if (ctx.usesUnifiedReasoningEffort || declared?.contains(offValue) == true) offValue else null
            ctx.supportsReasoning != false ->
                if (declared?.contains(offValue) != false) offValue else null
            else -> null
        }
    }

    /** MiMo/Agnes 严格枚举：关闭值一律吞掉（发 "minimal" 之类会杀死整个请求）。 */
    private fun strictOffValue(ctx: ThinkingResolveContext, lowerId: String, offValue: String?): String? =
        if (lowerId.contains("mimo") || lowerId.contains("agnes")) null else offValue

    /**
     * 通用线的三道守卫（与被删上游同口径）：
     *  a. 目录沉默 + 非统一网关的自思考家族——留给厂商默认（GLM 走中继静默丢
     *     思考字段的历史教训，跳过键改以「是否声明」而非家族名）；
     *  b. xAI 自家端点 + 目录肯定空档位 + 未声明——grok-build-0.1 的 400 实测；
     *     刻意不跨厂商：2090 条目录条目同形态，未验证的路线一个都不动；
     *  c. 目录判「不会思考」。
     */
    private fun guardsOff(ctx: ThinkingResolveContext, lowerId: String): Boolean {
        val declared = !ctx.declaredEffortValues.isNullOrEmpty()
        val selfFamilySilent = !ctx.usesUnifiedReasoningEffort && !declared &&
            lowerId inSelfFamily SELF_REASONING_FAMILIES
        val xaiEmptyTiers = ctx.isXAI && !ctx.usesUnifiedReasoningEffort && ctx.declaresNoEffortTiers && !declared
        return selfFamilySilent || xaiEmptyTiers || ctx.supportsReasoning == false
    }

    private fun deepSeekSibling(ctx: ThinkingResolveContext, body: JSONObject): Pair<String?, String?> {
        if (!ctx.level.isEnabled) {
            body.put("thinking", JSONObject().put("type", "disabled"))
            return NO_CLAMP
        }
        val want = tierOf(ctx.level)
        val allowed = snapToDeclared(want, ctx.declaredEffortValues)
        body.put("thinking", JSONObject().put("type", "enabled"))
        body.put("reasoning_effort", allowed)
        return want to allowed
    }

    /**
     * Qwen/DashScope 双写。关闭路径什么都不发（Android 口径：关闭交给厂商默认，
     * 与 iOS 的 enable_thinking:false 形态刻意不同，金表分别钉住）。
     * 预算必须严格小于输出上限（相等也被拒），按 maxTokens 相对取顶。
     */
    private fun qwenDual(ctx: ThinkingResolveContext, body: JSONObject): Pair<String?, String?> {
        if (!ctx.level.isEnabled) return NO_CLAMP
        val budget = qwenBudget(ctx.level, ctx.maxTokens)
        body.put("enable_thinking", true)
        if (budget > 0) body.put("thinking_budget", budget)
        val extra = JSONObject().put("enable_thinking", true)
        if (budget > 0) extra.put("thinking_budget", budget)
        body.put("extra_body", extra)
        return NO_CLAMP
    }

    /** 档位→预算表；再按 maxTokens 折顶（maxTokens<2 的病态输入给 0 不发键）。 */
    private fun qwenBudget(level: ThinkingLevel, maxTokens: Int): Int {
        val wanted = when (level) {
            ThinkingLevel.OFF -> 0
            ThinkingLevel.LOW -> 4_096
            ThinkingLevel.MEDIUM -> 16_384
            ThinkingLevel.HIGH -> 32_768
            else -> 65_536
        }
        if (wanted == 0 || maxTokens <= 0) return wanted
        if (maxTokens < 2) return 0
        val margin = maxOf(2_048, maxTokens / 8)
        val ceiling = maxOf(1, minOf(maxTokens - margin, maxTokens - 1))
        return if (wanted >= ceiling) ceiling else wanted
    }

    // ------------------------------------------------------------------
    // Gemini / Anthropic 形状（各自请求体；anthropic 判定在 novex.model）
    // ------------------------------------------------------------------

    /**
     * Gemini 的 generationConfig.thinkingConfig；机型不要思考配置时 null。
     *
     * 专用模态（-tts/-image/-embedding/-vision）优先于一切家族——这些 id 也含
     * 家族词，放后面会被遮蔽；它们对思考参数回硬 400。大小写口径沿被删上游：
     * 模态后缀按小写化 id 匹配，家族按原文匹配。
     */
    fun geminiThinkingConfig(modelId: String, level: ThinkingLevel): JSONObject? {
        val lowered = modelId.lowercase()
        if (SPECIALIZED_SUFFIXES.any { lowered.endsWith(it) || lowered.contains("$it-") }) return null
        return when (geminiFamilyOf(modelId, lowered)) {
            GeminiFamily.LITE -> null
            GeminiFamily.V3 -> v3Config(modelId, level)
            GeminiFamily.PRO_25 -> budgetConfig(level, GEMINI_PRO_BUDGETS, floor = 128)
            GeminiFamily.FLASH_25 -> budgetConfig(level, GEMINI_FLASH_BUDGETS, floor = 0)
            GeminiFamily.NONE -> null
        }
    }

    private fun v3Config(modelId: String, level: ThinkingLevel): JSONObject = JSONObject().apply {
        val flashLine = modelId.contains("flash")
        val tier = when {
            !level.isEnabled -> if (flashLine) "minimal" else "low"   // 3.x Pro 不能全关
            level == ThinkingLevel.LOW -> "low"
            level == ThinkingLevel.MEDIUM -> "medium"
            level in listOf(ThinkingLevel.HIGH, ThinkingLevel.XHIGH) -> "high"
            else -> "low"
        }
        put("thinkingLevel", tier)
        if (level.isEnabled) put("includeThoughts", true)
    }

    /** 预算族的档位表 + 必思考机型的地板（2.5 Pro 的 0 是非法值）。 */
    private fun budgetConfig(level: ThinkingLevel, table: Map<ThinkingLevel, Int>, floor: Int): JSONObject {
        val budget = maxOf(table[level] ?: floor, floor)
        return JSONObject()
            .put("thinkingBudget", budget)
            .apply { if (level.isEnabled) put("includeThoughts", true) }
    }

    private enum class GeminiFamily { V3, PRO_25, FLASH_25, LITE, NONE }

    private fun geminiFamilyOf(rawId: String, loweredId: String): GeminiFamily = when {
        rawId.contains("gemini-2.5-flash-lite") -> GeminiFamily.LITE
        rawId.contains("gemini-3") -> GeminiFamily.V3
        rawId.contains("gemini-2.5-pro") -> GeminiFamily.PRO_25
        rawId.contains("gemini-2.5-flash") -> GeminiFamily.FLASH_25
        else -> GeminiFamily.NONE
    }

    /**
     * Anthropic 思考形状的小映射（effort / budget_tokens / disabled / 空）。
     * 判定逻辑自 P3.1c 起在自有 novex.model（AnthropicWire.thinkingShape），此处
     * 只做档位映射与委托；快照测试钉行为。
     */
    fun anthropicThinkingShape(
        modelId: String,
        supportsReasoning: Boolean?,
        level: ThinkingLevel,
        maxTokens: Int,
    ): Map<String, Any> {
        val wireLevel = level.takeIf { it.isEnabled }?.let { novex.model.WireThinkingLevel.valueOf(it.name) }
        return novex.model.AnthropicWire.thinkingShape(modelId, supportsReasoning, wireLevel, maxTokens)
    }

    // ------------------------------------------------------------------
    // 档位词汇与钳制
    // ------------------------------------------------------------------

    /** UI 档位 → 线档位（机型钳制前）。ULTRA 是客户端编排概念，线上到 max 封顶。 */
    fun wireEffort(level: ThinkingLevel): String = tierOf(level)

    private val TIER_BY_LEVEL = mapOf(
        ThinkingLevel.OFF to "low",
        ThinkingLevel.LOW to "low",
        ThinkingLevel.MEDIUM to "medium",
        ThinkingLevel.HIGH to "high",
        ThinkingLevel.XHIGH to "xhigh",
        ThinkingLevel.MAX to "max",
        ThinkingLevel.ULTRA to "max",
    )

    private fun tierOf(level: ThinkingLevel): String = TIER_BY_LEVEL.getValue(level)

    /**
     * MiMo/Agnes 的梯子到 high 封顶（xhigh 回 400/422）；按家族子串匹配——线上
     * API 是 mimo-v2.5 而文档写 mimo-2.5。
     */
    fun clampEffortForModel(effort: String, lowerId: String): String =
        if (effort == "xhigh" && (lowerId.contains("mimo") || lowerId.contains("agnes"))) "high" else effort

    /** 原生线的档位：先家族折叠，再无声明集时不吸附。 */
    private fun familyClampedTier(ctx: ThinkingResolveContext, lowerId: String): String =
        clampEffortForModel(tierOf(ctx.level), lowerId)

    /**
     * 把请求档位吸附进声明集：先沿梯子向下找已声明的，一格都没有取声明集最低。
     * 实现为向下扫描而非索引排序——梯子本身就是顺序结构。
     */
    fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty() || values.contains(effort)) return effort
        val want = EFFORT_LADDER.indexOf(effort).takeIf { it >= 0 } ?: return effort
        for (step in want downTo 0) {
            values.firstOrNull { EFFORT_LADDER.indexOf(it) == step }?.let { return it }
        }
        return values.filter { it in EFFORT_LADDER }.minByOrNull { EFFORT_LADDER.indexOf(it) } ?: effort
    }

    private fun snapToDeclared(requested: String, values: List<String>?): String = clampEffort(requested, values)

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private val NO_CLAMP: Pair<String?, String?> = null to null
    private val SELF_REASONING_FAMILIES = arrayOf("deepseek", "glm", "kimi", "minimax")
    private val SPECIALIZED_SUFFIXES = arrayOf("-tts", "-image", "-embedding", "-vision")
    private val EFFORT_LADDER = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    private val GEMINI_PRO_BUDGETS = mapOf(
        ThinkingLevel.OFF to 128, ThinkingLevel.LOW to 2_048, ThinkingLevel.MEDIUM to 8_192,
        ThinkingLevel.HIGH to 16_384, ThinkingLevel.XHIGH to 32_768, ThinkingLevel.MAX to 32_768, ThinkingLevel.ULTRA to 32_768,
    )
    private val GEMINI_FLASH_BUDGETS = mapOf(
        ThinkingLevel.OFF to 0, ThinkingLevel.LOW to 1_024, ThinkingLevel.MEDIUM to 4_096,
        ThinkingLevel.HIGH to 8_192, ThinkingLevel.XHIGH to 16_384, ThinkingLevel.MAX to 16_384, ThinkingLevel.ULTRA to 16_384,
    )

    private infix fun String.inSelfFamily(families: Array<String>): Boolean = families.any(::contains)

    /** 原生档位线：宽 "o" 前缀逐字保留（收窄会改变一切 o 开头 id 的行为）。 */
    private fun isVendorNativeEffortLine(lowerId: String): Boolean =
        lowerId.startsWith("o") || lowerId.startsWith("gpt-5")

    private data class VendorProfile(
        val mistral: Boolean,
        val qianchenRelay: Boolean,
        val openRouter: Boolean,
        val dashScope: Boolean,
        val unifiedGateway: Boolean,
        val offEffort: String?,
    )

    private fun vendorProfile(ctx: ThinkingResolveContext) = VendorProfile(
        mistral = ctx.isMistral,
        qianchenRelay = ctx.isQianchenRelay,
        openRouter = ctx.isOpenRouter,
        dashScope = ctx.isDashScope,
        unifiedGateway = ctx.usesUnifiedReasoningEffort,
        offEffort = ctx.offEffort,
    )

    private fun contract(
        label: String,
        shape: ThinkingWireFormat,
        scope: ThinkingContract.Scope = ThinkingContract.Scope.AllModels,
        echo: ReasoningEchoPolicy? = null,
    ) = ThinkingContract(ThinkingContract.Kind.OFFICIAL_VENDOR, scope, shape, echo, label)

    private fun patternSeat(
        pattern: String,
        label: String,
        offValue: String?,
        shape: ThinkingWireFormat = ThinkingWireFormat.ReasoningEffort(offValue),
        echo: ReasoningEchoPolicy? = null,
    ) = ThinkingContract(
        kind = ThinkingContract.Kind.OFFICIAL_VENDOR,
        scope = ThinkingContract.Scope.ModelPattern(pattern),
        wireFormat = shape,
        reasoningEcho = echo,
        label = label,
    )

    private fun defaultSeat(offValue: String?) = ThinkingContract(
        kind = ThinkingContract.Kind.PROVIDER_TYPE_DEFAULT,
        scope = ThinkingContract.Scope.AllModels,
        wireFormat = ThinkingWireFormat.ReasoningEffort(offValue),
        label = "openai-compatible-default",
    )
}
