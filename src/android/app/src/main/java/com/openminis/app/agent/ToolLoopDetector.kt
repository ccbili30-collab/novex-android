package com.openminis.app.agent

import com.openminis.app.logging.AppLogger
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单条工具调用的滑动窗记录，两段式填充（血统清剿 P3.7 就地真重写；四策略
 * 优先级、消息文案、warningKey 形态、阈值语义、正则表均为行为契约冻结面，
 * ToolLoopDetectorTest 全量钉死）：
 *  - `check()` 只读不写——执行前看历史；
 *  - `record()` 追加一条，并从刚结束的执行里回填 `resultHash`/`unknownToolName`。
 */
data class ToolCallRecord(
    val toolName: String,
    val argsHash: String,
    val resultHash: String? = null,
    val unknownToolName: String? = null,
    val toolCallId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class Level { NONE, WARNING, CRITICAL }

data class LoopCheckResult(
    val level: Level,
    val message: String? = null,
    val warningKey: String? = null,
) {
    companion object {
        val NONE = LoopCheckResult(Level.NONE)
    }
}

/**
 * 阈值契约：warningThreshold < criticalThreshold < globalCircuitBreakerThreshold。
 * 构造期强制——配错的探测器不能被悄悄带上线。
 */
data class ToolLoopConfig(
    val historySize: Int = 30,
    val warningThreshold: Int = 10,
    val unknownToolThreshold: Int = 10,
    val criticalThreshold: Int = 20,
    val globalCircuitBreakerThreshold: Int = 30,
) {
    init {
        require(warningThreshold > 0) { "warningThreshold must be positive" }
        require(warningThreshold < criticalThreshold) {
            "warningThreshold ($warningThreshold) must be < criticalThreshold ($criticalThreshold)"
        }
        require(criticalThreshold < globalCircuitBreakerThreshold) {
            "criticalThreshold ($criticalThreshold) must be < globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
        require(historySize >= globalCircuitBreakerThreshold) {
            "historySize ($historySize) must be >= globalCircuitBreakerThreshold ($globalCircuitBreakerThreshold)"
        }
    }
}

/**
 * agent 工具调用循环检测器：识别四类循环，发警告或硬熔断（完整行为规格见
 * fix_tool_loop_detection.md）。
 *
 * 策略优先级（高→低）：
 *   1. unknown_tool_repeat       —— 连续幻觉工具错误。
 *   2. global_circuit_breaker    —— 同参数+同结果，任意工具累计 ≥30。
 *   3. known_poll_no_progress    —— 轮询型工具结果冻结。
 *   4. generic_repeat            —— 同参数 ≥10 次，无论结果。
 *
 * 每会话一个实例。非线程安全——经 agent 循环既有的单线程分发串行调用。
 */
class ToolLoopDetector(private val config: ToolLoopConfig = ToolLoopConfig()) {

    private val window = ArrayDeque<ToolCallRecord>()

    /** warningKey → 已发过的桶序号：节流用，同一警告不逐次挂在每个工具结果上。 */
    private val emittedBucketByKey = HashMap<String, Int>()

    /** 会话重置 / 新开聊天时清掉全部在途状态。 */
    fun reset() {
        window.clear()
        emittedBucketByKey.clear()
    }

    /** 仅测试用的窗口检视。 */
    internal fun historySnapshot(): List<ToolCallRecord> = window.toList()

    // ─── 执行前钩子 ─────────────────────────────────────────────────────────

    /**
     * 拿即将执行的工具对滑动窗问路。`result.level == CRITICAL` 时调用方必须
     * 跳过执行并把 `result.message` 作为工具错误结果呈现。
     */
    fun check(toolName: String, params: Map<String, Any?>): LoopCheckResult {
        val args = fingerprintArgs(toolName, params)
        val pollStyle = pollsByDesign(toolName, params)

        // 1. unknown_tool_repeat：最特异的信号最先跑。
        unknownToolStreak(toolName).let { streak ->
            if (streak >= config.unknownToolThreshold) {
                val msg = "[LOOP BLOCKED] CRITICAL: attempted unavailable tool '$toolName' " +
                    "$streak times. Stop retrying that missing tool and answer without it."
                AppLogger.warning("ToolLoopDetector",
                    "CRITICAL unknown_tool_repeat tool=$toolName streak=$streak")
                return LoopCheckResult(Level.CRITICAL, msg)
            }
        }

        val frozenStreak = frozenResultStreak(toolName, args)

        // 2. global_circuit_breaker：全局兜底先于轮询专项——失控的非轮询
        //    循环才不会从更低的专项阈值下溜过去。
        if (frozenStreak >= config.globalCircuitBreakerThreshold) {
            val msg = "[LOOP BLOCKED] CRITICAL: $toolName has repeated identical " +
                "no-progress outcomes $frozenStreak times. Session execution " +
                "blocked by global circuit breaker."
            AppLogger.warning("ToolLoopDetector",
                "CRITICAL global_circuit_breaker tool=$toolName streak=$frozenStreak")
            return LoopCheckResult(Level.CRITICAL, msg)
        }

        // 3. known_poll_no_progress：轮询工具的熔断线更紧——无进展的轮询
        //    就是最典型的浪费。
        if (pollStyle) {
            if (frozenStreak >= config.criticalThreshold) {
                val msg = "[LOOP BLOCKED] CRITICAL: Called $toolName $frozenStreak " +
                    "times with identical no-progress results. Session execution blocked."
                AppLogger.warning("ToolLoopDetector",
                    "CRITICAL known_poll_no_progress tool=$toolName streak=$frozenStreak")
                return LoopCheckResult(Level.CRITICAL, msg)
            }
            if (frozenStreak >= config.warningThreshold) {
                return LoopCheckResult(
                    Level.WARNING,
                    pollWarningText(toolName, frozenStreak),
                    "poll:$toolName:$args",
                ).also {
                    AppLogger.debug("ToolLoopDetector",
                        "WARNING known_poll_no_progress tool=$toolName streak=$frozenStreak")
                }
            }
        }

        // 4. generic_repeat：仅非轮询工具；计非连续命中——时好时坏但总体
        //    在前进的调用最终会滑出窗口。
        if (!pollStyle) {
            val repeats = countSameArgs(toolName, args)
            if (repeats >= config.warningThreshold) {
                return LoopCheckResult(
                    Level.WARNING,
                    genericWarningText(toolName, repeats),
                    "repeat:$toolName:$args",
                ).also {
                    AppLogger.debug("ToolLoopDetector",
                        "WARNING generic_repeat tool=$toolName count=$repeats")
                }
            }
        }

        return LoopCheckResult.NONE
    }

    // ─── 执行后钩子 ─────────────────────────────────────────────────────────

    /**
     * 把刚完成的调用追加进窗口，并判断本轮要不要在工具结果上附加警告。
     * CRITICAL 只由 `check()` 在调用*前*报——`record()` 至多返回 NONE 或
     * WARNING（规格如此）。
     */
    fun record(
        toolName: String,
        params: Map<String, Any?>,
        result: String?,
        errorMessage: String? = null,
        toolCallId: String? = null,
    ): LoopCheckResult {
        val args = fingerprintArgs(toolName, params)
        window.addLast(ToolCallRecord(
            toolName = toolName,
            argsHash = args,
            resultHash = fingerprintOutcome(result, errorMessage),
            unknownToolName = hallucinatedToolName(errorMessage),
            toolCallId = toolCallId,
        ))
        while (window.size > config.historySize) window.removeFirst()

        // 记录后在最新窗口上重估 poll/generic 警告——警告文案要反映当前
        // 计数（含刚追加的这次）。
        val streak = frozenResultStreak(toolName, args)
        if (pollsByDesign(toolName, params)) {
            if (streak in config.warningThreshold until config.criticalThreshold) {
                val key = "poll:$toolName:$args"
                if (throttleWarning(key, streak)) {
                    return LoopCheckResult(Level.WARNING, pollWarningText(toolName, streak), key)
                }
            }
        } else {
            val repeats = countSameArgs(toolName, args)
            if (repeats >= config.warningThreshold) {
                val key = "repeat:$toolName:$args"
                if (throttleWarning(key, repeats)) {
                    return LoopCheckResult(Level.WARNING, genericWarningText(toolName, repeats), key)
                }
            }
        }
        return LoopCheckResult.NONE
    }

    // ─── 策略原语 ───────────────────────────────────────────────────────────

    private fun pollWarningText(toolName: String, count: Int): String =
        "[LOOP WARNING] You have called $toolName $count times " +
            "with no progress. Stop polling and either (1) increase wait " +
            "time, or (2) report the task as failed."

    private fun genericWarningText(toolName: String, count: Int): String =
        "[LOOP WARNING] You have called $toolName $count times " +
            "with identical arguments. If this is not making progress, stop " +
            "retrying and report the task as failed."

    /**
     * 从尾向头数连续多少条记录是冲着同一个幻觉名去的未知工具错误；遇到
     * 第一条解析不出 unknownToolName 的、或目标换了名字的，即断。
     */
    private fun unknownToolStreak(toolName: String): Int {
        var streak = 0
        for (rec in window.reversed()) {
            val hallucination = rec.unknownToolName ?: break
            if (hallucination == toolName) streak++ else break
        }
        return streak
    }

    /**
     * 新→旧数「同 (toolName, argsHash) 且共享同一 resultHash」的记录数。
     * 别的工具的记录跳过（不断流）；目标工具的结果一变即断——那正是
     * 「观察到进展」的信号。
     */
    private fun frozenResultStreak(toolName: String, args: String): Int {
        var streak = 0
        var pinned: String? = null
        for (rec in window.reversed()) {
            if (rec.toolName != toolName || rec.argsHash != args) continue
            val outcome = rec.resultHash ?: break
            val frozen = pinned
            if (frozen == null) {
                pinned = outcome
                streak++
            } else if (outcome == frozen) {
                streak++
            } else {
                break
            }
        }
        return streak
    }

    /** 窗口内同 (toolName, argsHash) 的总条数（不要求连续）。 */
    private fun countSameArgs(toolName: String, args: String): Int =
        window.count { it.toolName == toolName && it.argsHash == args }

    /** 轮询型工具判定：command_status 全形态 + process 的 poll/log 动作。 */
    private fun pollsByDesign(toolName: String, params: Map<String, Any?>): Boolean {
        if (toolName == "command_status") return true
        if (toolName == "process") {
            when ((params["action"] as? String)?.lowercase()) {
                "poll", "log" -> return true
            }
        }
        return false
    }

    /**
     * 同键警告按 `warningThreshold` 次一档节流：30 连发的循环只在第
     * 10/20/30 次各响一次，越线后的每个工具回合不再重复响。
     */
    private fun throttleWarning(warningKey: String, currentCount: Int): Boolean {
        val bucket = currentCount / config.warningThreshold
        if (emittedBucketByKey[warningKey] == bucket) return false
        emittedBucketByKey[warningKey] = bucket
        return true
    }

    // ─── 散列 / 解析原语 ────────────────────────────────────────────────────

    /**
     * 参数指纹。先剥掉模型随手变化的纯 UI/遥测字段——尤其是 `tool_title`，
     * 模型惯常给它加计数后缀（"Read X #1"、"#2"……）。不过滤的话每次逻辑
     * 等价的调用都散列出唯一值，重复/熔断策略全部静默失灵。
     */
    private fun fingerprintArgs(toolName: String, params: Map<String, Any?>): String {
        val meaningful = params.filterKeys { it !in ARGS_HASH_IGNORED_KEYS }
        return digest("$toolName:${CanonicalJson.encode(meaningful)}")
    }

    /**
     * 只对承载成败的部分做结果指纹。刻意把整段输出也折进去——规格要求剥
     * 时间戳/请求 id 之类的噪声，但本层的工具结果不带这些（底层工具已自
     * 行清理）。未来哪个工具开始漏易变字段，在这里剪，别去每个调用点剪。
     */
    private fun fingerprintOutcome(result: String?, errorMessage: String?): String =
        digest("err=" + (errorMessage ?: "") + "out=" + (result ?: ""))

    /**
     * 两种措辞覆盖各供应商的变体（大小写不敏感，引号与首尾空白均可容忍）：
     *   "unknown tool: foobar"     → 组 1 = "foobar"
     *   "tool 'foobar' not found"  → 组 1 = "foobar"
     */
    private fun hallucinatedToolName(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return null
        return UNKNOWN_TOOL_RE_1.find(errorMessage)?.groupValues?.getOrNull(1)
            ?: UNKNOWN_TOOL_RE_2.find(errorMessage)?.groupValues?.getOrNull(1)
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * 不参与参数指纹的键。现在只有 `tool_title`（必需的 UI 标签，模型
         * 常加计数后缀）。将来再有纯装饰性字段漏进参数，往这里加。
         */
        private val ARGS_HASH_IGNORED_KEYS: Set<String> = setOf("tool_title")

        private val UNKNOWN_TOOL_RE_1 = Regex(
            """unknown tool[:\s]+["']?([a-zA-Z0-9_.\-]+)["']?""",
            RegexOption.IGNORE_CASE,
        )
        private val UNKNOWN_TOOL_RE_2 = Regex(
            """tool\s+["']?([a-zA-Z0-9_.\-]+)["']?\s+(?:not found|is not available)""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * 键序稳定的 JSON 编码：每层嵌套都按字母排序——`<"b"→2, "a"→1>` 与
 * `<"a"→1, "b"→2>` 散列一致。org.json 的 JSONObject/JSONArray 先解包成
 * Kotlin 容器再编码，避免其无序键表渗进指纹。
 */
private object CanonicalJson {

    fun encode(value: Any?): String = StringBuilder().also { writeTo(value, it) }.toString()

    private fun writeTo(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Map<*, *> -> {
                out.append('{')
                value.entries
                    .map { it.key?.toString().orEmpty() to it.value }
                    .sortedBy { it.first }
                    .forEachIndexed { i, (k, v) ->
                        if (i > 0) out.append(',')
                        out.append(JSONObject.quote(k)).append(':')
                        writeTo(v, out)
                    }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    writeTo(v, out)
                }
                out.append(']')
            }
            is Array<*> -> writeTo(value.toList(), out)
            is String -> out.append(JSONObject.quote(value))
            is Number, is Boolean -> out.append(value.toString())
            is JSONObject -> writeTo(plainMap(value), out)
            is JSONArray -> writeTo(plainList(value), out)
            else -> out.append(JSONObject.quote(value.toString()))
        }
    }

    private fun plainMap(obj: JSONObject): Map<String, Any?> {
        val unpacked = HashMap<String, Any?>(obj.length())
        val keyIter = obj.keys()
        while (keyIter.hasNext()) {
            val k = keyIter.next()
            unpacked[k] = unwrapPlain(obj.get(k))
        }
        return unpacked
    }

    private fun plainList(arr: JSONArray): List<Any?> =
        (0 until arr.length()).map { unwrapPlain(arr.get(it)) }

    private fun unwrapPlain(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONObject -> plainMap(v)
        is JSONArray -> plainList(v)
        else -> v
    }
}
