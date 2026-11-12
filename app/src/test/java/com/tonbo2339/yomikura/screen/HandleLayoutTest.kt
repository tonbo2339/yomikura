package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HandleLayoutTest {
    // 縦の位置は、箱の高さのうち、目印が動ける範囲 (箱の高さ − 目印の長さ) での割合
    @Test
    fun topIsAFractionOfTheRoom() {
        assertEquals(0, HandleLayout.top(0.0, 1000, 100))
        assertEquals(450, HandleLayout.top(0.5, 1000, 100))
        assertEquals(900, HandleLayout.top(1.0, 1000, 100))
    }

    // 範囲を超えた値・箱が目印より小さいときでも、はみ出さない
    @Test
    fun topStaysInside() {
        assertEquals(0, HandleLayout.top(-1.0, 1000, 100))
        assertEquals(900, HandleLayout.top(2.0, 1000, 100))
        assertEquals(0, HandleLayout.top(0.5, 50, 100))
    }

    // 棒は 64dp、小さなボタンは 44dp
    @Test
    fun lengths() {
        assertEquals(64, HandleLayout.lengthDp(false))
        assertEquals(44, HandleLayout.lengthDp(true))
    }

    // 外す範囲は、目印の中心を中心にして、左の端は左・右の端は右に、高さ 200dp
    @Test
    fun exclusionFollowsTheHandle() {
        val z = HandleLayout.exclusion("left", 1000, 2000, 1000, 1f)!!
        assertEquals(0, z.left); assertEquals(32, z.right)
        assertEquals(900, z.top); assertEquals(1100, z.bottom)
        val r = HandleLayout.exclusion("right", 1000, 2000, 1000, 1f)!!
        assertEquals(968, r.left); assertEquals(1000, r.right)
    }

    // 目印が上や下の端に近いときは、画面の中に収める (高さは 200dp のまま)
    @Test
    fun exclusionIsClampedToTheScreen() {
        val top = HandleLayout.exclusion("left", 1000, 2000, 10, 1f)!!
        assertEquals(0, top.top); assertEquals(200, top.bottom)
        val bottom = HandleLayout.exclusion("left", 1000, 2000, 1990, 1f)!!
        assertEquals(1800, bottom.top); assertEquals(2000, bottom.bottom)
    }

    // 上のバーには、外す範囲は無い
    @Test
    fun noExclusionOnTop() {
        assertNull(HandleLayout.exclusion("top", 1000, 2000, 1000, 1f))
        assertNotNull(HandleLayout.exclusion("left", 1000, 2000, 1000, 1f))
    }
}
