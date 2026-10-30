package com.tonbo2339.yomikura

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * フォントのファイル (ttf / otf / ttc) の中の `name` テーブルから、ファミリー名を読む。Android には依存しない。
 * [english] = 英語の名前 (内部の名前に使う)、[local] = 日本語の名前 (一覧に出す初めの名前。無ければ null)。
 * コレクション (ttc) は、先頭のフォントを見る。読めないとき・壊れているときは null (追加は断らず、ファイル名に戻す)。
 */
object FontNames {
    class Names(val english: String?, val local: String?)

    private const val MAX_NAME_TABLE = 512 * 1024
    private const val NAME_FAMILY = 1
    private const val NAME_TYPO_FAMILY = 16
    private const val LANG_EN_WIN = 0x409
    private const val LANG_JA_WIN = 0x411

    fun read(file: File): Names? = try {
        RandomAccessFile(file, "r").use { parse(it) }
    } catch (e: IOException) { null } catch (e: RuntimeException) { null }

    private fun parse(f: RandomAccessFile): Names? {
        val len = f.length()
        var base = 0L
        f.seek(0)
        if (f.readInt() == 0x74746366) { // 'ttcf'
            f.seek(12)
            base = f.readInt().toLong() and 0xFFFFFFFFL
        }
        if (base + 12 > len) return null
        f.seek(base + 4)
        val numTables = f.readUnsignedShort()
        f.seek(base + 12)
        var nameOffset = -1L
        var nameLength = 0L
        repeat(numTables) {
            val tag = ByteArray(4).also { f.readFully(it) }
            f.readInt()
            val off = f.readInt().toLong() and 0xFFFFFFFFL
            val l = f.readInt().toLong() and 0xFFFFFFFFL
            if (String(tag, Charsets.ISO_8859_1) == "name") { nameOffset = off; nameLength = l }
        }
        if (nameOffset < 0 || nameLength < 6 || nameLength > MAX_NAME_TABLE || nameOffset + nameLength > len) return null
        val t = ByteArray(nameLength.toInt())
        f.seek(nameOffset)
        f.readFully(t)
        return parseNameTable(t)
    }

    /** `name` テーブルの中身 (表の頭から) から名前を取る。単体テストから直接呼べる */
    fun parseNameTable(t: ByteArray): Names? {
        if (t.size < 6) return null
        val count = u16(t, 2)
        val strings = u16(t, 4)
        var en: String? = null
        var ja: String? = null
        var enRank = 0
        var jaRank = 0
        for (i in 0 until count) {
            val r = 6 + i * 12
            if (r + 12 > t.size) break
            val platform = u16(t, r)
            val encoding = u16(t, r + 2)
            val lang = u16(t, r + 4)
            val id = u16(t, r + 6)
            if (id != NAME_FAMILY && id != NAME_TYPO_FAMILY) continue
            if (platform == 3 && encoding != 0 && encoding != 1 && encoding != 10) continue // Shift_JIS などの古い符号化は読まない
            // 太字などの枠を除いた名前 (16) を先に。同じなら Windows (UTF-16) を Macintosh より先に
            val rank = (if (id == NAME_TYPO_FAMILY) 4 else 2) + (if (platform == 3) 1 else 0)
            val from = strings + u16(t, r + 10)
            val n = u16(t, r + 8)
            if (from + n > t.size) continue
            val s = decode(platform, t, from, n) ?: continue
            when {
                platform == 3 && lang == LANG_JA_WIN && rank > jaRank -> { ja = s; jaRank = rank }
                (platform == 3 && lang == LANG_EN_WIN || platform == 1 && lang == 0) && rank > enRank -> { en = s; enRank = rank }
            }
        }
        return if (en == null && ja == null) null else Names(en, ja)
    }

    /** Windows (3)・Unicode (0) は UTF-16BE、Macintosh (1) の英語は 1 バイト文字。それ以外は読まない */
    private fun decode(platform: Int, t: ByteArray, from: Int, n: Int): String? {
        val raw = when (platform) {
            3, 0 -> String(t, from, n, Charsets.UTF_16BE)
            1 -> String(t, from, n, Charsets.ISO_8859_1)
            else -> return null
        }
        return CustomFonts.tidy(raw).ifEmpty { null }
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
