package works.eub.voicememo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoUtilsTest {

    @Test
    fun generateTitle_shortContent_noEllipsis() {
        val title = MemoUtils.generateTitle("Hello")
        assertEquals("Hello", title)
    }

    @Test
    fun generateTitle_exactly30Chars_noEllipsis() {
        val content = "a".repeat(30)
        val title = MemoUtils.generateTitle(content)
        assertEquals(content, title)
        assertEquals(30, title.length)
    }

    @Test
    fun generateTitle_31Chars_withEllipsis() {
        val content = "a".repeat(31)
        val title = MemoUtils.generateTitle(content)
        assertEquals("a".repeat(30) + "...", title)
        assertEquals(33, title.length)
    }

    @Test
    fun generateTitle_longContent_truncates() {
        val content = "운전 중에 꼭 사야 할 것 목록을 정리해야겠다. 핸드폰 충전기, 차량용 거치대"
        val title = MemoUtils.generateTitle(content)
        assertTrue(title.endsWith("..."))
        assertEquals(33, title.length)
    }

    @Test
    fun generateTitle_emptyContent() {
        val title = MemoUtils.generateTitle("")
        assertEquals("", title)
    }

    @Test
    fun generateTitle_whitespaceOnly() {
        val title = MemoUtils.generateTitle("   ")
        assertEquals("", title)
    }

    @Test
    fun generateTitle_leadingTrailingWhitespace_trimmed() {
        val title = MemoUtils.generateTitle("  Hello  ")
        assertEquals("Hello", title)
    }

    @Test
    fun generateTitle_contentWithNewlines_trimmed() {
        val title = MemoUtils.generateTitle("\nHello\n")
        assertEquals("Hello", title)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
