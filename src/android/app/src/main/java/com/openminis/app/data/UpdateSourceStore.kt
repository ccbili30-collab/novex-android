package com.openminis.app.data

import android.content.Context

/**
 * [T-dual-update-source]（用户 2026-09-27：「默认 gitee，增加切换更新源
 * 选项——github（海外）/gitee（国内）」）应用内更新检查的分发源。
 *
 * - GITEE（默认，国内直连）：读 novex-hub 的 update.json raw 链接
 *   （https://gitee.com/ccbili/novex），stable/preview 两通道独立字段；
 *   raw 是 302 到签名地址，客户端跟随重定向且不缓存跳转结果。
 * - GITHUB（海外）：既有 releases API + Atom 兜底链路，行为不变。
 *
 * 公告获取源跟随更新源（用户口径）；Gitee 侧公告体系后置，当前回落
 * 内置归档（NovexBulletinDefaults）。
 */
enum class UpdateSource(val wireName: String) {
    GITEE("gitee"),
    GITHUB("github"),
    ;

    companion object {
        fun fromWireName(value: String?): UpdateSource =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: GITEE
    }
}

/**
 * Persists the user's update-source choice. [hydrate] is called once from the
 * Application (cold start, before the first background check) so the cached
 * value is authoritative for [UpdateChecker.check]; without a Context the
 * default [UpdateSource.GITEE] applies.
 */
object UpdateSourceStore {
    private const val PREFS = "novex_update_source"
    private const val KEY = "source"

    @Volatile
    private var cached: UpdateSource = UpdateSource.GITEE

    fun hydrate(context: Context) {
        cached = UpdateSource.fromWireName(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
        )
    }

    fun current(): UpdateSource = cached

    fun set(context: Context, source: UpdateSource) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, source.wireName)
            .apply()
        cached = source
    }
}
