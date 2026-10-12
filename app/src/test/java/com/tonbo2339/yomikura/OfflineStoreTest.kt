package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class OfflineStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = OfflineStore(File(tmp.root, "offline"))

    // 先頭だけ本物の JPEG の並びにした小さな中身
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    // 本文は gzip で保存し、読むと元の文字列に戻る (日本語の長い本文は小さくなる)
    @Test
    fun episodesAreCompressedAndReadBack() {
        val s = store()
        val html = "<p>" + "吾輩は猫である。名前はまだ無い。".repeat(800) + "</p>"
        val json = JSONObject().put("ep", "3").put("title", "第三話").put("url", "https://example.com/n1/3/").put("html", html).put("at", 1L).toString()
        assertTrue(s.putEpisode("narou", "n1", "3", json))
        val bytes = File(File(tmp.root, "offline"), "narou/n1/ep_3.json").readBytes()
        assertEquals(0x1f.toByte(), bytes[0]) // gzip の印
        assertEquals(0x8b.toByte(), bytes[1])
        assertTrue("圧縮して小さくなる: ${bytes.size} vs ${json.toByteArray().size}", bytes.size * 2 < json.toByteArray().size)
        assertEquals(json, s.episode("narou", "n1", "3"))
    }

    // 圧縮していない (以前の) ファイルも、そのまま読める
    @Test
    fun readsUncompressedLegacyEpisode() {
        val s = store()
        val json = """{"ep":"1","title":"一話","html":"<p>本文</p>"}"""
        val dir = File(File(tmp.root, "offline"), "narou/n1").apply { mkdirs() }
        File(dir, "ep_1.json").writeText(json)
        assertEquals(json, s.episode("narou", "n1", "1"))
    }

    // 作品の情報 (meta) は小さいので圧縮せず、そのまま読める。壊れた圧縮ファイルは空を返す (落ちない)
    @Test
    fun metaStaysPlainAndBrokenGzipIsEmpty() {
        val s = store()
        assertTrue(s.putMeta("narou", "n1", """{"title":"作品"}"""))
        assertEquals("{", File(File(tmp.root, "offline"), "narou/n1/meta.json").readText().take(1))
        assertEquals("""{"title":"作品"}""", s.meta("narou", "n1"))
        val dir = File(File(tmp.root, "offline"), "narou/n1")
        File(dir, "ep_9.json").writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 0, 1, 2, 3)) // 印だけで中身が壊れている
        assertEquals("", s.episode("narou", "n1", "9"))
    }

    // 圧縮する前の本文が上限を超えるときは保存しない (圧縮後の大きさではなく文字数で見る)
    @Test
    fun rejectsTooLongText() {
        assertFalse(store().putEpisode("narou", "n1", "1", "あ".repeat(OfflineStore.MAX_CHARS + 1)))
    }

    @Test
    // 挿絵は、名前と中身の種類が正しいものだけ保存して、読み出せる
    fun savesAndReadsValidImage() {
        val s = store()
        assertTrue(s.putImage("narou", "n1", "i0a1b2c3d.jpg", b64(jpeg)))
        val f = s.imageFile("narou", "n1", "i0a1b2c3d.jpg")
        assertNotNull(f)
        assertEquals(jpeg.toList(), f!!.readBytes().toList())
    }

    @Test
    // 拡張子と中身が違う物は保存しない (拡張子だけを信じない)
    fun rejectsMismatchedContent() {
        val s = store()
        assertFalse(s.putImage("narou", "n1", "i0a1b2c3d.png", b64(jpeg)))
        assertFalse(s.putImage("narou", "n1", "i0a1b2c3d.jpg", b64("<svg onload=alert(1)>".toByteArray())))
        assertNull(s.imageFile("narou", "n1", "i0a1b2c3d.png"))
    }

    @Test
    // フォルダの外へ出る名前、形の違う名前は受け付けない
    fun rejectsUnsafeNames() {
        val s = store()
        for (name in listOf("../i0a1b2c3d.jpg", "i0a1b2c3d.jpg/../../x", "a.jpg", "i0a1b2c3dz.jpg", "i0a1b2c3d.svg", "i0a1b2c3d.jpg\u0000")) {
            assertFalse(name, s.putImage("narou", "n1", name, b64(jpeg)))
            assertNull(name, s.imageFile("narou", "n1", name))
        }
        assertFalse(s.putImage("../x", "n1", "i0a1b2c3d.jpg", b64(jpeg)))
    }

    @Test
    // 壊れた base64 と、大きすぎる物は保存しない
    fun rejectsBrokenAndOversized() {
        val s = store()
        assertFalse(s.putImage("narou", "n1", "i0a1b2c3d.jpg", "!!not base64!!"))
        val big = jpeg + ByteArray(OfflineStore.MAX_IMAGE_BYTES)
        assertFalse(s.putImage("narou", "n1", "i0a1b2c3d.jpg", b64(big)))
        assertTrue(OfflineStore.MAX_IMAGE_BASE64 >= OfflineStore.MAX_IMAGE_BYTES * 4 / 3)
    }

    @Test
    // 配信するときの種類は、拡張子から。挿絵以外は null
    fun mimeOnlyForImages() {
        assertEquals("image/jpeg", OfflineStore.mimeOf("i0a1b2c3d.jpg"))
        assertEquals("image/webp", OfflineStore.mimeOf("i0a1b2c3d.webp"))
        assertNull(OfflineStore.mimeOf("i0a1b2c3d.svg"))
        assertNull(OfflineStore.mimeOf("meta.json"))
        assertTrue(OfflineStore.looksLike(png, "i0a1b2c3d.png"))
    }

    @Test
    // 同じ話を上書きすると、新しい中身で置き換わる
    fun overwriteAndRead() {
        val s = store()
        assertTrue(s.putEpisode("narou", "n1234ab", "1", "{\"a\":1}"))
        assertTrue(s.putEpisode("narou", "n1234ab", "1", "{\"a\":2}"))
        assertEquals("{\"a\":2}", s.episode("narou", "n1234ab", "1"))
    }

    @Test
    // 作品・話の名前でフォルダの外へ出られない
    fun rejectsPathTricksInWorkAndEpisode() {
        val s = store()
        assertFalse(s.putMeta("..", "x", "{}"))
        assertFalse(s.putEpisode("narou", "n1234ab", "../x", "{}"))
    }
}
