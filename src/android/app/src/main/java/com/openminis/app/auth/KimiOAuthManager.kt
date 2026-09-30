@file:Suppress("unused")

package com.openminis.app.auth

/**
 * Kimi 登录流的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.authkit.KimiLoginFlow]：设备码申请与轮询、
 * meta-data client id 覆写、刷新单飞 + 先比对再删除。令牌包内
 * device_id / last_refresh 字段与 5 分钟刷新窗为冻结面。
 */
typealias KimiOAuthManager = novex.android.authkit.KimiLoginFlow
