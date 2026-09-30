@file:Suppress("unused")

package com.openminis.app.share

/**
 * 会话 zip 导出的旧路径门面（P3.5c 真重写收编）。
 *
 * 实现在 [novex.android.sharekit.ChatZipExporter]：分页流式转录、
 * export-staging 暂存、shared/ 成品与 FileProvider 交出。包内布局与
 * zip 命名为冻结面；SessionListScreen 以全限定名调 exportToZip。
 */
typealias ChatExporter = novex.android.sharekit.ChatZipExporter
