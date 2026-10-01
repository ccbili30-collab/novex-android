package com.openminis.app.provider

import com.openminis.app.BuildConfig
import okhttp3.Request

/**
 * [T-provider-custom-user-agent] 各供应商 User-Agent 覆盖的唯一咽喉点
 * （血统清剿 P3.7 就地真重写；覆盖/回落规则与 UA 串格式冻结）。每家
 * provider/models-api 的请求构建器在 `.build()` 前调它，「null/空白 →
 * 回落，非空白 → 覆盖」的规则只活在这一处。
 *
 * `.header(...)` 会替换构建器先前设的任何值（如 Codex 的 `codex_cli_rs/…`
 * 或 Anthropic OAuth 的 `claude-cli/…`）——非空白覆盖永远赢过供应商默认。
 *
 * **回落策略（T-android-default-ua）**：`customUserAgent` 为 null/空白时
 * 不再让构建器裸奔（否则 OkHttp 会自塞 `okhttp/4.12.0`），而是套上
 * [MinisUserAgent.DEFAULT]——默认 UA 带 Minis 版本号，上游请求日志能溯源
 * 到发请求的应用构建，正合「除特定客户端身份外一律品牌 UA」的特性意图。
 *
 * 以**特定客户端身份**发请求的调用方（Codex OAuth 的 `codex_cli_rs/…`、
 * Anthropic OAuth 的 `claude-cli/…`）必须传 `defaultUserAgent = null`，
 * 让本函数不要碰它们已设好的 UA 头。其余场合默认生效。
 */
fun Request.Builder.applyUserAgentOverride(
    customUserAgent: String?,
    defaultUserAgent: String? = MinisUserAgent.DEFAULT,
): Request.Builder {
    val override = customUserAgent?.trim()
    return when {
        !override.isNullOrEmpty() -> header("User-Agent", override)
        // 保留构建器已有的 UA（OAuth 路径的 CLI 指纹就靠这条路活着）。
        defaultUserAgent != null -> header("User-Agent", defaultUserAgent)
        else -> this
    }
}

/**
 * [T-android-default-ua] 品牌 User-Agent：所有没有专属 SDK UA 要求的
 * Minis 出站请求统一用它。
 *
 * 格式与 iOS 完全同构：
 *   `Minis/<version> (Android <release>; <model>)`
 * 如 `Minis/0.14-preview (Android 13; Pixel 4a)`——对应 iOS 的
 * `Minis/1.10 (iOS 26.5; iPhone)`。
 *
 * 版本取 BuildConfig（每次发版自动跟进）。系统版本取
 * `Build.VERSION.RELEASE`（用户认得的营销版本号；不取 SDK_INT——iOS 没有
 * 这条平行轴，保持对齐）。机型取 `Build.MODEL`——默认 OkHttp UA 本来就
 * 广播它，做设备侧诊断有用，又不会额外去匿名化用户。
 *
 * 惰性构建——首个请求才拼串，`Build.*` 读取（便宜但走 JNI）不占启动时间。
 */
object MinisUserAgent {
    val DEFAULT: String by lazy {
        val osRelease = android.os.Build.VERSION.RELEASE ?: "unknown"
        val device = (android.os.Build.MODEL ?: "unknown").trim().ifEmpty { "unknown" }
        "Minis/${BuildConfig.VERSION_NAME} (Android $osRelease; $device)"
    }
}
