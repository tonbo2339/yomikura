package com.tonbo2339.yomikura

import android.graphics.Bitmap
import java.io.File

/**
 * 「一色のまま残っていた表示」の画像 (開発用。真っ黒になる件の切り分け) を、filesDir/blackshots/ に置く。
 * 置く数に上限を設け (古い順に捨てる)、いっぱいにならないようにする。名前の決め方・整理は Android に依存しない (単体テスト用)。
 */
object ShotStore {
    const val DIR = "blackshots"
    const val MAX_FILES = 10

    /** 時刻 (ミリ秒) から、ファイル名 (`shot-<時刻>.png`) */
    fun nameOf(at: Long) = "shot-$at.png"

    /** dir の中の画像のうち、古い方から捨てて max 個にする (名前の時刻の順) */
    fun trim(dir: File, max: Int = MAX_FILES) {
        val files = dir.listFiles { f -> f.name.startsWith("shot-") && f.name.endsWith(".png") }?.sortedBy { it.name } ?: return
        files.take((files.size - max).coerceAtLeast(0)).forEach { it.delete() }
    }

    /** 画像を保存する。できたらファイル名、できなければ null */
    fun save(filesDir: File, bmp: Bitmap, at: Long): String? = runCatching {
        val dir = File(filesDir, DIR).also { it.mkdirs() }
        val f = File(dir, nameOf(at))
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        trim(dir)
        f.name
    }.getOrNull()

    fun list(filesDir: File): List<File> =
        File(filesDir, DIR).listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name }.orEmpty()
}
