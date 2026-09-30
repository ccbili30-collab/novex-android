package novex.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** P2.5 R1：路径解析器从 PRootKernel 抽出后的行为钉（JVM 可测部分）。 */
class ContentPathsTest {
    @After fun clean() {
        ContentPaths.bindMounts.clear()
        ContentPaths.rootfsFallbackDir = null
    }

    @Test fun longestPrefixMountWins() {
        ContentPaths.addBindMount("/var/minis/skills", "/host/global/skills")
        ContentPaths.addBindMount("/var/minis/skills/references", "/host/other/refs")
        assertEquals(
            File("/host/other/refs/organizing-rules.md"),
            ContentPaths.resolveHostPath("/var/minis/skills/references/organizing-rules.md"),
        )
    }

    @Test fun exactMountPointResolvesToBaseItself() {
        ContentPaths.addBindMount("/var/minis/memory", "/host/global/memory")
        assertEquals(File("/host/global/memory"), ContentPaths.resolveHostPath("/var/minis/memory"))
        assertEquals(
            File("/host/global/memory/notes.md"),
            ContentPaths.resolveHostPath("/var/minis/memory/notes.md"),
        )
    }

    @Test fun unmountedPathIsNullWithoutFallback() {
        ContentPaths.addBindMount("/var/minis/skills", "/host/global/skills")
        assertNull(ContentPaths.resolveHostPath("/etc/passwd"))
        assertNull(ContentPaths.resolveHostPath("/var/other/thing.txt"))
    }

    @Test fun registeredFallbackResolvesNonMountedPaths() {
        ContentPaths.rootfsFallbackDir = File("/host/rootfs")
        assertEquals(File("/host/rootfs"), ContentPaths.resolveHostPath("/"))
        assertEquals(File("/host/rootfs/etc/passwd"), ContentPaths.resolveHostPath("/etc/passwd"))
    }

    @Test fun removedMountNoLongerResolves() {
        ContentPaths.addBindMount("/var/minis/shared", "/host/global/shared")
        ContentPaths.removeBindMount("/var/minis/shared")
        assertNull(ContentPaths.resolveHostPath("/var/minis/shared/x.txt"))
    }
}
