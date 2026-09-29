package novex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * [T-stage3-snapshot] 世界快照守护：解析（合法 JSON/前后包裹噪音容忍/非法
 * null）、状态锚一行版（空快照 null/三字段拼接）、持久化 latest+历史链
 * 往返、损坏静默 null。
 */
class NovexStateSnapshotTest {

    @get:Rule val temporary = TemporaryFolder()

    @Test fun `parse accepts clean and noisy json`() {
        val clean = NovexStateSnapshot.parse(
            """{"time":"嘉靖四十年三月","location":"北镇抚司","main":"查西苑传闻","characters":"费千户：好感83","threads":"严嵩倒台倒计时53月"}""", 1L)
        assertNotNull(clean)
        assertEquals("嘉靖四十年三月", clean!!.timeAnchor)
        // 模型常在 JSON 前后带说明文字——substringAfter('{') 容忍
        val noisy = NovexStateSnapshot.parse(
            "好的，这是快照：\n{\"time\":\"崇祯三年\",\"location\":\"南京\",\"main\":\"主线\",\"characters\":\"\",\"threads\":\"\"}\n以上。", 2L)
        assertNotNull(noisy)
        assertEquals("崇祯三年", noisy!!.timeAnchor)
    }

    @Test fun `parse rejects garbage`() {
        assertNull(NovexStateSnapshot.parse("不是 JSON", 1L))
        assertNull(NovexStateSnapshot.parse("""{"time":"","location":"","main":"","characters":"","threads":""}""", 1L))
    }

    /**
     * [T-snapshot-prompt-degrade] 提示词契约（2026-09-27 用户裁决：非游戏
     * 会话的快照退化走提示词方案，不做代码守卫）。全空串在 parse 层已有
     * null 拒绝（上一测），这里锁住提示词里的退化条款本身——重写措辞时
     * 弄丢"空串而非'无'/不编造"任一句，本测即红。
     */
    @Test fun `prompt carries non game degrade clause`() {
        val text = NovexStateSnapshot.prompt(null, "摘要", "近期原文", null)
        org.junit.Assert.assertTrue(text.contains("并非进行中的游戏"))
        org.junit.Assert.assertTrue(text.contains("空字符串"))
        org.junit.Assert.assertTrue(text.contains("不要填\"无\""))
        org.junit.Assert.assertTrue(text.contains("编造成剧情"))
    }

    @Test fun `anchor line joins non blank fields`() {
        assertNull(NovexStateSnapshot.anchorLine(null))
        val snap = NovexStateSnapshot.Snapshot("嘉靖四十年三月", "北镇抚司", "", "", "", "{}", 1L)
        assertEquals("嘉靖四十年三月 · 北镇抚司", NovexStateSnapshot.anchorLine(snap))
    }

    @Test fun `latest snapshot persists and round trips`() {
        val dir = temporary.newFolder()
        // 借同一目录结构验证读写往返（不走 context.filesDir——构造 File 直接验证）
        val snapshot = NovexStateSnapshot.parse(
            """{"time":"t1","location":"l1","main":"m1","characters":"c1","threads":"th1"}""", 42L)!!
        val json = org.json.JSONObject(snapshot.rawJson).put("savedAt", 42L).toString()
        val file = java.io.File(java.io.File(dir, "snapshots"), "latest.json")
        file.parentFile.mkdirs()
        file.writeText(json)
        val loaded = NovexStateSnapshot.parse(file.readText(), org.json.JSONObject(file.readText()).optLong("savedAt"))
        assertNotNull(loaded)
        assertEquals("t1", loaded!!.timeAnchor)
        assertEquals(42L, loaded.createdAt)
    }
}
