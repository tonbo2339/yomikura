package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChunkerTest {
    @Test
    fun keepsShortParagraphsWhole() {
        assertEquals(listOf(TtsChunker.Chunk(0, "一つ目"), TtsChunker.Chunk(1, "二つ目。")), TtsChunker.chunk(listOf("一つ目", "二つ目。")))
    }

    @Test
    fun skipsEmptyParagraphsButKeepsTheNumbering() {
        val c = TtsChunker.chunk(listOf("あ", "  ", "", "い"))
        assertEquals(listOf(0, 3), c.map { it.para }) // 段落の番号は元の順のまま (色をつける段落とずれない)
    }

    @Test
    fun splitsLongParagraphsAtSentenceEndsWithoutLosingText() {
        val p = "あいう。".repeat(10) + "えお！" + "かき？".repeat(5)
        val c = TtsChunker.chunk(listOf(p), max = 20)
        assertTrue(c.all { it.para == 0 && it.text.length <= 20 })
        assertEquals(p, c.joinToString("") { it.text }) // 欠けない・重ならない
        assertTrue(c.dropLast(1).all { it.text.last() in "。！？" }) // 切れ目は、文の終わり
    }

    @Test
    fun cutsASentenceWithoutBreaksAtTheLimit() {
        val p = "あ".repeat(95)
        val c = TtsChunker.chunk(listOf(p), max = 40)
        assertEquals(listOf(40, 40, 15), c.map { it.text.length })
        assertEquals(p, c.joinToString("") { it.text })
    }

    @Test
    fun handlesNothing() {
        assertTrue(TtsChunker.chunk(emptyList()).isEmpty())
        assertTrue(TtsChunker.chunk(listOf("", " ")).isEmpty())
    }
}
