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

    @Test
    fun judgeStopsOnlyOnBlockingCodes() {
        val html = """<a href="https://noc.syosetu.com/?ref=ncodeheader">n</a>"""
        assertEquals(null to true, R18Subs.judge(403, ""))
        assertEquals(null to true, R18Subs.judge(429, ""))
        assertEquals(null to true, R18Subs.judge(503, ""))
        assertEquals("noc" to false, R18Subs.judge(200, html))
        assertEquals(null to false, R18Subs.judge(0, html)) // 開けなかった: 止めない
        assertEquals(null to false, R18Subs.judge(404, html))
    }
}
