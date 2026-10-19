package com.tonbo2339.yomikura

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReleaseNotesTest {
    private val sample = """
        <!-- 説明 (出さない) -->

        1.0.0 からの
        主な変更です。

        ## 新しくできること

        - **履歴**: 話数を出します
        - [README](https://example.test/) を見てください

        <!-- アプリに出すのはここまで -->

        Android 8.0 以上。
    """.trimIndent()

    @Test
    fun parsesHeadingsBulletsParagraphsAndInlineMarks() {
        val b = ReleaseNotes.parse(sample)
        assertEquals(listOf(ReleaseNotes.Kind.PARAGRAPH, ReleaseNotes.Kind.HEADING, ReleaseNotes.Kind.BULLET, ReleaseNotes.Kind.BULLET), b.map { it.kind })
        assertEquals("1.0.0 からの主な変更です。", b[0].text) // 続く行は 1 つの段落
        assertEquals("新しくできること", b[1].text)
        assertEquals(listOf("履歴" to true, ": 話数を出します" to false), b[2].spans) // **太字**
        assertEquals("README を見てください", b[3].text) // リンクは文字だけ
        assertFalse(b.any { "Android" in it.text }) // 目印より下は出さない
    }

    @Test
    fun jsonForTheSettingsPage() {
        val a = JSONArray(ReleaseNotes.toJson(ReleaseNotes.parse("## 見出し\n- **太字**と文字")))
        assertEquals("h", a.getJSONObject(0).getString("k"))
        assertEquals("li", a.getJSONObject(1).getString("k"))
        val s = a.getJSONObject(1).getJSONArray("s")
        assertEquals("太字", s.getJSONArray(0).getString(0))
        assertTrue(s.getJSONArray(0).getBoolean(1))
        assertEquals("と文字", s.getJSONArray(1).getString(0))
    }

    // 更新したあとの最初の起動で 1 回だけ。新しく入れたときは出さない。この仕組みより前の版からの更新では出す
    @Test
    fun showOnlyOnceAfterAnUpdate() {
        assertTrue(ReleaseNotes.shouldShow("1.0.11", "1.1.0", 100, 200))
        assertFalse(ReleaseNotes.shouldShow("1.1.0", "1.1.0", 100, 200)) // もう出した
        assertFalse(ReleaseNotes.shouldShow("", "1.1.0", 100, 100)) // 新しく入れた
        assertTrue(ReleaseNotes.shouldShow("", "1.1.0", 100, 200)) // 記録の無い古い版から更新した
    }

    // 版ごとの変更点のファイル (GitHub の Release の本文にも使う) が読めて、アプリに出す文があること
    @Test
    fun eachEditionHasReadableNotes() {
        // 版のフォルダ (src/<版>/assets) を順に見る (版の名前はここに書かない)
        val files = File("src").listFiles().orEmpty().map { File(it, "assets/" + ReleaseNotes.FILE) }.filter { it.isFile }
        assertTrue("変更点のファイルが見つかりません", files.isNotEmpty())
        files.forEach { f ->
            val b = ReleaseNotes.parse(f.readText())
            assertTrue("${f.path} にアプリに出す文がありません", b.isNotEmpty())
            assertFalse("${f.path} の <!-- --> が出てしまいます", b.any { "<!--" in it.text })
        }
    }
}
