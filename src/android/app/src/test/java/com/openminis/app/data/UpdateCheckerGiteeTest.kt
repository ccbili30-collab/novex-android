package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-dual-update-source] Gitee 更新源的纯决策守护：update.json 的通道
 * 独立性（stable/preview 各读各的键）、版本比较、缺道/缺直链/坏 JSON
 * 的降级路径。网络层（302 跟随/不缓存跳转）由 okhttp 默认行为覆盖，
 * 不在本测范围。
 */
class UpdateCheckerGiteeTest {

    private fun hubJson(stable: String?, preview: String?): String {
        fun lane(v: String?) = v?.let {
            """{"version":"$it","channel":"x","date":"2026-09-27","notes":"n","download":"https://gitee.com/ccbili/novex/releases/download/v$it/novex.apk"}"""
        } ?: "null"
        return """{"_readme":"r","stable":${lane(stable)},"preview":${lane(preview)}}"""
    }

    @Test fun `newer stable lane yields update with hub url`() {
        val r = UpdateChecker.parseGiteeUpdate(hubJson(stable = "3.0.4", preview = null), UpdateChannel.STABLE, "3.0.2")
        assertTrue(r is UpdateChecker.CheckResult.UpdateAvailable)
        r as UpdateChecker.CheckResult.UpdateAvailable
        assertEquals("3.0.4", r.versionName)
        assertEquals("v3.0.4", r.tagName)
        assertTrue(r.apkUrl.endsWith("/v3.0.4/novex.apk"))
        assertEquals(false, r.isPrerelease)
    }

    @Test fun `preview build reads only the preview lane`() {
        // 预览/正式独立获取：正式道有新版、预览道空——预览构建不得看到
        val r = UpdateChecker.parseGiteeUpdate(hubJson(stable = "9.9.9", preview = null), UpdateChannel.PREVIEW, "3.0.5-beta.80")
        assertTrue(r is UpdateChecker.CheckResult.NoReleaseAvailable)
        // 反向：预览道高版本，正式构建同样不看
        val s = UpdateChecker.parseGiteeUpdate(hubJson(stable = null, preview = "3.0.5-beta.83"), UpdateChannel.STABLE, "3.0.4")
        assertTrue(s is UpdateChecker.CheckResult.NoReleaseAvailable)
    }

    @Test fun `preview lane upgrade keeps prerelease identity`() {
        val r = UpdateChecker.parseGiteeUpdate(hubJson(stable = null, preview = "3.0.5-beta.83"), UpdateChannel.PREVIEW, "3.0.5-beta.82")
        assertTrue(r is UpdateChecker.CheckResult.UpdateAvailable)
        r as UpdateChecker.CheckResult.UpdateAvailable
        assertEquals(true, r.isPrerelease)
        assertEquals("3.0.5-beta.83", r.versionName)
    }

    @Test fun `equal or older lane is up to date`() {
        assertEquals(
            UpdateChecker.CheckResult.UpToDate,
            UpdateChecker.parseGiteeUpdate(hubJson(stable = "3.0.4", preview = null), UpdateChannel.STABLE, "3.0.4"),
        )
        assertEquals(
            UpdateChecker.CheckResult.UpToDate,
            UpdateChecker.parseGiteeUpdate(hubJson(stable = "3.0.4", preview = null), UpdateChannel.STABLE, "9.9.9"),
        )
    }

    @Test fun `lane without download url surfaces no apk asset`() {
        val body = """{"stable":{"version":"3.0.5","notes":"n","download":""}}"""
        val r = UpdateChecker.parseGiteeUpdate(body, UpdateChannel.STABLE, "3.0.4")
        assertTrue(r is UpdateChecker.CheckResult.NoApkAsset)
        assertEquals("v3.0.5", (r as UpdateChecker.CheckResult.NoApkAsset).tagName)
    }

    @Test fun `malformed body is an error not a crash`() {
        assertTrue(UpdateChecker.parseGiteeUpdate("不是 JSON", UpdateChannel.STABLE, "3.0.4") is UpdateChecker.CheckResult.Error)
        // 空 lane 对象（version 缺失）按未发布处理
        val r = UpdateChecker.parseGiteeUpdate("""{"stable":{}}""", UpdateChannel.STABLE, "3.0.4")
        assertTrue(r is UpdateChecker.CheckResult.NoReleaseAvailable)
    }

    @Test fun `source enum defaults to gitee and parses wire names`() {
        // 未注水（无 Context 的进程级默认）也是 Gitee——用户口径：默认国内源
        assertEquals(UpdateSource.GITEE, UpdateSource.fromWireName(null))
        assertEquals(UpdateSource.GITEE, UpdateSource.fromWireName("nonsense"))
        assertEquals(UpdateSource.GITHUB, UpdateSource.fromWireName("GitHub"))
        assertEquals(UpdateSource.GITEE, UpdateSource.fromWireName(" gitee "))
    }
}
