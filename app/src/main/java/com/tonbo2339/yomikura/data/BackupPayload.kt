package com.tonbo2339.yomikura

import org.json.JSONArray
import org.json.JSONObject

/**
 * バックアップで送る中身を、保存データ (NHData の JSON) から作る。送り先に関わらず共通で使う。
 * 画面が無くても (裏での確認でも) 作れるように Kotlin に置く。Android に依存しない (単体テストで動かせる)。
 *
 * - スナップショット: 設定の「書き出す」と同じ形 {app, format:1, exportedAt, appVersion, parts}。app は版の形式名 (Edition.BACKUP_APP)。
 *   項目名と保存データのキーは、画面の NH.BACKUP_PARTS (core/backup.js。読み込み nhImport もこれを見る) と同じにする
 * - 閲覧履歴: アプリで読んだ時刻 (appAt) が since より新しい作品を古い順に {at, site, sub, id, title}。
 *   サイト名と作品の URL は送り先が作る。読んだ位置は入れない
 */
object BackupPayload {
    /** 種類 → [項目名 to 保存データのキー]。送り先の受け口の種類と同じ */
    val SNAPSHOTS: Map<String, List<Pair<String, String>>> = linkedMapOf(
        "settings" to listOf("settings" to "settings", "mute" to "mute"),   // めったに変わらない
        "data" to listOf("list" to "readLater", "progress" to "progress"),  // 毎日変わる
    )
    /** 無いときに空の配列にする項目 (ほかは空のオブジェクト) */
    private val ARRAYS = setOf("readLater", "history")

    /** app = 書き出しの形式名 (JSON の app)。送り先の受け口が検証する名前を渡す */
    fun snapshot(d: JSONObject, kind: String, app: String, appVersion: String, now: Long): String {
        val parts = JSONObject()
        for ((part, key) in SNAPSHOTS.getValue(kind)) parts.put(part, d.opt(key) ?: if (key in ARRAYS) JSONArray() else JSONObject())
        return JSONObject()
            .put("app", app).put("format", 1)
            .put("exportedAt", java.time.Instant.ofEpochMilli(now).toString())
            .put("appVersion", appVersion)
            .put("parts", parts)
            .toString()
    }

    fun history(d: JSONObject, since: Long): JSONArray {
        val h = d.optJSONArray("history") ?: return JSONArray()
        val picked = (0 until h.length()).mapNotNull { h.optJSONObject(it) }
            .filter { it.optLong("appAt") > since && it.optString("title").isNotEmpty() && it.has("site") && it.has("id") }
            .sortedBy { it.optLong("appAt") }
        return JSONArray(picked.map { e ->
            JSONObject().put("at", e.optLong("appAt")).put("site", e.optString("site")).put("id", e.optString("id"))
                .put("title", e.optString("title")).apply { if (e.optString("sub").isNotEmpty()) put("sub", e.optString("sub")) }
        })
    }
}
