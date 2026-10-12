package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class DiagTest {
    @Test
    fun sanitizeKeepsOnlyTheHostOfUrls() {
        assertEquals("stalled at https://ncode.syosetu.com/…", DiagBuffer.sanitize("stalled at https://ncode.syosetu.com/n1234ab/5/?x=1#top"))
        assertEquals("a https://kakuyomu.jp/… b https://syosetu.org/…", DiagBuffer.sanitize("a https://kakuyomu.jp/works/9/episodes/1 b http://syosetu.org/novel/2/"))
        assertEquals("1 2 3", DiagBuffer.sanitize("1\n2\t3"))
        assertEquals(10, DiagBuffer.sanitize("x".repeat(50), 10).length) // 長ければ切る (… を含めて 10 文字)
    }

    @Test
    fun bufferKeepsTheNewestAndRoundTrips() {
        val b = DiagBuffer(max = 3)
        for (i in 1..5) b.add(i * 1000L, if (i % 2 == 0) 'E' else 'W', "Tag", "message $i")
        assertEquals(listOf("message 3", "message 4", "message 5"), b.entries().map { it.message })
        val c = DiagBuffer(max = 3).also { it.load(b.toText()) }
        assertEquals(b.entries(), c.entries())
        c.load("broken\n\n1000\tE\tT\tok\nabc\tW\tT\tbad time\n2000\tW\tT") // 壊れた行は飛ばす
        assertEquals(listOf("ok"), c.entries().map { it.message })
    }

    @Test
    fun lineShowsTimeLevelTagAndMessage() {
        val e = DiagBuffer.Entry(0, 'W', "Collector", "stalled")
        assertEquals("1/1 09:00 W Collector: stalled", DiagBuffer.line(e, TimeZone.getTimeZone("Asia/Tokyo")))
    }

    @Test
    fun dataSummaryHasCountsAndSafeSettingsButNoTitlesOrUrls() {
        val d = JSONObject(
            """{"readLater":[{"key":"narou:n1","title":"秘密の作品名","url":"https://ncode.syosetu.com/n1/","memo":"私のメモ"}],
                "history":[{"title":"履歴の題名"},{"title":"b"}],
                "progress":{"narou:n1":{"url":"https://ncode.syosetu.com/n1/3/"}},
                "mute":{"authors":["作者名"],"keywords":["語"]},
                "settings":{"theme":"dark","size":110,"nsfw":false,"tabNames":{"narou":"私のタブ"},"autoCheck":true}}"""
        )
        val text = DiagReport.dataSummary(d).joinToString("\n")
        assertTrue(text.contains("リスト 1 作品"))
        assertTrue(text.contains("履歴 2 作品"))
        assertTrue(text.contains("読書位置 1 作品"))
        assertTrue(text.contains("theme=dark") && text.contains("size=110") && text.contains("autoCheck=true"))
        for (secret in listOf("秘密の作品名", "私のメモ", "履歴の題名", "作者名", "私のタブ", "ncode.syosetu.com", "nsfw")) assertFalse(secret, text.contains(secret))
    }

    @Test
    fun emptyDataDoesNotCrash() {
        assertEquals(listOf("リスト 0 作品 / 履歴 0 作品 / 読書位置 0 作品", "設定 0 項目"), DiagReport.dataSummary(JSONObject()))
    }

    @Test
    fun reportHasSectionsAndTheLatestErrorsFirst() {
        val r = DiagReport.format("ヨミクラ", listOf("アプリ" to listOf("0.1 (1)")), listOf("新しい", "古い"))
        assertTrue(r.startsWith("# ヨミクラ 診断情報\n"))
        assertTrue(r.contains("## アプリ\n- 0.1 (1)\n"))
        assertTrue(r.indexOf("- 新しい") < r.indexOf("- 古い"))
        assertTrue(DiagReport.format("x", emptyList(), emptyList()).contains("- なし"))
        assertEquals(DiagReport.LOG_MAX, DiagReport.format("x", emptyList(), List(100) { "e$it" }).lines().count { it.startsWith("- e") })
    }
}
