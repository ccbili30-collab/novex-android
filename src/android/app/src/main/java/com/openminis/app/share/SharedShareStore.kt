@file:Suppress("unused")

package com.openminis.app.share

/**
 * 盘上收件箱的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.sharekit.ShareInbox]：prefs share_prefs /
 * pending_share、share_extension 暂存目录、300s/50 条合并窗。调用方以
 * 全限定名引用本名。
 */
typealias SharedShareStore = novex.android.sharekit.ShareInbox
