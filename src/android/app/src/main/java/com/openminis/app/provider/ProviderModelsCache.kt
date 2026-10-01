package com.openminis.app.provider

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import novex.android.data.model.LLMModel

/**
 * `/v1/models` 形态响应的按供应商磁盘缓存（血统清剿 P3.7 就地真重写；
 * 缓存路径 `models-cache/<namespace>/<sha256>.json`、Entry JSON 字段与
 * TTL 为契约冻结面）。键取凭据的 SHA-256——裸 API key / OAuth 令牌绝不落
 * 缓存文件名。对齐 iOS `ModelsCache`（7 天 TTL、cacheDir 作用域），但每家
 * 供应商各占一个子目录：轮换一家凭据不污染别家的缓存。
 *
 * 每家供应商构造一次（如 `ProviderModelsCache("openrouter")`），再以凭据
 * （+可选 baseURL）为键调 [load]/[save]/[invalidate]。
 */
internal class ProviderModelsCache(
    private val namespace: String,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    @Serializable
    private data class Entry(val models: List<LLMModel>, val savedAt: Long)

    private fun namespaceDir(context: Context): File =
        File(context.cacheDir, "models-cache/$namespace").apply { mkdirs() }

    /** 键 → 文件名：SHA-256 十六进制 + `.json`。 */
    private fun fileFor(context: Context, cacheKey: String): File {
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(cacheKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(namespaceDir(context), "$hex.json")
    }

    /** 读缓存：文件缺失/坏 JSON/过期一律 miss（null）。 */
    fun load(context: Context, cacheKey: String): List<LLMModel>? {
        val file = fileFor(context, cacheKey)
        if (!file.isFile) return null
        val entry = runCatching { JSON.decodeFromString<Entry>(file.readText()) }.getOrNull()
            ?: return null
        if (System.currentTimeMillis() - entry.savedAt > ttlMs) return null
        return entry.models
    }

    fun save(context: Context, cacheKey: String, models: List<LLMModel>) {
        runCatching {
            fileFor(context, cacheKey).writeText(
                JSON.encodeToString(Entry(models, System.currentTimeMillis())),
            )
        }
    }

    fun invalidate(context: Context, cacheKey: String) {
        runCatching { fileFor(context, cacheKey).delete() }
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24 * 3600 * 1000
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
