package com.openminis.app.data

import android.content.Context

/**
 * Built-in text-style presets (文字文风预设) shipped under assets/textstyles/.
 *
 * The genre cards come verbatim from the MIT-licensed oh-story-claudecode
 * project (github.com/zenstory-ai/oh-story-claudecode); positive style
 * content stays community-authored — the app only packages and offers it.
 */
object TextStylePresets {

    data class Preset(val id: String, val label: String, val content: String)

    private const val ASSET_DIR = "textstyles/genre"
    private const val MANIFEST = "$ASSET_DIR/MANIFEST.txt"

    /** Cached list; assets are immutable at runtime so one read is enough. */
    @Volatile
    private var cached: List<Preset>? = null

    fun load(context: Context): List<Preset> {
        cached?.let { return it }
        val presets = runCatching {
            val names = context.assets.open(MANIFEST).bufferedReader().readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            names.mapNotNull { fileName ->
                val content = runCatching {
                    context.assets.open("$ASSET_DIR/$fileName").bufferedReader().use { it.readText() }
                }.getOrNull() ?: return@mapNotNull null
                // Files are ASCII-named for cross-runtime asset safety; the Chinese
                // display label comes from the frontmatter `genre:` field.
                val lines = content.lines()
                var label = fileName.removeSuffix(".md")
                var bodyStart = 0
                if (lines.firstOrNull()?.trim() == "---") {
                    val end = lines.indexOfFirst { it.trim() == "---" }
                    if (end > 0) {
                        lines.subList(1, end).forEach { line ->
                            val idx = line.indexOf(':')
                            if (idx > 0 && line.substring(0, idx).trim() == "genre") {
                                label = line.substring(idx + 1).trim()
                            }
                        }
                        bodyStart = end + 1
                    }
                }
                val body = lines.drop(bodyStart).joinToString("\n").trim()
                Preset(
                    id = fileName.removeSuffix(".md"),
                    label = label,
                    content = body,
                )
            }
        }.getOrDefault(emptyList())
        cached = presets
        return presets
    }
}
