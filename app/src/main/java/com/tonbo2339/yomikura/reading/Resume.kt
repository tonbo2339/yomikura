package com.tonbo2339.yomikura

import org.json.JSONObject

/**
 * 「続きから読む」で開く先 (ランチャーのショートカット)。アプリで最後に読んだ作品の、読書位置のある話。Android に依存しない (単体テストで動かせる)。
 *
 * - 閲覧履歴のうち、アプリで開いた時刻 (appAt) がいちばん新しい作品。NSFW モード中は、R18 の作品を飛ばして次に新しいものにする
 * - その作品の読書位置 (progress) が、読了で次の話 (next) が分かっていれば次の話、そうでなければ読んでいた話 (url)。位置は開いたときにサイトのページが復元する
 * - 読書位置が無ければ、履歴の url。https でなければ (壊れた記録) null
 */
object Resume {
    fun latestUrl(d: JSONObject, nsfw: Boolean): String? {
        val h = d.optJSONArray("history") ?: return null
        var best: JSONObject? = null
        for (i in 0 until h.length()) {
            val e = h.optJSONObject(i) ?: continue
            if (e.optLong("appAt") <= 0L) continue
            if (nsfw && isR18(e, d)) continue
            if (best == null || e.optLong("appAt") > best.optLong("appAt")) best = e
        }
        val e = best ?: return null
        val p = d.optJSONObject("progress")?.optJSONObject(e.optString("key"))
        val next = if (p != null && p.optBoolean("done") && !p.isNull("next")) p.optString("next") else ""
        val read = if (p != null && !p.isNull("url")) p.optString("url") else ""
        val last = if (e.isNull("url")) "" else e.optString("url")
        return listOf(next, read, last).firstOrNull { it.startsWith("https://") }
    }

    private fun isR18(e: JSONObject, d: JSONObject): Boolean {
        if (e.optString("site") == "narou18" || e.optBoolean("r18")) return true
        val key = e.optString("key")
        val list = d.optJSONArray("readLater") ?: return false
        for (i in 0 until list.length()) {
            val w = list.optJSONObject(i) ?: continue
            if (w.optString("key") == key && w.optBoolean("r18")) return true
        }
        return false
    }
}
