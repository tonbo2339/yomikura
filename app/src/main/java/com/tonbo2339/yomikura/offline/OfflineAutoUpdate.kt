package com.tonbo2339.yomikura

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * オフライン保存の自動更新: 更新の確認で新しい話が出た作品のうち、端末に保存してある作品を、保存キューに入れる
 * (保存を始めるとき、作品の目次を 1 ページ読んで、保存していない話だけを取る。従量課金の回線では始めない)。
 * 設定 offlineAutoUpdate (既定オフ) がオンのときだけ。更新の確認の結果 (collectLog) の keys = 新しい話が出た作品のキー (サイト:ID)。
 */
object OfflineAutoUpdate {
    private const val TAG = "Yomikura.offline"

    /** 結果 (collectLog の JSON) から、キューに入れる作品 (サイト, ID) を選ぶ。保存済みの作品だけ。R18 は NSFW モード中は除く。形が違えば空 */
    fun candidates(logJson: String, hasSaved: (String, String) -> Boolean, nsfwOn: Boolean): List<Pair<String, String>> {
        val log = logJson.toJsonOrNull() ?: return emptyList()
        val out = LinkedHashSet<Pair<String, String>>()
        for (site in log.keys()) {
            val keys = log.optJSONObject(site)?.optJSONArray("keys") ?: continue
            for (i in 0 until keys.length()) {
                val key = keys.optString(i)
                val at = key.indexOf(':')
                if (at <= 0) continue
                val s = key.substring(0, at)
                val id = key.substring(at + 1)
                if (!SaveQueue.isSafe(s) || !SaveQueue.isSafe(id)) continue
                if (s == "narou18" && nsfwOn) continue
                if (hasSaved(s, id)) out += s to id
            }
        }
        return out.toList()
    }

    /** 更新の確認が終わったとき (Collector から) */
    fun onCollected(ctx: Context, logJson: String) {
        try {
            if (!NHData.settings(ctx).optBoolean("offlineAutoUpdate", false)) return
            val store = OfflineStore(File(ctx.filesDir, "offline"))
            val queue = SaveQueue(File(ctx.filesDir, SaveQueue.FILE_NAME))
            val list = candidates(logJson, { s, id -> store.meta(s, id).isNotEmpty() }, NHData.nsfwOn(NHData.settings(ctx)))
            var added = 0
            for ((site, id) in list) {
                val meta = store.meta(site, id).toJsonOrNull() ?: continue
                val url = meta.optString("url")
                if (SiteUrls.safeNavUrl(url) == null) continue
                val job = SaveQueue.Job(
                    site = site, id = id, title = meta.optString("title", id), url = url, eps = emptyList(),
                    total = meta.optInt("total"), refresh = true, addedAt = System.currentTimeMillis(),
                )
                if (queue.add(job) > 0) added++
            }
            // 始められなかったとき (アプリが前に出ていないなど) は、キューに残り、次にアプリを開いたときに続きから進む
            if (added > 0) OfflineService.start(ctx)
        } catch (e: Exception) {
            Diag.w(TAG, "auto update", e)
        }
    }
}
