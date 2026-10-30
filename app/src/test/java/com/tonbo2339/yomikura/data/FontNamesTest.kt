package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class FontNamesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** (platform, lang, nameId, 文字) の並びから `name` テーブルを作る。Windows・Unicode は UTF-16BE、Macintosh は 1 バイト文字 */
    private fun nameTable(vararg rec: Triple<Pair<Int, Int>, Int, String>): ByteArray {
        val strs = ByteArrayOutputStream()
        val head = ByteArrayOutputStream()
        val h = DataOutputStream(head)
        h.writeShort(0); h.writeShort(rec.size); h.writeShort(6 + rec.size * 12)
        rec.forEach { (pl, id, text) ->
            val bytes = if (pl.first == 1) text.toByteArray(Charsets.ISO_8859_1) else text.toByteArray(Charsets.UTF_16BE)
            h.writeShort(pl.first); h.writeShort(0); h.writeShort(pl.second); h.writeShort(id)
            h.writeShort(bytes.size); h.writeShort(strs.size())
            strs.write(bytes)
        }
        return head.toByteArray() + strs.toByteArray()
    }

    private val win = 3
    private fun en(id: Int, s: String) = Triple(win to 0x409, id, s)
    private fun ja(id: Int, s: String) = Triple(win to 0x411, id, s)

    @Test
    fun readsJapaneseAndEnglishFamilyNames() {
        val n = FontNames.parseNameTable(nameTable(en(1, "Yu Mincho"), ja(1, "游明朝")))!!
        assertEquals("Yu Mincho", n.english)
        assertEquals("游明朝", n.local)
    }

    @Test
    fun prefersTypographicFamilyOverFamily() {
        val n = FontNames.parseNameTable(nameTable(en(1, "Yu Gothic Bold"), en(16, "Yu Gothic"), ja(1, "游ゴシック Bold"), ja(16, "游ゴシック")))!!
        assertEquals("Yu Gothic", n.english)
        assertEquals("游ゴシック", n.local)
    }

    @Test
    fun macEnglishCountsAsEnglishAndMissingLocalIsNull() {
        val n = FontNames.parseNameTable(nameTable(Triple(1 to 0, 1, "Plain Font")))!!
        assertEquals("Plain Font", n.english)
        assertNull(n.local)
    }

    @Test
    fun ignoresOtherNameIdsAndBrokenTables() {
        assertNull(FontNames.parseNameTable(nameTable(en(4, "Full Name Only"))))
        assertNull(FontNames.parseNameTable(ByteArray(3)))
        val t = nameTable(en(1, "Abc"))
        assertNull(FontNames.parseNameTable(t.copyOf(t.size - 2).also { it[8] = 0x7f })) // 文字が表の外
    }

    @Test
    fun windowsNameBeatsMacNameAtTheSameRank() {
        // Mac の記録 (1 バイト文字) が先に並んでいても、同じ種類なら Windows (UTF-16) を使う
        val n = FontNames.parseNameTable(nameTable(Triple(1 to 0, 1, "Cafe?"), en(1, "Café")))!!
        assertEquals("Café", n.english)
    }

    @Test
    fun skipsOldWindowsEncodingsSuchAsShiftJis() {
        val t = nameTable(ja(1, "ＭＳ"), en(1, "Old Font"))
        t[6 + 3] = 2 // 最初の記録 (日本語) の encodingID を Shift_JIS (2) にする
        val n = FontNames.parseNameTable(t)!!
        assertNull(n.local)
        assertEquals("Old Font", n.english)
    }

    @Test
    fun namesAreCleanedOfDirectionAndBrokenCharacters() {
        val n = FontNames.parseNameTable(nameTable(en(1, "A\u202Eb\u200Bc\uFFFD")))!!
        assertEquals("Abc", n.english)
    }

    /** ファイルの形 (表の一覧から name を探す)。ttc は先頭のフォントを見る */
    private fun sfnt(table: ByteArray, collection: Boolean): File {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        val fontStart = if (collection) 16 else 0
        if (collection) { d.writeBytes("ttcf"); d.writeInt(0x10000); d.writeInt(1); d.writeInt(fontStart) }
        d.writeInt(0x10000); d.writeShort(1); d.writeShort(0); d.writeShort(0); d.writeShort(0)
        d.writeBytes("name"); d.writeInt(0); d.writeInt(fontStart + 28); d.writeInt(table.size)
        d.write(table)
        return tmp.newFile().also { it.writeBytes(out.toByteArray()) }
    }

    @Test
    fun readsFromTtfAndTtcFiles() {
        val t = nameTable(en(1, "Sample"), ja(1, "見本"))
        for (collection in listOf(false, true)) {
            val n = FontNames.read(sfnt(t, collection))!!
            assertEquals("Sample", n.english)
            assertEquals("見本", n.local)
        }
    }

    @Test
    fun unreadableFileGivesNull() {
        assertNull(FontNames.read(tmp.newFile().also { it.writeBytes(byteArrayOf(0, 1, 0, 0, 9, 9, 9, 9)) }))
        assertNull(FontNames.read(File(tmp.root, "none.ttf")))
    }
}
