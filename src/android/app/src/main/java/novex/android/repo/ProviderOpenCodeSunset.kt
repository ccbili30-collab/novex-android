package novex.android.repo

import novex.android.data.model.ProviderConfig

/**
 * OpenCode Zen 下线（T-opencode-sunset，2026-09-26）：上游免费档已对第三方
 * 调用服务端 403，两个内置免费实例整体下线 —— 禁用实例并隐藏其模型条目，
 * 数据一律不删（下线保持可逆），已绑定这些实例的会话得到专门的 403 文案。
 *
 * 纯函数、无 Context/Room 依赖，可独立单测；ID 内联于此（对应的 API 客户端
 * 已退役），迁移与列表过滤据此识别存量用户配置里的残留实例。
 */

private val OPENCODE_FREE_INSTANCE_IDS = setOf(
    "builtin-opencode-free-chat",
    "builtin-opencode-free-responses",
)

fun isOpenCodeFreeInstanceId(instanceId: String): Boolean =
    instanceId in OPENCODE_FREE_INSTANCE_IDS

/** 原位、幂等：禁用残留实例、隐藏其条目；返回是否有变更（调用方决定落盘）。 */
fun applyOpenCodeSunset(config: ProviderConfig): Boolean {
    var touched = false
    config.instances.forEachIndexed { index, instance ->
        if (isOpenCodeFreeInstanceId(instance.id) && instance.isEnabled) {
            config.instances[index] = instance.copy(isEnabled = false)
            touched = true
        }
    }
    config.modelEntries.forEachIndexed { index, entry ->
        if (isOpenCodeFreeInstanceId(entry.providerInstanceId) && !entry.isHidden) {
            config.modelEntries[index] = entry.copy(isHidden = true)
            touched = true
        }
    }
    return touched
}
