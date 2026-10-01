package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局开关/确认位的集中存取处（血统清剿 P3.7：AutoCompactPrefs、
 * FastModePrefs、EnhancedCachePrefs、EnvVarPrivacyStore、MemoryGlobalPrefs
 * 五个 50–90 行小 prefs 件按任务书合并为自有单件；对象名与 prefs 名/键集
 * 逐字冻结，消费方零改动）。
 *
 * 共同形态：都是应用级（非按会话）的 SharedPreferences 布尔位，生命周期
 * 归 MinisApp.onCreate 统一预热。两类细分：
 *  - 请求构建路径要无 Context 读取的（AutoCompact/FastMode）：启动时抓
 *    applicationContext + 挥发缓存，任何请求装配点直接读缓存，翻转对
    进行中会话的下一次请求立即生效——含 offload/标题生成这类不经
 *    ChatViewModel 的调用；
 *  - 纯 UI 读写位（EnhancedCache/MemoryGlobal）：带 Context 直读直写即可。
 *
 * EnvVarPrivacyStore 特殊在热路径（脱敏器每段 shell 输出都要问一次）且
 * UI 需要可观察：用 StateFlow 承载，脱敏器读 .value 走同一条无锁路径。
 */

/** 「请求装配期生效」型开关的内脏：预热 + 挥发镜像（组合供各对象委托）。 */
private class PrimedSwitch(private val storeName: String, private val key: String) {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var mirror = false

    private fun store(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(storeName, Context.MODE_PRIVATE)

    /** MinisApp.onCreate 调：抓应用上下文并把持久值拉进缓存。幂等顺序由调用方保证。 */
    fun prime(context: Context) {
        appContext = context.applicationContext
        mirror = store(context).getBoolean(key, false)
    }

    /** 无 Context 读：预热前恒 false（= 全新安装未翻转），与持久缺省一致。 */
    fun isEnabled(): Boolean = mirror

    fun setEnabled(context: Context, enabled: Boolean) {
        mirror = enabled
        store(context).edit().putBoolean(key, enabled).apply()
    }
}

/**
 * 「越过阈值即自动压缩」全局开关。键沿 iOS 的
 * `autoCompactOnThreshold`（UserDefaults）逐字复用，双平台同一特性
 * grep 一个键名即可对上。语义：false（默认，对齐 iOS 裸 bool 的未置位
 * 值）= 越线先弹窗问用户；true = 越线静默压缩后直接发。刻意全局而非按
 * 会话：iOS 同样持久化，一次性点选的目的就是让未来的会话继承。
 */
object AutoCompactPrefs {
    private val switch = PrimedSwitch("minis_auto_compact_prefs", "autoCompactOnThreshold")

    fun prime(context: Context) = switch.prime(context)

    fun isEnabled(): Boolean = switch.isEnabled()

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)
}

/**
 * [T-codex-fast-mode] Fast Mode 全局开关（键沿 iOS `codexFastModeEnabled`，
 * commit fb671083）。与增强缓存不同，此旗跨会话持久共享（iOS 上用户确认
 * 过）：切到别的聊天读到的还是同一个翻转态。provider 层无 Context、且
 * iOS 刻意在请求装配期读取（翻转立即作用于进行中会话的下一次请求）——
 * 靠 [prime] 的应用上下文 + 挥发缓存复刻该语义。
 */
object FastModePrefs {
    private val switch = PrimedSwitch("minis_fast_mode_prefs", "codexFastModeEnabled")

    fun prime(context: Context) = switch.prime(context)

    fun isEnabled(): Boolean = switch.isEnabled()

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)
}

/**
 * [T-android-enhanced-cache] 增强缓存一次性确认位。开关本身的翻转态
 * 按会话内存存在（不持久化，对齐 iOS `AIChatViewModel.enhancedCache
 * Enabled`）；只有「用户看过并接受了额外计费警示」这个旗要持久——确认
 * 对话框每次安装恰好出现一次。键沿 iOS `enhancedCacheConfirmed`。
 */
object EnhancedCachePrefs {
    private const val STORE = "minis_enhanced_cache_prefs"
    private const val KEY_CONFIRMED = "enhancedCacheConfirmed"

    private fun store(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun isConfirmed(context: Context): Boolean =
        store(context).getBoolean(KEY_CONFIRMED, false)

    fun setConfirmed(context: Context) {
        store(context).edit().putBoolean(KEY_CONFIRMED, true).apply()
    }
}

/**
 * shell 输出「隐私模式」偏好：开启后 [EnvVarRedactor] 在文本回灌模型
 * 之前，把逐字出现在 shell 工具 stdout/stderr 里的环境变量值打码。用户
 * 在聊天里看到的仍是未打码原文——被改写的只是流进 agent 上下文的字节。
 * 默认开（除非用户显式关掉，密钥不进模型上下文）。键沿 iOS 同名件。
 */
object EnvVarPrivacyStore {
    private const val STORE = "envvar_privacy"
    private const val KEY = "privacy_mode_enabled"
    private const val DEFAULT_ON = true

    private var store: SharedPreferences? = null

    private val flag = MutableStateFlow(DEFAULT_ON)

    /** UI 可观察态。 */
    val enabled: StateFlow<Boolean> = flag.asStateFlow()

    /** 脱敏器热路径读：任何工作开始前问一次。 */
    val isEnabled: Boolean get() = flag.value

    /** MinisApp.onCreate 早调一次；幂等。 */
    fun init(context: Context) {
        if (store != null) return
        val opened = context.applicationContext
            .getSharedPreferences(STORE, Context.MODE_PRIVATE)
        store = opened
        flag.value = if (opened.contains(KEY)) opened.getBoolean(KEY, DEFAULT_ON) else DEFAULT_ON
    }

    /** UI 翻转：即时持久 + 流广播。 */
    fun setEnabled(value: Boolean) {
        store?.edit()?.putBoolean(KEY, value)?.apply()
        flag.value = value
    }
}

/**
 * 记忆特性全局默认位（两层模型的全局层，对齐 iOS）：
 *  - **全局**（本件）：新会话的默认值，设置 → 记忆 里翻转。键
 *    `memory.global.enabled`，Novex 默认 false——无关世界不得互相渗漏；
 *  - **按会话**（`SessionRow.memory_enabled`）：具体聊天的覆盖位，经
 *    `/memory` 或 SessionMemorySheet 翻转。会话一旦落行，行值即赢；改
 *    全局永不回溯已存在的会话。
 *
 * 新草稿 VM 在 DB 行尚未物化时挂载：读全局默认播种 `_memoryEnabled`；
 * `ensureSession` 物化该行时把同值传给
 * `ChatRepository.createSession(memoryEnabled = …)`，DB 快照与种子一致。
 */
object MemoryGlobalPrefs {
    private const val STORE = "minis_memory_prefs"
    private const val KEY_GLOBAL = "memory.global.enabled"

    private fun store(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun isGlobalEnabled(context: Context): Boolean =
        store(context).getBoolean(KEY_GLOBAL, false)

    fun setGlobalEnabled(context: Context, enabled: Boolean) {
        store(context).edit().putBoolean(KEY_GLOBAL, enabled).apply()
    }
}
