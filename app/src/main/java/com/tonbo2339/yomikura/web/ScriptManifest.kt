package com.tonbo2339.yomikura

import org.json.JSONObject

/**
 * 注入・読み込みする JS の一覧 (assets/js/scripts.json)。グループ名 → ファイルの並び (assets/js からの相対パス)。
 * 並びの中の `@名前` は、そのグループを展開する。Android に依存しない (単体テストで動かせる)。
 * 一覧を使うところ: サイトのページへの注入 (site) / 描画前に入れる分 (early) / アプリ内ページの `<!--#scripts core-->` / JS のテスト。
 */
object ScriptManifest {
    const val FILE = "js/scripts.json"
    private const val MAX_DEPTH = 4
    private val DIRECTIVE = Regex("<!--#scripts ([a-z0-9-]+)-->")

    /** group のファイルの並び。グループが無い・展開が深すぎるときは例外 (一覧の書き間違いを、読み込まれない JS にしない)。 */
    fun resolve(manifest: JSONObject, group: String, depth: Int = 0): List<String> {
        require(depth < MAX_DEPTH) { "scripts.json: $group の展開が深すぎます" }
        val arr = manifest.optJSONArray(group) ?: throw IllegalArgumentException("scripts.json: グループ $group がありません")
        return (0 until arr.length()).flatMap { i ->
            val name = arr.getString(i)
            if (name.startsWith("@")) resolve(manifest, name.substring(1), depth + 1) else listOf(name)
        }
    }

    /** HTML の `<!--#scripts 名前-->` を、そのグループの `<script src="js/…">` の並びに置き換える。 */
    fun expand(html: String, manifest: JSONObject): String = DIRECTIVE.replace(html) { m ->
        resolve(manifest, m.groupValues[1]).joinToString("\n") { "<script src=\"js/$it\"></script>" }
    }
}
