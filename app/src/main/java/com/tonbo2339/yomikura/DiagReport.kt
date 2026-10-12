package com.tonbo2339.yomikura

import org.json.JSONObject

/**
 * 診断情報 (不具合の報告に貼る文) の組み立て。Android に依存しない (単体テストで動かせる)。
 * 載せるのは、版・端末・設定の要約・データの件数・直近のエラーだけ。作品名・URL・ログイン情報・メモ・タグ・ミュートの中身は載せない。
 */
object DiagReport {
    /** 保存データの要約に載せてよい設定 (数・真偽・短い選択肢だけ)。成人向けに関わる設定やタブの名前などは載せない */
    private val SETTING_KEYS = listOf(
        "theme", "font", "size", "lh", "start", "autoCheck", "autoCheckHours", "listArrows", "dimRead",
        "pruneDone", "pruneDays", "historyMax", "historyDays",
    )

    /** 保存データの件数と、載せてよい設定。中身 (題名など) は出さない */
    fun dataSummary(d: JSONObject): List<String> {
        fun n(name: String) = d.optJSONArray(name)?.length() ?: 0
        fun k(name: String) = d.optJSONObject(name)?.length() ?: 0
        val s = d.optJSONObject("settings")
        val shown = SETTING_KEYS.mapNotNull { key ->
            val v = s?.opt(key)
            if (v == null || v == JSONObject.NULL) null else "$key=$v"
        }
        return listOf(
            "リスト ${n("readLater")} 作品 / 履歴 ${n("history")} 作品 / 読書位置 ${k("progress")} 作品",
            "設定 ${s?.length() ?: 0} 項目" + if (shown.isEmpty()) "" else " (" + shown.joinToString(", ") + ")",
        )
    }

    /** sections = 見出しと行の並び。log = 直近のエラー (新しい順に、最大 [LOG_MAX] 行) */
    fun format(title: String, sections: List<Pair<String, List<String>>>, log: List<String>): String = buildString {
        appendLine("# $title 診断情報")
        appendLine("(作品名・URL・ログイン情報・メモは含みません)")
        for ((head, lines) in sections) {
            appendLine()
            appendLine("## $head")
            lines.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## 直近のエラー・警告 (新しい順)")
        if (log.isEmpty()) appendLine("- なし") else log.take(LOG_MAX).forEach { appendLine("- $it") }
    }.trimEnd() + "\n"

    const val LOG_MAX = 30
}
