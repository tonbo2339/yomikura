package com.tonbo2339.yomikura

import android.content.Context
import org.json.JSONObject

/**
 * アクセスを止められた (403 / 429 / 503 / 確認画面) サイトを、しばらく休ませる記録。保存データの rest = {サイトの id: 止められた時刻}
 * (JS の NH.markRest / NH.isResting と同じ。サイトの id は narou / narou18 / hameln / kakuyomu)。
 * 更新の確認・オフライン保存・作品の判別が、どれも同じ記録を見て、止められたサイトには続けて取りに行かない。
 */
object SiteRest {
    /** 休ませる分: 自動 (起動時・裏・作品の判別) は 60 分、手動は 10 分。JS の NH.restMinutes と同じ */
    fun minutes(auto: Boolean): Int = if (auto) 60 else 10

    /** rest = 保存データの rest (無ければ null)。Android に依存しない */
    fun isResting(rest: JSONObject?, site: String, auto: Boolean, now: Long): Boolean {
        val at = rest?.optLong(site, 0L) ?: 0L
        return at > 0 && now - at < minutes(auto) * 60_000L
    }

    fun isResting(ctx: Context, site: String, auto: Boolean): Boolean =
        isResting(NHData.obj(ctx).optJSONObject("rest"), site, auto, System.currentTimeMillis())

    /** 休止を 1 件解除する (開発用。止められた記録を消す)。サイトの id は narou / narou18 / hameln / kakuyomu / hamelnRating */
    fun clear(ctx: Context, site: String) {
        NHData.update(ctx) { d -> d.optJSONObject("rest")?.remove(site) }
        DevLog.eventOf(DevLog.Category.SITE, "rest cleared", "site" to site)
    }

    /** 止められたことを記録する */
    fun mark(ctx: Context, site: String) {
        NHData.update(ctx) { d ->
            val rest = d.optJSONObject("rest") ?: JSONObject().also { d.put("rest", it) }
            rest.put(site, System.currentTimeMillis())
        }
        DevLog.eventOf(DevLog.Category.SITE, "rest", "site" to site, "minutes" to "${minutes(false)}/${minutes(true)}")
    }
}
