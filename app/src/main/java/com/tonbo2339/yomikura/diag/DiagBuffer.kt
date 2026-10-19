package com.tonbo2339.yomikura

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 直近のエラー・警告の記録 (新しいものが後ろ。古いものから捨てる輪)。Diag が使う。Android に依存しない (単体テストで動かせる)。
 *
 * 診断情報として人に見せる・貼る前提なので、記録するときに URL はホスト名だけにし、長い文は切る ([sanitize])。
 * 保存用の文字列は 1 件 1 行 `<時刻ミリ秒>\t<レベル>\t<タグ>\t<メッセージ>`。
 */
class DiagBuffer(private val max: Int = 60) {
    data class Entry(val at: Long, val level: Char, val tag: String, val message: String)

    private val items = ArrayDeque<Entry>()

    @Synchronized
    fun add(at: Long, level: Char, tag: String, message: String) {
        items.addLast(Entry(at, level, sanitize(tag, 40), sanitize(message)))
        while (items.size > max) items.removeFirst()
    }

    /** 古い順 */
    @Synchronized
    fun entries(): List<Entry> = items.toList()

    @Synchronized
    fun clear() = items.clear()

    @Synchronized
    fun toText(): String = items.joinToString("\n") { "${it.at}\t${it.level}\t${it.tag}\t${it.message}" }

    /** 保存した文字列から戻す。壊れた行は飛ばす */
    @Synchronized
    fun load(text: String) {
        items.clear()
        for (line in text.lineSequence()) {
            val p = line.split('\t', limit = 4)
            val at = p.getOrNull(0)?.toLongOrNull() ?: continue
            val level = p.getOrNull(1)?.singleOrNull() ?: continue
            if (p.size < 4) continue
            items.addLast(Entry(at, level, p[2], p[3]))
        }
        while (items.size > max) items.removeFirst()
    }

    companion object {
        private val URL = Regex("""https?://([^/\s?#]+)[^\s]*""")

        /** URL はホスト名だけ (作品の ID や、読んでいる作品を、記録に残さない)。改行・タブは空白に。長ければ切る */
        fun sanitize(s: String, maxLen: Int = 200): String {
            val flat = URL.replace(s) { "https://${it.groupValues[1]}/…" }.replace(Regex("""[\r\n\t]+"""), " ").trim()
            return if (flat.length > maxLen) flat.take(maxLen - 1) + "…" else flat
        }

        /** 診断情報の 1 行: `10/11 09:30 W Collector: stalled at https://ncode.syosetu.com/…` */
        fun line(e: Entry, zone: TimeZone = TimeZone.getDefault()): String {
            val f = SimpleDateFormat("M/d HH:mm", Locale.JAPAN).apply { timeZone = zone }
            return "${f.format(java.util.Date(e.at))} ${e.level} ${e.tag}: ${e.message}"
        }
    }
}
