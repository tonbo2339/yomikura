package com.tonbo2339.yomikura

/**
 * アプリ内ページ (assets/web の HTML) の `<!--#include 名前-->` を、その場で中身に置き換える。Android に依存しない (単体テストで動かせる)。
 *
 * - 名前は assets/web からの相対パス (英数字・ハイフン・ドット・スラッシュ)。`settings/page-list.html` のように、フォルダの下も指せる
 * - `..` や、スラッシュで始まる名前・`//` を含む名前は読まない (assets/web の外へ出さない)
 * - 読めない (ファイルが無い・名前が不正) ときは空にする。版によってだけある画面の差し込み口は、その版にファイルが無ければ空になる
 * - 読んだ中身の中の include も展開する (深さは [MAX_DEPTH] まで。超えた分は空)。置き換える中身は、そのまま (`$` や `\` も) 入れる
 */
object Includes {
    private val INCLUDE = Regex("<!--#include ([A-Za-z0-9._/-]+)-->")
    const val MAX_DEPTH = 4

    /** 読んでよい名前か */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && !name.contains("..") && !name.contains("//") && !name.endsWith("/")

    /** [read] は assets/web からの相対パスで中身を返す。読めなければ null (例外を投げてもよい: 空にする) */
    fun expand(html: String, read: (String) -> String?, depth: Int = 0): String {
        if (depth >= MAX_DEPTH) return INCLUDE.replace(html) { "" }
        return INCLUDE.replace(html) { m ->
            val name = m.groupValues[1]
            val body = if (isValidName(name)) runCatching { read(name) }.getOrNull() else null
            if (body == null) "" else expand(body, read, depth + 1)
        }
    }
}
