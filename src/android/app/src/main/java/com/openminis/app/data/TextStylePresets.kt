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
                // Strip the YAML frontmatter; the body is the prompt itself.
                val body = if (content.startsWith("---")) {
                    content.lines().dropWhile { it.trim() != "---" }.drop(1).joinToString("\n").trim()
                } else content.trim()
                Preset(
                    id = fileName.removeSuffix(".md"),
                    label = fileName.removeSuffix(".md"),
                    content = body,
                )
            }
        }.getOrDefault(emptyList())
        cached = presets
        return presets
    }
}
