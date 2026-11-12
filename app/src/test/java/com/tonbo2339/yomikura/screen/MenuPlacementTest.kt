package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuPlacementTest {
    // 画面 1000×2000、バーは左端 (タブは x=10〜210)、メニューは 600×500
    private fun left(top: Int) = MenuPlacement.beside(10, top, 200, 100, 600, 500, 1000, 2000, 8)

    // 左のバーのタブ: メニューはタブの右隣。下に収まるなら、タブの上端にそろえる
    @Test
    fun leftRailOpensToTheRightAlignedToTheTop() {
        assertEquals(210 to 300, left(300))
    }

    // 下に収まらないとき (バーの下の方のタブ): タブの下端にそろえて、上へ伸ばす。バーの上には重ならない (x はタブの右)
    @Test
    fun nearTheBottomItGrowsUpwards() {
        val (x, y) = left(1800)
        assertEquals(210, x)
        assertEquals(1800 + 100 - 500, y)
        assertTrue(y + 500 <= 2000)
    }

    // 上端に近すぎても、画面の中に収める
    @Test
    fun clampedToTheScreen() {
        val (_, y) = MenuPlacement.beside(10, 0, 200, 100, 600, 500, 1000, 2000, 8)
        assertEquals(8, y)
    }

    // 右のバーのタブ: メニューはタブの左隣
    @Test
    fun rightRailOpensToTheLeft() {
        val (x, y) = MenuPlacement.beside(790, 300, 200, 100, 600, 500, 1000, 2000, 8)
        assertEquals(190, x)
        assertEquals(300, y)
    }

    // 幅が足りない窓でも、はみ出さない
    @Test
    fun narrowWindow() {
        val (x, _) = MenuPlacement.beside(10, 300, 200, 100, 900, 500, 1000, 2000, 8)
        assertEquals(92, x) // 1000 − 900 − 8
    }
}
