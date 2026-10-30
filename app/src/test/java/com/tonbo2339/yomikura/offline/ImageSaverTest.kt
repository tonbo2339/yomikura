package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class ImageSaverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = OfflineStore(File(tmp.root, "offline"))
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private val url = "https://syosetu.org/img/user/v2/6227/501/264262.jpg"

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

    // ページ (WebView) で取った挿絵の保存: 種類は中身で決め、名前は元の URL で決める (save と同じ)
    @Test
    fun saveDataKeepsImageUnderNameFromUrl() {
        val s = store()
        val r = ImageSaver.saveData(s, "hameln", "431079", url, b64(jpeg))
        assertEquals("""{"name":"${ImageSaver.nameFor(url, "jpg")}","bytes":7}""", r)
        assertNotNull(s.imageFile("hameln", "431079", ImageSaver.nameFor(url, "jpg")))
    }

    @Test
    fun saveDataRefusesWhatIsNotAllowed() {
        val s = store()
        assertEquals("{}", ImageSaver.saveData(s, "hameln", "1", "http://syosetu.org/a.jpg", b64(jpeg))) // 平文の URL
        assertEquals("{}", ImageSaver.saveData(s, "hameln", "1", url, b64("<svg onload=alert(1)>".toByteArray()))) // 画像でない
        assertEquals("{}", ImageSaver.saveData(s, "hameln", "1", url, "これは base64 ではない")) // 壊れた base64
        assertEquals("{}", ImageSaver.saveData(s, "hameln", "1", url, b64(jpeg)) { it == "syosetu.org" }) // 止める配信元
        assertEquals("{}", ImageSaver.saveData(s, "hameln", "1", url, "A".repeat(OfflineStore.MAX_IMAGE_BASE64 + 1))) // 大きすぎる
        assertEquals("{}", ImageSaver.saveData(s, "hameln", "1", "not a url", b64(jpeg)))
    }
}
