package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabLayoutTest {
    // 1 文字 = sp と同じ幅
    private val measure = { s: String, sp: Float -> s.length * sp }

    @Test
    fun splitAvoidsSmallCharAtLineHead() {
        assertEquals(listOf("ムーン", "ライト"), TabLayout.split("ムーンライト"))
        assertEquals(listOf("ノク", "ターン"), TabLayout.split("ノクターン")) // 2 行目が「ー」で始まらないよう前へずらす
        assertEquals(listOf("あ", ""), TabLayout.split("あ"))
    }

    @Test
    fun shortLabelsStayOnOneLine() {
        val (sp, texts) = TabLayout.oneRowText(listOf("なろう", "カクヨム"), 100f, measure)
        assertEquals(13f, sp, 0.001f)
        assertEquals(listOf("なろう", "カクヨム"), texts)
    }

    @Test
    fun longLabelsWrapToTwoLines() {
        // 8 文字が 9sp でも入らない幅 (room 50) → 2 行
        val (sp, texts) = TabLayout.oneRowText(listOf("なろう", "ミッドナイトです"), 50f, measure)
        assertTrue(sp in 8f..12f)
        assertTrue(texts[1].contains("\n"))
    }
}
