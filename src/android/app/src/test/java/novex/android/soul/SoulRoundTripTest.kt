package novex.android.soul

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SOUL.md 存取回路（P3.5c 真重写的行为验收）：
 *  - ensureExists 播种缺省文件、再跑不覆盖用户改动；
 *  - load ↔ save 往返（frontmatter 三键，emoji 行写入即淘汰）；
 *  - Nova 改名迁移只跑一次、只认 Minis/Novex 旧名；
 *  - 旧出厂正文的风格映射（出厂投影换成现行缺省，用户改过的不动）；
 *  - 身份缓存在 save/refreshCache 后同步。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class SoulRoundTripTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `ensureExists seeds default content and is idempotent`() {
        SoulRepository.ensureExists(context)
        val seeded = SoulRepository.load(context)
        assertNotNull(seeded)
        assertEquals("Nova", seeded!!.metadata.name)
        assertEquals("auto", seeded.metadata.lang)
        assertTrue(SoulRepository.fileLocation(context).readText().contains("name: \"Nova\""))

        // 用户改名后再次 ensureExists 不得覆盖。
        SoulRepository.save(context, SoulDocument(SoulIdentity(name = "自定义", emoji = "", style = "", lang = "zh"), "我的正文"))
        SoulRepository.ensureExists(context)
        assertEquals("自定义", SoulRepository.load(context)!!.metadata.name)
    }

    @Test
    fun `save load round trip keeps three keys and drops emoji line`() {
        val doc = SoulDocument(
            metadata = SoulIdentity(name = "Nova", emoji = "🙂", style = "简洁", lang = "en"),
            body = "line1\n\nline2",
        )
        SoulRepository.save(context, doc)
        val raw = SoulRepository.fileLocation(context).readText()

        assertTrue(raw.startsWith("---\n"))
        assertTrue(raw.contains("name: \"Nova\""))
        assertTrue(raw.contains("style: \"简洁\""))
        assertTrue(raw.contains("lang: \"en\""))
        assertTrue("emoji 行写入即淘汰", !raw.contains("emoji:"))

        val back = SoulRepository.load(context)!!
        assertEquals("Nova", back.metadata.name)
        assertEquals("简洁", back.metadata.style)
        assertEquals("en", back.metadata.lang)
        assertEquals("line1\n\nline2\n", back.body)
    }

    @Test
    fun `missing file loads null and cache falls back to default`() {
        SoulRepository.fileLocation(context).delete()
        assertNull(SoulRepository.load(context))
        SoulRepository.refreshCache(context)
        assertEquals(SoulIdentity.DEFAULT.name, SoulRepository.cachedMetadata.value.name)
    }

    @Test
    fun `cached metadata follows save`() {
        SoulRepository.save(
            context,
            SoulDocument(SoulIdentity(name = "Nova", emoji = "", style = "", lang = "auto"), "b"),
        )
        assertEquals("Nova", SoulRepository.cachedMetadata.value.name)
    }

    @Test
    fun `legacy assistant names migrate once and leave custom names alone`() {
        assertEquals("name: \"Nova\"", migrateLegacyAssistantName("name: \"Minis\""))
        assertEquals("name: \"Nova\"", migrateLegacyAssistantName("name: \"Novex\""))
        assertEquals("name: \"自定义角色\"", migrateLegacyAssistantName("name: \"自定义角色\""))

        // 带旧名的文件首跑迁移，二跑（账本已记）不再动用户改回的名字。
        SoulRepository.fileLocation(context).parentFile?.mkdirs()
        SoulRepository.fileLocation(context).writeText("---\nname: \"Minis\"\n---\n\n正文")
        SoulRepository.ensureExists(context)
        assertEquals("Nova", SoulRepository.load(context)!!.metadata.name)

        SoulRepository.fileLocation(context).writeText("---\nname: \"Minis\"\n---\n\n正文")
        SoulRepository.ensureExists(context)
        // 迁移账本已记，用户（或外部）写回的名字保持原样。
        assertEquals("Minis", SoulRepository.load(context)!!.metadata.name)
    }

    @Test
    fun `legacy factory default body maps to current default projection`() {
        assertEquals(
            SoulFrontmatterCodec.parse(SoulRepository.DEFAULT_CONTENT).body.trim(),
            SoulRepository.currentDefaultStyle(SoulRepository.LEGACY_NOVEX_DEFAULT_BODY),
        )
        // 用户改过一字都不动。
        assertEquals("自定义正文", SoulRepository.currentDefaultStyle("自定义正文"))
    }

    @Test
    fun `length verdict stays Ok on realistic bodies`() {
        assertTrue(SoulRepository.isOverLimit("").isOverLimit.not())
        assertTrue(SoulRepository.isOverLimit("   \n ").isOverLimit.not())
        assertTrue(SoulRepository.isOverLimit("这是一段中文正文。").isOverLimit.not())
        assertTrue(SoulRepository.isOverLimit("An english body with words.").isOverLimit.not())
    }
}
