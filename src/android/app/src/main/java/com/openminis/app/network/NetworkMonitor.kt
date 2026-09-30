@file:Suppress("unused")

package com.openminis.app.network

/**
 * 连通性监视的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.netwatch.LinkMonitor]：NET_CAPABILITY_INTERNET
 * 监视、通断流转、网络切换时对共享池与登记客户端池的逐出。MinisApp 是
 * 唯一调用方（import 行）；本名保留以防外部引用，sharedLLMConnectionPool
 * 常量形状不变。
 */
typealias NetworkMonitor = novex.android.netwatch.LinkMonitor
