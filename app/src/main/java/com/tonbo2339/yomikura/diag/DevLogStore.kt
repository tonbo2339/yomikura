package com.tonbo2339.yomikura

import java.io.File
import java.util.TimeZone

/**
 * 開発用ログ ([DevLog]) のファイルの出し入れ。java.io だけを使う (Android に依存しない = 一時フォルダで単体テストできる)。
 * dir/devlog.log に追記し、大きくなったら dir/devlog.old に回す (1 つ前の分だけ残す)。呼ぶ側 ([DevLog]) が、1 つのスレッドの列に並べて呼ぶ。
 */
object DevLogStore {
    const val FILE = "devlog.log"
    const val OLD = "devlog.old"
    private val LEGACY = listOf("netlog.old", "netlog.log") // 前の版の通信の記録 (古い順)

    /** 1 行を追記する。いまのファイルが maxBytes を超えていたら、先に古い方 (OLD) を捨てて、いまの分を OLD に回す */
    fun append(dir: File, line: String, maxBytes: Long) {
        val f = File(dir, FILE)
        if (f.length() > maxBytes) {
            File(dir, OLD).delete()
            f.renameTo(File(dir, OLD))
        }
        f.appendText(line + "\n")
    }

    /** 古い順に、全部の行 (OLD → いまのファイル) */
    fun lines(dir: File): List<String> =
        listOf(OLD, FILE).map { File(dir, it) }.filter { it.isFile }.flatMap { it.readLines() }

    fun sizeBytes(dir: File): Long = listOf(OLD, FILE).sumOf { File(dir, it).length() }

    fun clear(dir: File) {
        File(dir, FILE).delete()
        File(dir, OLD).delete()
    }

    /** cutoff (ミリ秒) より古い行を、両方のファイルから捨てる。変わらないファイルは書き直さない */
    fun dropOlderThan(dir: File, cutoff: Long, zone: TimeZone = TimeZone.getDefault()) {
        for (name in listOf(OLD, FILE)) {
            val f = File(dir, name).takeIf { it.isFile } ?: continue
            val lines = f.readLines()
            val kept = DevLogFormat.dropOlderThan(lines, cutoff, zone)
            if (kept.size != lines.size) f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        }
    }

    /** 前の版の通信の記録 (netlog.old / netlog.log) を、新しい形にして、いまのログの先頭に取り込み、元のファイルを消す */
    fun migrateLegacy(dir: File) {
        val legacy = LEGACY.map { File(dir, it) }.filter { it.isFile }
        if (legacy.isEmpty()) return
        val converted = legacy.flatMap { it.readLines() }.mapNotNull(DevLogFormat::fromLegacyNetLog)
        if (converted.isNotEmpty()) {
            val f = File(dir, FILE)
            val existing = if (f.isFile) f.readText() else ""
            f.writeText(converted.joinToString("\n") + "\n" + existing)
        }
        legacy.forEach { it.delete() }
    }
}
