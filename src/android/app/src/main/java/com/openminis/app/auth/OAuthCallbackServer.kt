@file:Suppress("unused")

package com.openminis.app.auth

/**
 * 本机回环回调接收器的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.authkit.LoopbackReceiver]：端口梯队绑定、CORS
 * 预检应答、成功页 HTML、外部中止钩子的消费式语义均为冻结面。
 */
typealias OAuthCallbackServer = novex.android.authkit.LoopbackReceiver
