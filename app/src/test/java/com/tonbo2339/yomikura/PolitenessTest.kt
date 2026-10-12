package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolitenessTest {
    @Test
    fun pauseIsBetweenOneAndAHalfAndThreeSeconds() {
        assertEquals(1500L, Politeness.pauseMs(0.0))
        assertTrue(Politeness.pauseMs(0.5) in 2200L..2300L)
        assertTrue(Politeness.pauseMs(0.999999) < 3000L)
        for (r in listOf(-5.0, 7.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) assertTrue(Politeness.pauseMs(r) in 1500L..2999L) // 範囲外の乱数でも、短くならない
    }

    @Test
    fun restLastsAnHourForAutoAndTenMinutesForManual() {
        val rest = JSONObject("""{"narou": 1000000}""")
        val min = 60_000L
        assertTrue(SiteRest.isResting(rest, "narou", auto = true, now = 1000000 + 59 * min))
        assertFalse(SiteRest.isResting(rest, "narou", auto = true, now = 1000000 + 60 * min))
        assertTrue(SiteRest.isResting(rest, "narou", auto = false, now = 1000000 + 9 * min))
        assertFalse(SiteRest.isResting(rest, "narou", auto = false, now = 1000000 + 10 * min))
    }

    @Test
    fun otherSitesAndMissingRecordsAreNotResting() {
        val rest = JSONObject("""{"narou": 1000000}""")
        assertFalse(SiteRest.isResting(rest, "hameln", auto = true, now = 1000001))
        assertFalse(SiteRest.isResting(null, "narou", auto = true, now = 1000001))
        assertFalse(SiteRest.isResting(JSONObject("""{"narou": 0}"""), "narou", auto = true, now = 1))
    }
}
