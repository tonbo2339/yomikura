package com.tonbo2339.yomikura

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** 裏での確認の時刻の計算 (BgCheck.slots / nextAt)。 */
class BgCheckTest {
    @Before
    fun tz() = TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))

    private fun hm(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun intervalFromMidnight() {
        val c = JSONObject().put("mode", "interval").put("hours", 3)
        assertEquals((0 until 8).map { hm(it * 3) }, BgCheck.slots(c))
    }

    @Test
    fun intervalSkipsQuietHours() {
        // 静かな時間 23:00〜7:00: 7:00 から 6 時間ごと (7, 13, 19)。1:00 は静かな時間なので飛ばす
        val c = JSONObject().put("mode", "interval").put("hours", 6)
            .put("quietOn", true).put("quietFrom", "23:00").put("quietTo", "7:00")
        assertEquals(listOf(hm(7), hm(13), hm(19)), BgCheck.slots(c))
    }

    @Test
    fun timesCloserThanMinGapAreDropped() {
        // 30 分未満しかあいていない時刻は、あとのものを除く (サイトへのアクセスを控えるため)
        val c = JSONObject().put("mode", "times").put("times", JSONArray(listOf("07:00", "07:29", "07:30", "21:00")))
        assertEquals(listOf(hm(7), hm(7, 30), hm(21)), BgCheck.slots(c))
    }

    @Test
    fun timesAcrossMidnightAreSpaced() {
        // 0:00 をまたぐ最後と最初が 30 分未満なら、最後を除く
        val c = JSONObject().put("mode", "times").put("times", JSONArray(listOf("00:10", "12:00", "23:50")))
        assertEquals(listOf(hm(0, 10), hm(12)), BgCheck.slots(c))
        assertEquals(listOf(hm(5)), BgCheck.spaced(listOf(hm(5))))
        assertEquals(listOf(hm(0), hm(0, 30), hm(23, 30)), BgCheck.spaced(listOf(hm(0), hm(0, 30), hm(23, 30)))) // 0:00 をまたいでも 30 分あいていれば残る
    }

    @Test
    fun timesAreSortedAndCleaned() {
        val c = JSONObject().put("mode", "times").put("times", JSONArray(listOf("21:30", "7:05", "bad", "7:05", "24:00")))
        assertEquals(listOf(hm(7, 5), hm(21, 30)), BgCheck.slots(c))
    }

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long = Calendar.getInstance().apply {
        clear(); set(y, mo - 1, d, h, mi)
    }.timeInMillis

    @Test
    fun nextOnChosenDay() {
        // 2026-10-07 (水) 12:00 から、日曜 (0) の 8:00 だけ → 10-11 (日) 8:00
        val c = JSONObject().put("mode", "times").put("times", JSONArray(listOf("8:00"))).put("days", JSONArray(listOf(0)))
        assertEquals(at(2026, 10, 11, 8), BgCheck.nextAt(c, at(2026, 10, 7, 12)))
    }

    @Test
    fun nextSkipsSlotTooClose() {
        // 今から 30 秒以内の時刻は飛ばして次へ
        val c = JSONObject().put("mode", "times").put("times", JSONArray(listOf("12:00", "18:00")))
        assertEquals(at(2026, 10, 7, 18), BgCheck.nextAt(c, at(2026, 10, 7, 12) - 10_000))
    }

    @Test
    fun noSlots() {
        assertNull(BgCheck.nextAt(JSONObject().put("mode", "times").put("times", JSONArray())))
    }
}
