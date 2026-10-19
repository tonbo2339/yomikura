package com.tonbo2339.yomikura

import org.json.JSONArray
import org.json.JSONObject

/**
 * リスト画面を開いたあと、足りない作品の情報を作品ページを読んで補う:
 *  - ハーメルンの作品が R-18 か (HamelnRating) → 作品の r18
 *  - サイトが分からない R18 の作品 (narou18) がどのサイトか (R18Subs) → 作品の sub
 * 分かったらリスト (readLater) と閲覧履歴 (history) の同じ作品に書き、終わったらリスト画面に知らせる (nhSubsFilled)。
 * NSFW モード中は R18 のドメインへ行くおそれがあるので通信しない (呼ぶ側が確かめる)。アプリ内ページからだけ受け付ける確認は呼ぶ側 (StoreBridge)。
 * どちらも画面のスレッドから呼ばれ、通信は裏のスレッドで行う。
 */
class ListFill(private val host: BridgeHost) {
    private val ctx get() = host.activity

    /** ids = 作品の ID。ハーメルンの R-18 を判別して作品の r18 を書く。 */
    fun hamelnR18(ids: List<String>) {
        HamelnRating.fill(ids, host.userAgent(), { id, r18 ->
            // 閲覧履歴にも書く (リストから外したあとも、NSFW モードで履歴から隠せるように)
            NHData.update(ctx) { data -> putOnWork(data, "hameln:$id", "r18", r18) }
        }, {
            host.evalJs("window.nhSubsFilled && nhSubsFilled()")
        }, { SiteRest.isResting(ctx, "hameln", auto = true) }, { SiteRest.mark(ctx, "hameln") })
    }

    /** ids = 作品の ID。サイトが分からない R18 の作品の sub を書く。 */
    fun r18Subs(ids: List<String>) {
        var found = 0
        R18Subs.fill(ids, host.userAgent(), { id, sub ->
            NHData.update(ctx) { data -> putOnWork(data, "narou18:$id", "sub", sub) }
            found++
        }, {
            if (found > 0) host.evalJs("window.nhSubsFilled && nhSubsFilled()")
        }, { SiteRest.isResting(ctx, "narou18", auto = true) }, { SiteRest.mark(ctx, "narou18") },
            stop = { NHData.nsfwOn(NHData.settings(ctx)) }) // 判別の途中で NSFW モードがオンになったら、残りは取りに行かない
    }

    /** リスト (readLater) と閲覧履歴 (history) の、key の作品に name = value を書く */
    private fun putOnWork(data: JSONObject, key: String, name: String, value: Any) {
        for (list in listOf("readLater", "history")) {
            val a = data.optJSONArray(list) ?: continue
            for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("key") == key }?.put(name, value)
        }
    }

    companion object {
        /** JS から渡された作品 ID の配列 (JSON)。読めなければ null */
        fun parseIds(json: String): List<String>? =
            try { JSONArray(json).let { arr -> List(arr.length()) { arr.optString(it) } } } catch (e: org.json.JSONException) { null }
    }
}
