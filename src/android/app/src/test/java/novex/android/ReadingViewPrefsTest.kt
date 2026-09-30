package novex.android

import novex.content.ReadingLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/** [T-reading-view-global] 视图偏好解析 fail-closed 到默认翻页。 */
class ReadingViewPrefsTest {
    @Test
    fun parseFallsBackToPagedAndAcceptsLegalValues() {
        assertEquals(ReadingLayout.PAGED, ReadingViewPrefs.parse(null))
        assertEquals(ReadingLayout.PAGED, ReadingViewPrefs.parse(""))
        assertEquals(ReadingLayout.PAGED, ReadingViewPrefs.parse("这不是一个模式"))
        assertEquals(ReadingLayout.CONTINUOUS, ReadingViewPrefs.parse("continuous"))
        assertEquals(ReadingLayout.CONTINUOUS, ReadingViewPrefs.parse(" CONTINUOUS "))
        assertEquals(ReadingLayout.PAGED, ReadingViewPrefs.parse("paged"))
    }
}
