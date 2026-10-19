package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSaverTest {
    // JS の imgName (offline.js) と同じ結果になること。値は同じ URL を JS で計算したもの
    @Test
    fun nameMatchesJavascriptNaming() {
        assertEquals("i99559634.jpg", ImageSaver.nameFor("https://img.example.com/image/viewimagebig/icode/i0000001/", "jpg"))
    }

    @Test
    fun nameIsEightHexDigitsWithExtension() {
        val n = ImageSaver.nameFor("https://example.test/日本語/画像.png", "png")
        assertEquals(true, Regex("^i[0-9a-f]{8}[.]png$").matches(n))
    }

    // 話ごと止めて (サイトを休ませて) よいのは、本文のサイト自身の 403 / 429 / 503 だけ。別の置き場所の 403 は、その挿絵だけ飛ばす
    @Test
    fun onlyTheNovelSiteItselfStopsTheEpisode() {
        assertEquals(true, ImageSaver.stopsSite(403, "syosetu.org"))
        assertEquals(true, ImageSaver.stopsSite(429, "img.syosetu.com"))
        assertEquals(true, ImageSaver.stopsSite(503, "kakuyomu.jp"))
        assertEquals(false, ImageSaver.stopsSite(403, "i.imgur.com"))
        assertEquals(false, ImageSaver.stopsSite(429, "example.s3.amazonaws.com"))
        assertEquals(false, ImageSaver.stopsSite(404, "syosetu.org"))
    }

    // 種類は中身で決まる (配信元の Content-Type が間違っていても取れる)。SVG や HTML は対象外
    @Test
    fun kindComesFromContentNotLabel() {
        assertEquals("jpg", ImageSaver.kindOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("png", ImageSaver.kindOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals(null, ImageSaver.kindOf("<svg onload=alert(1)>".toByteArray()))
        assertEquals(null, ImageSaver.kindOf("<html>".toByteArray()))
    }
}
