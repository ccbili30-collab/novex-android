package novex.android

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Host-side resolution for guest-visible `/var/minis/...` paths.
 *
 * Extracted from PRootKernel (upstream-exit P2.5 · R1) so the surviving
 * consumers — skills/memory reads, image fetch, markdown asset rendering,
 * attachment tools — keep resolving content paths after the PRoot sandbox is
 * retired. Ownership of the bind-mount table moves here; PRootKernel delegates
 * to this object during the grey window and the sandbox-only rootfs fallback
 * is an explicitly-registered directory that goes away with R2.
 *
 * Resolution rules (unchanged from the PRootKernel implementation):
 *  - `/var/minis/{attachments,offloads,workspace,browser}/...` are per-session
 *    and resolve directly under `filesDir/minis-sessions/<sessionId>/...`,
 *    bypassing the mount table;
 *  - everything else resolves by longest-prefix match against [bindMounts];
 *  - no match falls back to [rootfsFallbackDir] when the sandbox is booted,
 *    else null.
 */
object ContentPaths {
    private const val TAG = "ContentPaths"

    /** Bind mounts: Linux path -> host filesystem path. Longest prefix wins. */
    val bindMounts: MutableMap<String, String> = linkedMapOf()

    /**
     * Sandbox-root fallback for guest paths outside `/var/minis/`. Assigned by
     * PRootKernel.boot() while the sandbox exists; stays null (and every
     * non-mounted path resolves to null) once it is retired.
     */
    @Volatile
    var rootfsFallbackDir: File? = null

    private val perSessionSubdirs = setOf("attachments", "offloads", "workspace", "browser")

    fun addBindMount(linuxPath: String, hostPath: String) {
        bindMounts[linuxPath] = hostPath
    }

    fun removeBindMount(linuxPath: String) {
        bindMounts.remove(linuxPath)
    }

    fun clearBindMounts() {
        bindMounts.clear()
    }

    /**
     * Register the global (session-independent) content mounts so direct file
     * I/O can resolve `/var/minis/{memory,skills,shared,mcp-servers}/...`
     * without the sandbox being booted. Safe to call repeatedly.
     */
    fun registerGlobalMounts(context: Context) {
        val globalBase = File(context.filesDir, "minis-global")
        listOf("memory", "skills", "shared", "mcp-servers").forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            bindMounts["/var/minis/$subdir"] = hostDir.absolutePath
        }
        Log.d(TAG, "global content mounts registered: ${bindMounts.size} entries")
    }

    /**
     * Resolve a session-scoped `/var/minis/...` path to its host directory.
     * Falls back to [resolveHostPath] for paths outside the per-session
     * subdirs (memory/skills/shared do not depend on sessionId).
     */
    fun resolveSessionHostPath(sessionId: String, linuxPath: String, context: Context): File? {
        if (!linuxPath.startsWith("/var/minis/")) return resolveHostPath(linuxPath)
        val rest = linuxPath.removePrefix("/var/minis/")
        val slash = rest.indexOf('/')
        val subdir = if (slash < 0) rest else rest.substring(0, slash)
        if (subdir !in perSessionSubdirs) return resolveHostPath(linuxPath)
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId/$subdir")
        val tail = if (slash < 0) "" else rest.substring(slash + 1)
        return if (tail.isEmpty()) sessionBase else File(sessionBase, tail)
    }

    /**
     * Resolve a Linux path to a host filesystem File by checking bind mounts
     * (longest prefix match), then the sandbox-root fallback. Returns null
     * when nothing matches.
     */
    fun resolveHostPath(linuxPath: String): File? {
        for (mountPoint in bindMounts.keys.sortedByDescending { it.length }) {
            if (linuxPath == mountPoint || linuxPath.startsWith("$mountPoint/")) {
                val hostBase = bindMounts[mountPoint]!!
                val relativePath = linuxPath.removePrefix(mountPoint).removePrefix("/")
                return if (relativePath.isEmpty()) File(hostBase) else File(hostBase, relativePath)
            }
        }
        val fallback = rootfsFallbackDir ?: return null
        val stripped = linuxPath.removePrefix("/")
        return if (stripped.isEmpty()) fallback else File(fallback, stripped)
    }
}
