@file:Suppress("unused")

package com.openminis.app.share

/**
 * 分享内存缓冲的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.sharekit.ShareBuffer]：300s TTL、合并去重、
 * 版本流与过期提示。processPendingShare / consumeBuffer / bufferVersion
 * 被 MainActivity / ChatScreen / AppNavigation 全限定名引用，属冻结面。
 */
typealias ShareCoordinator = novex.android.sharekit.ShareBuffer
