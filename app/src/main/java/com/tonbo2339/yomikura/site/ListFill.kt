package com.tonbo2339.yomikura

import org.json.JSONArray
import org.json.JSONObject

/**
 * リスト画面を開いたあと、足りない作品の情報を作品ページを読んで補う:
 *  - ハーメルンの作品が R-18 か (HamelnRating) → 作品の r18
 *  - サイトが分からない R18 の作品 (narou18) がどのサイトか (R18Subs) → 作品の sub
 * 分かったらリスト (readLater) と閲覧履歴 (history) の同じ作品に書き、終わったらリスト画面に知らせる (nhSubsFilled)。
 * NSFW モード中は R18 のドメインへ行くおそれがあるので通信しない (呼ぶ側が確かめる)。アプリ内ページからだけ受け付ける確認は呼ぶ側 (StoreBridge)。
 * どちらも画面のスレッドから呼ばれ、ページは画面に出さない WebView で開く (PageProbe。結果もメインスレッドで返る)。
 */
class ListFill(private val host: BridgeHost) {
    private val ctx get() = host.activity

    /** ids = 作品の ID。ハーメルンの R-18 を判別して作品の r18 を書く。 */
    fun hamelnR18(ids: List<String>) {
        // 年齢確認に答えていないと、ハーメルンに止められる (判別が止まる)。その前に答えるよう案内して、行かない
        if (!AdultGate.answered(AdultGate.Gate.HAMELN)) { hintAdultGate(AdultGate.Gate.HAMELN); return }
        HamelnRating.fill(ctx, ids, { id, r18 ->
            // 閲覧履歴にも書く (リストから外したあとも、NSFW モードで履歴から隠せるように)
            NHData.update(ctx) { data -> putOnWork(data, "hameln:$id", "r18", r18) }
        }, {
            host.evalJs("window.nhSubsFilled && nhSubsFilled()")
        // 判別が止められても、ハーメルン全体は休ませない (更新の確認まで止めない)。判別だけの休みを別に記録する。ハーメルン全体が休み中のときは、判別も行かない
        }, { SiteRest.isResting(ctx, "hameln", auto = true) || SiteRest.isResting(ctx, HAMELN_RATING_REST, auto = true) }, { SiteRest.mark(ctx, HAMELN_RATING_REST) },
            onStopped = { hintAdultGate(AdultGate.Gate.HAMELN) },
            stop = { NHData.nsfwOn(NHData.settings(ctx)) }) // 判別の途中で NSFW モードがオンになったら、残りは取りに行かない
    }

    /** ids = 作品の ID。サイトが分からない R18 の作品の sub を書く。 */
    fun r18Subs(ids: List<String>) {
        // 年齢確認に答えていないと、確認の画面しか読めずに判別できない。答えるよう案内して、行かない
        if (!AdultGate.answered(AdultGate.Gate.NAROU)) { hintAdultGate(AdultGate.Gate.NAROU); return }
        var found = 0
        R18Subs.fill(ctx, ids, { id, sub ->
            NHData.update(ctx) { data -> putOnWork(data, "narou18:$id", "sub", sub) }
            found++
        }, {
            if (found > 0) host.evalJs("window.nhSubsFilled && nhSubsFilled()")
        }, { SiteRest.isResting(ctx, "narou18", auto = true) }, { SiteRest.mark(ctx, "narou18") },
            stop = { NHData.nsfwOn(NHData.settings(ctx)) }) // 判別の途中で NSFW モードがオンになったら、残りは取りに行かない
    }

    /**
     * R18 の年齢確認に答えていないとき、答えるよう案内する (アプリの起動 1 回につき、サイトごとに 1 度)。
     * アプリは年齢確認を自動では押さない。ボタンで、そのサイトの成人向けを開く (年齢確認の画面が出るので、自分で答える)
     */
    private fun hintAdultGate(gate: AdultGate.Gate) {
        if (!hinted.add(gate)) return
        DevLog.eventOf(DevLog.Category.SITE, "adult gate hint", "gate" to gate.name)
        Messages.show(
            ctx, gate.message,
            gate.button to {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(gate.openUrl)).setPackage(ctx.packageName)) }
            },
        )
    }

    /** リスト (readLater) と閲覧履歴 (history) の、key の作品に name = value を書く */
    private fun putOnWork(data: JSONObject, key: String, name: String, value: Any) {
        for (list in listOf("readLater", "history")) {
            val a = data.optJSONArray(list) ?: continue
            for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("key") == key }?.put(name, value)
        }
    }

    companion object {
        private val hinted = java.util.Collections.synchronizedSet(HashSet<AdultGate.Gate>())
        /** SiteRest の記録で、ハーメルンの R-18 の判別だけを休ませるための名前 (サイトの id ではない) */
        private const val HAMELN_RATING_REST = "hamelnRating"
        /** JS から渡された作品 ID の配列 (JSON)。読めなければ null */
        fun parseIds(json: String): List<String>? =
            try { JSONArray(json).let { arr -> List(arr.length()) { arr.optString(it) } } } catch (e: org.json.JSONException) { null }
    }
}
