package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderStatsTest {
    private fun solid(argb: Int, n: Int = 100) = IntArray(n) { argb }

    @Test
    fun pureBlackIsFlatAndBlack() {
        val s = RenderStats.analyze(solid(0xFF000000.toInt()))
        assertEquals(0, s.avgLuma)
        assertEquals(100, s.blackPct)
        assertEquals(1, s.colors)
        assertTrue(s.flat)
    }

    @Test
    fun darkThemeBackgroundWithoutTextIsFlatButNotBlack() {
        val s = RenderStats.analyze(solid(0xFF121212.toInt()))
        assertTrue(s.flat)
        assertEquals(0, s.blackPct) // 暗い灰色 (ほぼ黒ではない)
    }

    @Test
    fun aPageWithTextIsNotFlat() {
        val px = IntArray(100) { if (it % 5 == 0) 0xFFE0E0E0.toInt() else 0xFF121212.toInt() } // 暗い背景に明るい文字
        val s = RenderStats.analyze(px)
        assertFalse(s.flat)
        assertTrue(s.stddev > 20)
        assertEquals(2, s.colors)
    }

    @Test
    fun emptyInputIsTreatedAsBlack() {
        val s = RenderStats.analyze(IntArray(0))
        assertEquals(100, s.blackPct)
        assertTrue(s.flat)
    }

    @Test
    fun fieldsListTheNumbers() {
        val f = RenderStats.analyze(solid(0xFFFFFFFF.toInt())).fields().toMap()
        assertEquals(255, f["avgLuma"])
        assertEquals(true, f["flat"])
    }
}
