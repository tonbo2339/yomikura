package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class R18SubsTest {
    @Test
    fun headerLinkWins() {
        val html = """<a href="https://mnlt.syosetu.com/top/top/">x</a><a href="https://noc.syosetu.com/?ref=ncodeheader">n</a>"""
        assertEquals("noc", R18Subs.detectSub(html))
    }

    @Test
    fun fallsBackToAnyLink() {
        assertEquals("mid", R18Subs.detectSub("""<a href="https://mid.syosetu.com/">m</a>"""))
        assertNull(R18Subs.detectSub("<html>年齢確認</html>"))
    }
}
