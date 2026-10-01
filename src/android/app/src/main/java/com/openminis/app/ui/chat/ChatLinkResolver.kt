package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.ui.sandbox.FileItem
import java.io.File
import novex.android.ContentPaths

/**
 * 聊天 markdown 里点链接的去向：可识别的 minis:// 深链 → 沙盒文件 →
 * 非 http(s) scheme 外跳系统应用 → 其余按网页（外跳系统浏览器）。
 */
sealed class ChatLinkAction {
    data class DeepLink(val action: DeepLinkAction) : ChatLinkAction()
    data class SandboxFile(val item: FileItem) : ChatLinkAction()
    data class ExternalApp(val url: String) : ChatLinkAction()
    data class Web(val url: String) : ChatLinkAction()
}

object ChatLinkResolver {

    fun resolve(rawUrl: String, sessionId: String? = null, context: Context? = null): ChatLinkAction {
        val url = rawUrl.trim()
        if (url.isEmpty()) return ChatLinkAction.Web(rawUrl)
        val scheme = runCatching { url.toUri() }.getOrNull()?.scheme?.lowercase()

        return asDeepLink(url, scheme)
            ?: asSandboxItem(url, scheme, sessionId, context)
            ?: asExternalApp(url, scheme)
            ?: ChatLinkAction.Web(url)
    }

    /** minis:// 且能解析出动作 → 深链；解析不出动作留给沙盒路径兜底。 */
    private fun asDeepLink(url: String, scheme: String?): ChatLinkAction? {
        if (scheme != "minis") return null
        return DeepLinkHandler.parse(url.toUri())
            .takeIf { it !is DeepLinkAction.Unknown }
            ?.let(ChatLinkAction::DeepLink)
    }

    /**
     * 链接指向沙盒内文件 → FileItem：
     *   minis://attachments/x.png    → /var/minis/attachments/x.png
     *   minis:///var/minis/ws/x.csv  → 绝对路径
     *   file:///data/x               → /data/x
     *   /var/minis/...、/root/...    → 经 bind mount / rootfs 解析
     *
     * 会话级解析优先：全局 bindMounts 后写覆盖，多会话设备上不带
     * sessionId 会指到最近一次起 shell 的会话。
     */
    private fun asSandboxItem(
        url: String,
        scheme: String?,
        sessionId: String?,
        context: Context?,
    ): ChatLinkAction? {
        val linuxPath = when (scheme) {
            "minis" -> {
                // 保留 '#'：附件名本身可能含井号，minis:// 不用 fragment。
                url.removePrefix("minis://").substringBefore('?')
                    .percentDecoded()
                    .let { if (it.startsWith("/")) it else "/var/minis/$it" }
            }
            "file" -> url.removePrefix("file://").substringBefore('?')
                .takeIf(String::isNotEmpty)?.percentDecoded()
            null -> url.takeIf { it.startsWith("/") }
            else -> null
        } ?: return null

        // file:// 给出的本来就是宿主路径；其余两种是沙盒 linux 路径，
        // 会话级解析优先（全局 bindMounts 后写覆盖，多会话设备上不带
        // sessionId 会指到最近一次起 shell 的会话）。
        val file = when {
            scheme == "file" -> File(linuxPath)
            sessionId != null && context != null ->
                ContentPaths.resolveSessionHostPath(sessionId, linuxPath, context)
            else -> ContentPaths.resolveHostPath(linuxPath)
        }
        return file
            ?.takeIf { it.exists() && it.isFile }
            ?.let(FileItem::from)
            ?.let(ChatLinkAction::SandboxFile)
    }

    private fun String.percentDecoded(): String =
        runCatching { java.net.URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    /** 非 http(s) scheme（intent/market/tel/mailto/geo…）外跳系统应用。 */
    private fun asExternalApp(url: String, scheme: String?): ChatLinkAction? =
        if (scheme != null && scheme != "http" && scheme != "https") {
            ChatLinkAction.ExternalApp(url)
        } else null

    /** 发系统 Intent，让 MainActivity 的 BROWSABLE 过滤器接住深链。 */
    fun dispatchDeepLink(context: Context, originalUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, originalUrl.toUri()).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
