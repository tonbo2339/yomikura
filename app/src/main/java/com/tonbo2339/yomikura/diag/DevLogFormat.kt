package com.tonbo2339.yomikura

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 開発用ログ ([DevLog]) の 1 行の形と、読み書きの決まり。Android に依存しない (単体テストで動かせる)。
 * 1 件 1 行: `<時刻>\t<分類>\t<レベル>\t<要約>\t<項目>`。時刻は `yyyy-MM-dd HH:mm:ss.SSS` (端末の時刻)、レベルは I / W / E。
 * 要約・項目のタブと改行は空白にする (1 行に収めるため)。項目は `key=value` を空白でつないだ文。
 */
object DevLogFormat {
    data class Entry(val at: Long, val category: String, val level: Char, val message: String, val fields: String)

    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS"
    private const val TIME_LENGTH = 23
    private val BREAKS = Regex("""[\r\n\t]+""")

    private fun formatter(zone: TimeZone) = SimpleDateFormat(TIME_PATTERN, Locale.JAPAN).apply { timeZone = zone }
    private fun flat(s: String) = BREAKS.replace(s, " ").trim()

    /** ログに出す時刻の文 (行の時刻と同じ形) */
    fun time(at: Long, zone: TimeZone = TimeZone.getDefault()): String = formatter(zone).format(Date(at))

    /** key=value の組を、項目の文にする。値が null の組は飛ばす */
    fun fieldsOf(vararg pairs: Pair<String, Any?>): String =
        pairs.filter { it.second != null }.joinToString(" ") { "${it.first}=${it.second}" }

    fun format(at: Long, category: String, level: Char, message: String, fields: String = "", zone: TimeZone = TimeZone.getDefault()): String =
        listOf(formatter(zone).format(Date(at)), flat(category), level.toString(), flat(message), flat(fields)).joinToString("\t")

    /** 1 行を読む。形が違う行 (壊れた行・空行) は null */
    fun parse(line: String, zone: TimeZone = TimeZone.getDefault()): Entry? {
        val p = line.split('\t', limit = 5)
        if (p.size < 4 || p[0].length != TIME_LENGTH) return null
        val at = runCatching { formatter(zone).parse(p[0])?.time }.getOrNull() ?: return null
        val level = p[2].singleOrNull() ?: return null
        return Entry(at, p[1], level, p[3], p.getOrElse(4) { "" })
    }

    /** categories が空 (または null) なら全部。query は、行のどこかに (大文字・小文字を区別せず) 含まれるか */
    fun filter(lines: List<String>, categories: Set<String>?, query: String): List<String> {
        val q = query.trim()
        return lines.filter { line ->
            val cat = line.split('\t', limit = 3).getOrNull(1).orEmpty()
            (categories.isNullOrEmpty() || cat in categories) && (q.isEmpty() || line.contains(q, ignoreCase = true))
        }
    }

    /** 古い順の行から、時刻が cutoff より前の行を除く。時刻が読めない行は残す */
    fun dropOlderThan(lines: List<String>, cutoff: Long, zone: TimeZone = TimeZone.getDefault()): List<String> =
        lines.filter { line -> parse(line, zone)?.let { it.at >= cutoff } ?: true }

    /** 前の版の通信の記録 (netlog.log) の 1 行 `<時刻>\t<種類>\t<メソッド>\t<コード>\t<URL>\t<メモ>` を、新しい形にする。形が違えば null */
    fun fromLegacyNetLog(line: String): String? {
        val p = line.split('\t')
        if (p.size < 6 || p[0].length != TIME_LENGTH) return null
        return listOf(p[0], "net", "I", flat("${p[1]} ${p[2]} ${p[3]} ${p[4]}"), flat(p[5])).joinToString("\t")
    }
}
