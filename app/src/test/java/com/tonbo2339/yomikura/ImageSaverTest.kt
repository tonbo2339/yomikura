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

    // 種類は中身で決まる (配信元の Content-Type が間違っていても取れる)。SVG や HTML は対象外
    @Test
    fun kindComesFromContentNotLabel() {
        assertEquals("jpg", ImageSaver.kindOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("png", ImageSaver.kindOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals(null, ImageSaver.kindOf("<svg onload=alert(1)>".toByteArray()))
        assertEquals(null, ImageSaver.kindOf("<html>".toByteArray()))
    }
}
