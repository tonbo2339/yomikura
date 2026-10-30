package com.tonbo2339.yomikura

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「いまの状態」を 1 つの JSON にして、デバッグのページ (Edition.DEBUG_TOOLS の版だけ) に渡す。ログを読まなくても、今どうなっているかを見られるように。
 * 形の組み立て (休止の残り・保存キューの数え方) は Android に依存しない関数に分けてある (単体テスト用)。
 */
object DevState {
    /** 休止の 1 件: サイトの id・止められた時刻・残りの分 (自動の長さ)・まだ休んでいるか */
    data class Rest(val site: String, val at: Long, val leftMin: Long, val resting: Boolean)

    /** rest = 保存データの rest。止められた記録があるサイトを、残りの長い順に */
    fun rests(rest: JSONObject?, now: Long): List<Rest> {
        val keys = rest?.keys()?.asSequence()?.toList().orEmpty()
        return keys.mapNotNull { site ->
            val at = rest!!.optLong(site, 0L).takeIf { it > 0L } ?: return@mapNotNull null
            val left = (at + SiteRest.minutes(true) * 60_000L - now) / 60_000L
            Rest(site, at, left.coerceAtLeast(0L), left > 0L)
        }.sortedByDescending { it.leftMin }
    }

    /** 休止の名前。サイトの id のほか、判別だけの休み (`hamelnRating`) もある (休止中の表示 rest-timer.js と同じ名前) */
    fun restName(site: String): String = when (site) {
        "hamelnRating" -> "ハーメルン (成人向けの判別)"
        else -> BgCheck.siteName(site)
    }

    /** 画面の行に収まる時刻 (`M/d HH:mm`) */
    fun shortTime(at: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
        java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.JAPAN).apply { timeZone = zone }.format(java.util.Date(at))

    /** 何分前か (60 分以上は時間、1 日以上は日で) */
    fun ago(min: Long): String = when {
        min < 60 -> "${min} 分前"
        min < 24 * 60 -> "${min / 60} 時間前"
        else -> "${min / (24 * 60)} 日前"
    }

    /** 保存キューの状態ごとの数 (例: `RUNNING=1 WAITING=2`)。空なら "なし" */
    fun queueSummary(statuses: List<String>): String =
        if (statuses.isEmpty()) "なし" else statuses.groupingBy { it }.eachCount().entries.joinToString(" ") { "${it.key}=${it.value}" }

    fun toJson(ctx: Context): String {
        val now = System.currentTimeMillis()
        val data = NHData.obj(ctx)
        val o = JSONObject()
        o.put("now", DevLogFormat.time(now))
        o.put("rests", JSONArray().also { arr ->
            rests(data.optJSONObject("rest"), now).forEach { r ->
                arr.put(JSONObject().put("site", r.site).put("name", restName(r.site)).put("at", DevLogFormat.time(r.at)).put("leftMin", r.leftMin).put("resting", r.resting))
            }
        })
        val lastCollect = CollectCooldown.lastOf(data)
        o.put("lastCheck", if (lastCollect > 0L) "${shortTime(lastCollect)} (${ago((now - lastCollect) / 60_000L)})" else "なし")
        val nextAlarm = BgCheck.nextAlarmAt(ctx)
        o.put("nextAlarm", if (nextAlarm > 0L) shortTime(nextAlarm) else "なし (オフ)")
        val queue = SaveQueue(java.io.File(ctx.filesDir, SaveQueue.FILE_NAME)).all()
        o.put("queue", queueSummary(queue.map { it.status.name }))
        o.put("queueFailed", JSONArray().also { arr ->
            queue.filter { it.status == SaveQueue.Status.STOPPED || it.status == SaveQueue.Status.NEEDS_CHECK }
                .forEach { j -> arr.put(JSONObject().put("title", j.title).put("status", j.status.name).put("note", j.note)) }
        })
        o.put("adult", JSONObject()
            .put("narou", AdultGate.answered(AdultGate.Gate.NAROU))
            .put("hameln", AdultGate.answered(AdultGate.Gate.HAMELN)))
        return o.toString()
    }
}
