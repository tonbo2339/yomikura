package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class CustomFontsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fonts() = CustomFonts(File(tmp.root, "fonts"))
    private val ttf = byteArrayOf(0, 1, 0, 0, 9, 9, 9, 9)
    private val otf = "OTTO....".toByteArray()

    @Test
    fun addsFontAndServesItByIdWithMime() {
        val f = fonts()
        val r = f.add("My Font.ttf", ByteArrayInputStream(ttf))
        assertTrue(r.message, r.ok)
        val id = r.font!!.id
        assertTrue(CustomFonts.ID.matches(id))
        assertEquals("My Font", r.font!!.name)
        assertEquals(ttf.toList(), f.file(id)!!.readBytes().toList())
        assertEquals("font/ttf", f.mimeOf(id))
        assertEquals("font/otf", f.add("a.otf", ByteArrayInputStream(otf)).font!!.let { f.mimeOf(it.id) })
    }

    @Test
    fun listSurvivesANewInstanceAndRemoveDeletesTheFile() {
        val id = fonts().add("a.ttf", ByteArrayInputStream(ttf)).font!!.id
        val again = fonts()
        assertEquals(listOf(id), again.list().map { it.id })
        val file = again.file(id)!!
        assertTrue(again.remove(id))
        assertFalse(file.exists())
        assertTrue(again.list().isEmpty())
        assertFalse(again.remove(id))
    }

    @Test
    fun rejectsNonFontFilesWhatEverTheName() {
        val f = fonts()
        val r = f.add("evil.ttf", ByteArrayInputStream("<html>hello</html>".toByteArray()))
        assertFalse(r.ok)
        assertTrue(f.list().isEmpty())
        // 書きかけのファイルを残さない
        assertEquals(emptyList<String>(), File(tmp.root, "fonts").list()!!.filter { it.startsWith("tmp-") })
    }

    @Test
    fun rejectsTooLargeFile() {
        val f = fonts()
        val big = object : InputStream() {
            var left = CustomFonts.MAX_BYTES + 1
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        assertFalse(f.add("big.ttf", big).ok)
        assertTrue(f.list().isEmpty())
        assertEquals(emptyList<String>(), File(tmp.root, "fonts").list()!!.filter { it.startsWith("tmp-") })
    }

    @Test
    fun limitsTheNumberOfFonts() {
        val f = fonts()
        repeat(CustomFonts.MAX_FONTS) { assertTrue(f.add("f$it.ttf", ByteArrayInputStream(ttf)).ok) }
        assertFalse(f.add("one-more.ttf", ByteArrayInputStream(ttf)).ok)
        assertEquals(CustomFonts.MAX_FONTS, f.list().size)
    }

    // id は URL から来る。形の違うもの (../ など) は、ファイルを探さずに断る
    @Test
    fun fileRejectsMalformedIds() {
        val f = fonts()
        f.add("a.ttf", ByteArrayInputStream(ttf))
        for (bad in listOf("../index.json", "..", "", "ABCDEF123456", "0123456789a", "0123456789abc", "index.json")) assertNull(bad, f.file(bad))
        assertNull(f.file("0123456789ab")) // 形は正しいが、入っていない
    }

    @Test
    fun clearRemovesEverything() {
        val f = fonts()
        f.add("a.ttf", ByteArrayInputStream(ttf))
        f.clear()
        assertTrue(f.list().isEmpty())
        assertNotNull(f.add("b.ttf", ByteArrayInputStream(ttf)).font) // 消したあとも使える
    }

    private fun dir() = File(tmp.root, "fonts")

    // 一覧に無いフォントのファイル (一覧に書けなかった・消し損ねた物) は、次の追加のときの掃除で消える。一覧にある物・ほかの種類のファイルは消さない
    @Test
    fun sweepRemovesOrphanFontFilesButKeepsListedOnes() {
        val f = fonts()
        val keep = f.add("keep.ttf", ByteArrayInputStream(ttf)).font!!
        val orphan = File(dir(), "0123456789ab.ttf").also { it.writeBytes(ttf) }
        val other = File(dir(), "notes.txt").also { it.writeText("x") }
        assertTrue(f.add("second.ttf", ByteArrayInputStream(ttf)).ok)
        assertFalse(orphan.exists())
        assertTrue(other.exists())
        assertNotNull(f.file(keep.id))
        assertEquals(2, f.list().size)
    }

    // 別の追加が写している最中の書きかけ (新しい) は消さない。古い書きかけ (1 時間より前) は消す
    @Test
    fun sweepKeepsFreshTmpFilesAndRemovesStaleOnes() {
        val f = fonts()
        f.add("a.ttf", ByteArrayInputStream(ttf))
        val fresh = File(dir(), "tmp-fresh").also { it.writeBytes(ttf) }
        val stale = File(dir(), "tmp-stale").also { it.writeBytes(ttf); it.setLastModified(System.currentTimeMillis() - CustomFonts.STALE_MS - 60_000) }
        assertTrue(f.add("b.ttf", ByteArrayInputStream(ttf)).ok)
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
    }

    // 一覧が壊れているとき: フォントのファイルは消さない (一覧だけ作り直す)。一覧を置き換えるときの一時ファイルは残さない
    @Test
    fun corruptIndexIsToleratedAndFontFilesAreKept() {
        val f = fonts()
        val old = f.add("old.ttf", ByteArrayInputStream(ttf)).font!!
        File(dir(), CustomFonts.INDEX).writeText("{not json")
        assertTrue(f.list().isEmpty()) // 読めない一覧は空として扱う
        assertTrue(f.add("new.ttf", ByteArrayInputStream(ttf)).ok)
        assertTrue(File(dir(), "${old.id}.ttf").exists())
        assertEquals(1, f.list().size)
        assertEquals(emptyList<String>(), dir().list()!!.filter { it.endsWith(".new") })
    }

    // 一覧の ext はファイルの名前に使うので、決まった物以外 (../ など) は読み込まない
    @Test
    fun indexEntriesWithOddExtensionOrIdAreIgnored() {
        val f = fonts()
        f.add("a.ttf", ByteArrayInputStream(ttf))
        val good = f.list().first()
        File(dir(), CustomFonts.INDEX).writeText(
            "[{\"id\":\"${good.id}\",\"name\":\"a\",\"ext\":\"ttf\"},{\"id\":\"0123456789ab\",\"name\":\"x\",\"ext\":\"../x\"},{\"id\":\"zz\",\"name\":\"y\",\"ext\":\"ttf\"}]"
        )
        assertEquals(listOf(good.id), f.list().map { it.id })
    }

    @Test
    fun detectsFontKindFromTheHead() {
        assertEquals("ttf", CustomFonts.extOf(byteArrayOf(0, 1, 0, 0)))
        assertEquals("ttf", CustomFonts.extOf("true".toByteArray()))
        assertEquals("otf", CustomFonts.extOf("OTTO".toByteArray()))
        assertEquals("ttc", CustomFonts.extOf("ttcf".toByteArray()))
        assertNull(CustomFonts.extOf("wOFF".toByteArray())) // Web フォント (woff) は入れない
        assertNull(CustomFonts.extOf(byteArrayOf(1, 2)))
    }

    @Test
    fun cleanNameDropsExtensionAndControlCharsAndLimitsLength() {
        assertEquals("Noto Serif", CustomFonts.cleanName("Noto Serif.otf"))
        assertEquals("フォント", CustomFonts.cleanName(""))
        assertEquals("フォント", CustomFonts.cleanName(".ttf"))
        assertEquals("ab", CustomFonts.cleanName("a\nb.ttf"))
        assertEquals(40, CustomFonts.cleanName("あ".repeat(100) + ".ttf").length)
    }

    @Test
    fun toJsonHasIdAndNameOnly() {
        val f = fonts()
        f.add("x.ttf", ByteArrayInputStream(ttf))
        val a = org.json.JSONArray(f.toJson())
        assertEquals(1, a.length())
        assertEquals(setOf("id", "name"), a.getJSONObject(0).keys().asSequence().toSet())
    }

    /** nameId 1 の名前を (platform, lang, 文字) で持つ、表が 1 つだけの ttf */
    private fun fontWithNames(vararg rec: Triple<Int, Int, String>): ByteArray {
        val strs = java.io.ByteArrayOutputStream()
        val body = java.io.ByteArrayOutputStream()
        val h = java.io.DataOutputStream(body)
        h.writeShort(0); h.writeShort(rec.size); h.writeShort(6 + rec.size * 12)
        rec.forEach { (pl, lang, text) ->
            val b = text.toByteArray(Charsets.UTF_16BE)
            h.writeShort(pl); h.writeShort(0); h.writeShort(lang); h.writeShort(1); h.writeShort(b.size); h.writeShort(strs.size()); strs.write(b)
        }
        val table = body.toByteArray() + strs.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeInt(0x10000); d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(0)
        d.writeBytes("name"); d.writeInt(0); d.writeInt(28); d.writeInt(table.size)
        d.write(table)
        return out.toByteArray()
    }

    @Test
    fun nameStartsAsFontLocalNameAndFamilyKeepsEnglish() {
        val f = fonts()
        val r = f.add("file.ttf", ByteArrayInputStream(fontWithNames(Triple(3, 0x409, "Yu Mincho"), Triple(3, 0x411, "游明朝"))))
        assertEquals("游明朝", r.font!!.name)
        assertEquals("Yu Mincho", r.font!!.family)
        assertEquals("游明朝", fonts().list().single().name) // 新しく開き直しても残る
        assertEquals("Yu Mincho", fonts().list().single().family)
        // 名前が読めないフォントは、ファイル名から
        assertEquals("plain", f.add("plain.ttf", ByteArrayInputStream(ttf)).font!!.name)
    }

    @Test
    fun renameChangesOnlyTheShownName() {
        val f = fonts()
        val font = f.add("a.ttf", ByteArrayInputStream(fontWithNames(Triple(3, 0x409, "Sample")))).font!!
        assertTrue(f.rename(font.id, "  わたしの書体\n "))
        val now = fonts().list().single()
        assertEquals("わたしの書体", now.name)
        assertEquals("Sample", now.family)
        assertEquals(font.id, now.id)
        f.rename(font.id, "あ".repeat(100))
        assertEquals(40, f.list().single().name.length)
    }

    @Test
    fun renameRefusesEmptyNameAndUnknownId() {
        val f = fonts()
        val font = f.add("a.ttf", ByteArrayInputStream(ttf)).font!!
        assertFalse(f.rename(font.id, "  \n"))
        assertFalse(f.rename("0123456789ab", "x"))
        assertEquals("a", f.list().single().name)
    }

    @Test
    fun tidyCapsByCodePointsAndDropsFormatAndBrokenCharacters() {
        val emoji = "😀" // 1 文字だが UTF-16 では 2 つ
        assertEquals("a".repeat(39) + emoji, CustomFonts.tidy("a".repeat(39) + emoji + "zzz"))
        assertEquals(40, CustomFonts.tidy("あ".repeat(100)).length)
        assertEquals("ab", CustomFonts.tidy(" a‮b�\n "))
        assertEquals("", CustomFonts.tidy("\uD83D")) // 対にならない上位は捨てる
    }
}
