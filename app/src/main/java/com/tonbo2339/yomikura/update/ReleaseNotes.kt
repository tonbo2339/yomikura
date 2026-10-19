package com.tonbo2339.yomikura

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * この版の変更点。文は版ごとに 1 つのファイル (app/src/<版>/assets/release-notes.md) に置き、GitHub の Release の本文もこのファイルから作る
 * (同じ文を二度書かない。Release の手順は CLAUDE.md)。
 * 出す所: アプリを更新したあとの最初の起動で 1 回だけ (MainActivity のダイアログ) と、設定の「アプリの更新」(NHStore.releaseNotes)。
 *
 * 文の書き方 (Markdown のうち、使う分だけ): `## 見出し`、`- 箇条書き`、空行で段落を分ける、`**太字**`、`[文字](URL)` (アプリでは文字だけ)、
 * `<!-- コメント -->` の行は出さない。[APP_END] の行より下は GitHub にだけ出す (動く Android の版・README への案内など)。
 * 読み取りと 1 回だけ出すかの判断は Android に依存しない (単体テストで動かせる)。
 */
object ReleaseNotes {
    const val FILE = "release-notes.md"
    /** この行より下は、アプリに出さない */
    const val APP_END = "<!-- アプリに出すのはここまで -->"
    /** 変更点を出した版 (アプリの設定の置き場。保存データとは別なので、「すべてのデータを削除」でも消えない) */
    private const val KEY_SHOWN = "releaseNotesShown"

    enum class Kind { HEADING, BULLET, PARAGRAPH }

    /** 1 まとまり (見出し・箇条書きの 1 項目・段落)。spans = (文字, 太字か) の並び */
    data class Block(val kind: Kind, val spans: List<Pair<String, Boolean>>) {
        val text: String get() = spans.joinToString("") { it.first }
    }

    private val BOLD = Regex("""\*\*(.+?)\*\*""")
    private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")

    /** 文を、まとまりの並びにする。 */
    fun parse(md: String): List<Block> {
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun endPara() {
            if (para.isNotEmpty()) out += Block(Kind.PARAGRAPH, inline(para.joinToString("")))
            para.clear()
        }
        for (raw in md.replace("\r\n", "\n").split('\n')) {
            val line = raw.trim()
            if (line == APP_END) break
            when {
                line.isEmpty() -> endPara()
                line.startsWith("<!--") && line.endsWith("-->") -> Unit
                line.startsWith("#") -> { endPara(); out += Block(Kind.HEADING, inline(line.trimStart('#').trim())) }
                line.startsWith("- ") || line.startsWith("* ") -> { endPara(); out += Block(Kind.BULLET, inline(line.substring(2).trim())) }
                else -> para += line
            }
        }
        endPara()
        return out
    }

    /** 行の中の **太字** と [文字](URL) を分ける */
    private fun inline(s: String): List<Pair<String, Boolean>> {
        val text = LINK.replace(s) { it.groupValues[1] }
        val spans = mutableListOf<Pair<String, Boolean>>()
        var at = 0
        for (m in BOLD.findAll(text)) {
            if (m.range.first > at) spans += text.substring(at, m.range.first) to false
            spans += m.groupValues[1] to true
            at = m.range.last + 1
        }
        if (at < text.length) spans += text.substring(at) to false
        return spans
    }

    /** 設定画面に渡す形: [{k: "h" | "li" | "p", s: [[文字, 太字か], …]}, …] */
    fun toJson(blocks: List<Block>): String = JSONArray(blocks.map { b ->
        JSONObject().put("k", when (b.kind) { Kind.HEADING -> "h"; Kind.BULLET -> "li"; Kind.PARAGRAPH -> "p" })
            .put("s", JSONArray(b.spans.map { (t, bold) -> JSONArray().put(t).put(bold) }))
    }).toString()

    /**
     * 変更点を出すか。shown = 前に出した版 (無ければ空)。新しく入れたとき (入れた時刻と更新した時刻が同じ) は出さない。
     * 前に出した版の記録が無くても、更新したのなら出す (この仕組みより前の版から更新したとき)
     */
    fun shouldShow(shown: String, current: String, firstInstall: Long, lastUpdate: Long): Boolean =
        shown != current && (shown.isNotEmpty() || firstInstall != lastUpdate)

    /** アプリの中の変更点の文。無ければ空 */
    fun read(ctx: Context): List<Block> = try {
        parse(ctx.assets.open(FILE).bufferedReader().use { it.readText() })
    } catch (e: java.io.IOException) {
        emptyList()
    }

    /** 変更点を出さずに、今の版を出したことにする (初回の画面を出すとき = 新しく使い始めるとき・すべてのデータを削除したあと) */
    fun markSeen(prefs: SharedPreferences) {
        prefs.edit().putString(KEY_SHOWN, BuildConfig.VERSION_NAME).apply()
    }

    /**
     * 起動時: 今回、変更点を出すか決めて、出すなら出したことにする (1 回だけ)。新しく入れたときは、出さずに今の版を覚える。
     * 出す文が無い (ファイルが読めない) ときは、出したことにせず null を返す (次の起動でもう一度試す)。出すなら、その文を返す
     */
    fun takeOnce(ctx: Context, prefs: SharedPreferences): List<Block>? {
        val current = BuildConfig.VERSION_NAME
        val shown = prefs.getString(KEY_SHOWN, "").orEmpty()
        if (shown == current) return null
        val info = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0) } catch (e: Exception) { null }
        val show = shouldShow(shown, current, info?.firstInstallTime ?: 0L, info?.lastUpdateTime ?: 0L)
        if (!show) { prefs.edit().putString(KEY_SHOWN, current).apply(); return null }
        val blocks = read(ctx)
        if (blocks.isEmpty()) return null
        prefs.edit().putString(KEY_SHOWN, current).apply()
        return blocks
    }
}
