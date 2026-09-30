package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.data.repository.ProviderRepository
import novex.android.thinking.ThinkingContract
import novex.android.thinking.ThinkingContractCoding
import org.json.JSONObject

/**
 * minis-config 暴露面：把用户自定义的思考契约（[ThinkingContract]）映射成
 * `thinkingrules.<instanceId>:<ruleId>.<field>` 路径族。
 *
 * 设计约束（与既有数据格式兼容，不因本文件演进而破坏）：
 *  - 子节点 id 用 `<instanceId>:<ruleId>` 复合形式；instance id 是 UUID 不含
 *    冒号，因此**首个冒号**即分隔符，两段都必须非空；
 *  - 只枚举 CUSTOM 规则——内置规则不成为子节点，就没有可写路径，「内置不可
 *    改」由结构保证而非运行时判断；remove 对 `builtin:` 前缀显式拒绝，报错
 *    引导用户用「在其上方新增规则」来覆盖；
 *  - 新增规则落在列表顶端（优先级最高）——排在被覆盖目标之下的覆盖规则没
 *    有意义。
 */
class ThinkingContractsCollection(
    private val repo: ProviderRepository,
) : ConfigCollection {

    override val basePath: String get() = "thinkingrules"
    override val displayName: String get() = "Thinking rules"
    override val description: String get() =
        "User-authored rules that decide which thinking parameters an OpenAI-compatible provider sends."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = true
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    /** 一条已被定位的自定义规则：属于哪个实例、用什么 id 存回。 */
    private data class Located(val instanceId: String, val ruleId: String, val rule: ThinkingContract)

    /** 全部自定义规则的复合 id，按「实例 × 规则」展开。 */
    override fun childIds(): List<String> =
        repo.config.value.instances.flatMap { inst ->
            repo.thinkingContractIds(inst.id).map { ruleId -> "${inst.id}:$ruleId" }
        }

    /**
     * 复合 id → 定位信息。找不到（id 残缺、实例/规则已被删）返回 null，
     * 由调用方决定是隐藏字段还是报「不存在」。
     */
    private fun locate(childId: String): Located? {
        val sep = childId.indexOf(':')
        if (sep <= 0 || sep == childId.length - 1) return null
        val instanceId = childId.substring(0, sep)
        val ruleId = childId.substring(sep + 1)
        val index = repo.thinkingContractIds(instanceId).indexOf(ruleId)
        val rule = index.takeIf { it >= 0 }?.let { repo.thinkingContracts(instanceId).getOrNull(it) }
            ?: return null
        return Located(instanceId, ruleId, rule)
    }

    /** 读当前值；规则已消失时给 [fallback]（字段整体隐藏的场景用不到）。 */
    private fun current(childId: String): ThinkingContract? = locate(childId)?.rule

    /** 集中「定位失败即拒绝」的写路径：所有 writer 都经此落盘。 */
    private inline fun mutate(childId: String, message: String, edit: (ThinkingContract) -> ThinkingContract) {
        val located = locate(childId) ?: throw ConfigError.InvalidValue(message)
        repo.saveThinkingContract(located.instanceId, edit(located.rule), id = located.ruleId)
    }

    override fun fields(forId: String): List<ConfigField> {
        if (locate(forId) == null) return emptyList()
        val instanceId = forId.substringBefore(':')
        return listOf(
            labelField(forId),
            scopeField(forId),
            wireFormatField(forId),
            providerField(forId, instanceId),
        )
    }

    private fun labelField(childId: String) = ClosureField(
        path = "$basePath.$childId.label",
        displayName = "Label",
        description = "Human-readable name shown in the rule list and the resolution trace.",
        valueSchema = ConfigSchema.Str(),
        reader = { ConfigValue.Str(current(childId)?.label ?: "") },
        writer = { v ->
            val text = (v as? ConfigValue.Str)?.value ?: throw ConfigError.InvalidValue("a string is required")
            mutate(childId, "rule no longer exists") { it.copy(label = text) }
        },
    )

    private fun scopeField(childId: String) = ClosureField(
        path = "$basePath.$childId.scope",
        displayName = "Scope",
        description = "\"all\" for every model, or a glob pattern like \"deepseek-v4*\".",
        valueSchema = ConfigSchema.Str(),
        risk = ConfigRisk.SENSITIVE,
        reader = {
            val scope = current(childId)?.scope
            ConfigValue.Str((scope as? ThinkingContract.Scope.ModelPattern)?.pattern ?: "all")
        },
        writer = { v ->
            val text = (v as? ConfigValue.Str)?.value ?: throw ConfigError.InvalidValue("a string is required")
            val scope = text.toScope()
            mutate(childId, "rule no longer exists") { it.copy(scope = scope) }
        },
    )

    private fun wireFormatField(childId: String) = ClosureField(
        path = "$basePath.$childId.wireFormat",
        displayName = "Wire format",
        description = "JSON {\"type\":\"reasoning_effort\",\"offValue\":\"none\"} etc. — how the thinking control appears on the wire.",
        valueSchema = ConfigSchema.Json,
        risk = ConfigRisk.SENSITIVE,
        reader = {
            looseObject(ThinkingContractCoding.encodeWireFormat(current(childId)?.wireFormat))
        },
        writer = { v ->
            val fmt = ThinkingContractCoding.decodeWireFormat(v.toJsonText())
                ?: throw ConfigError.InvalidValue("unrecognized wire format JSON")
            mutate(childId, "rule no longer exists") { it.copy(wireFormat = fmt) }
        },
    )

    private fun providerField(childId: String, instanceId: String) = ReadOnlyField(
        path = "$basePath.$childId.provider",
        displayName = "Provider",
        description = "The provider instance this rule belongs to.",
        valueSchema = ConfigSchema.Str(),
        reader = {
            val label = repo.config.value.instances.find { it.id == instanceId }?.label ?: instanceId
            ConfigValue.Str("$label ($instanceId)")
        },
    )

    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected JSON object")
        val instanceId = obj.stringOf("provider")
            ?: throw ConfigError.InvalidValue("`provider` (instance id) required")
        if (repo.config.value.instances.none { it.id == instanceId }) {
            throw ConfigError.InvalidValue("provider instance not found: $instanceId")
        }
        val label = obj.stringOf("label")
            ?: throw ConfigError.InvalidValue("`label` required")
        val fmt = ThinkingContractCoding.decodeWireFormat(
            (obj["wire_format"] as? ConfigValue)?.toJsonText()
                ?: throw ConfigError.InvalidValue("`wire_format` required (JSON object)"),
        ) ?: throw ConfigError.InvalidValue("unrecognized wire_format")

        val ruleId = repo.saveThinkingContract(
            instanceId,
            ThinkingContract(
                kind = ThinkingContract.Kind.CUSTOM,
                scope = (obj.stringOf("scope") ?: "all").toScope(),
                wireFormat = fmt,
                label = label,
            ),
            id = null, // null = 新增，落列表顶端（最高优先级）
        )
        return "$instanceId:$ruleId"
    }

    override fun remove(id: String) {
        when {
            id.startsWith("builtin:") -> throw ConfigError.PermissionDenied(
                "Built-in rules are part of the app and cannot be removed. Add a rule above one to override it.",
            )
            else -> {
                val sep = id.indexOf(':')
                if (sep <= 0 || sep == id.length - 1) {
                    throw ConfigError.InvalidValue("bad rule id: $id")
                }
                repo.dropRule(id.substring(0, sep), id.substring(sep + 1))
            }
        }
    }

    // ---- 值域与 JSON 的松散互转 ----

    /** "all"（忽略大小写）或空白 → 全模型；其余文本视为 glob。 */
    private fun String.toScope(): ThinkingContract.Scope =
        if (equals("all", ignoreCase = true) || isBlank()) ThinkingContract.Scope.AllModels
        else ThinkingContract.Scope.ModelPattern(this)

    private fun Map<String, ConfigValue>.stringOf(key: String): String? =
        (this[key] as? ConfigValue.Str)?.value

    /** ConfigValue → JSON 文本：Str 原样；Obj 递归组装；其余退化成字符串。 */
    private fun ConfigValue?.toJsonText(): String = when (this) {
        null -> throw ConfigError.InvalidValue("`wire_format` required (JSON object)")
        is ConfigValue.Str -> value
        is ConfigValue.Obj -> JSONObject().also { out ->
            value.forEach { (k, v) ->
                when (v) {
                    is ConfigValue.Str -> out.put(k, v.value)
                    is ConfigValue.Int -> out.put(k, v.value)
                    is ConfigValue.Obj -> out.put(k, JSONObject(v.toJsonText()))
                    else -> out.put(k, v.toString())
                }
            }
        }.toString()
        else -> toString()
    }

    /** JSON 文本 → ConfigValue：能解析就展平成 Obj（值转字符串），否则原样 Str。 */
    private fun looseObject(json: String?): ConfigValue {
        if (json.isNullOrBlank()) return ConfigValue.Str("")
        return try {
            val parsed = JSONObject(json)
            ConfigValue.Obj(buildMap {
                for (key in parsed.keys()) put(key, ConfigValue.Str(parsed.get(key).toString()))
            })
        } catch (_: Exception) {
            ConfigValue.Str(json)
        }
    }
}
