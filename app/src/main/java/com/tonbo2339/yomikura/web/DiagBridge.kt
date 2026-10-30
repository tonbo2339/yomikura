package com.tonbo2339.yomikura

import android.webkit.JavascriptInterface

/**
 * JS から使う窓口 (NHDiag)。診断情報 (Diagnostics) を見せる・コピーする・エラーの記録を消す。アプリ内ページからだけ受け付ける。
 * 診断情報は、不具合の報告に貼る文。作品名・URL・ログイン情報は含まない (DiagReport)。外へは送らず、コピーするだけ。
 */
class DiagBridge(private val host: BridgeHost) {
    /** いまの診断情報 (画面で中身を見せるため)。アプリ内ページでなければ空 */
    @JavascriptInterface
    fun report(): String = if (host.appPageNow) Diagnostics.report(host.activity) else ""

    /** 診断情報をクリップボードにコピーする。コピーできたら true */
    @JavascriptInterface
    fun copy(): Boolean {
        if (!host.appPageNow) return false
        val text = Diagnostics.report(host.activity)
        host.activity.runOnUiThread {
            Clip.copy(host.activity, "診断情報", text)
        }
        return true
    }

    // ---- 開発用ログ (Edition.DEBUG_TOOLS の版だけ。DevLog。この版で使えないときは、空・0 を返す) ----

    /** 分類の一覧 (JSON の配列 `[{"id","label","key"}]`。key = 分類ごとのスイッチの設定名。null = いつも残す) */
    @JavascriptInterface
    fun devLogCategories(): String {
        if (!host.appPageNow) return "[]"
        return org.json.JSONArray().also { arr ->
            DevLog.Category.values().forEach { arr.put(org.json.JSONObject().put("id", it.id).put("label", it.label).put("key", it.key ?: org.json.JSONObject.NULL)) }
        }.toString()
    }

    /** ログの行数 */
    @JavascriptInterface
    fun devLogLines(): Int = if (host.appPageNow) DevLog.lineCount() else 0

    /** ログの大きさ (バイト) */
    @JavascriptInterface
    fun devLogBytes(): Long = if (host.appPageNow) DevLog.sizeBytes() else 0L

    /** ログの中身。categoriesCsv = 分類の id をカンマでつないだ文 (空なら全部)。query = 含む言葉。limit = 新しい方の行数 (0 なら全部) */
    @JavascriptInterface
    fun devLogText(categoriesCsv: String, query: String, limit: Int): String {
        if (!host.appPageNow) return ""
        val cats = categoriesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return DevLog.text(cats, query, limit)
    }

    /** ログを Download フォルダへ書き出す。できたらファイル名、できなければ空 */
    @JavascriptInterface
    fun exportDevLog(): String = if (host.appPageNow) DevLog.export(host.activity).orEmpty() else ""

    /** ログを消す */
    @JavascriptInterface
    fun clearDevLog() {
        if (host.appPageNow) DevLog.clear()
    }

    /** 「一色のまま残っていた表示」の画像の枚数 */
    @JavascriptInterface
    fun shotCount(): Int = if (host.appPageNow) DevLog.shotCount(host.activity) else 0

    /** その画像を Download フォルダへ書き出す。書き出した枚数 */
    @JavascriptInterface
    fun exportShots(): Int = if (host.appPageNow) DevLog.exportShots(host.activity) else 0

    /** その画像を消す */
    @JavascriptInterface
    fun clearShots() {
        if (host.appPageNow) DevLog.clearShots(host.activity)
    }

    // ---- いまの状態・開発用の操作 (Edition.DEBUG_TOOLS の版だけ。ほかの版では、何もしない) ----

    /** いまの状態 (休止・前回の確認・次のアラーム・保存キュー・年齢確認) の JSON */
    @JavascriptInterface
    fun devState(): String = if (host.appPageNow && Edition.DEBUG_TOOLS) DevState.toJson(host.activity) else "{}"

    /** 休止を 1 件解除する */
    @JavascriptInterface
    fun clearRest(site: String) {
        if (host.appPageNow && Edition.DEBUG_TOOLS) SiteRest.clear(host.activity, site)
    }

    /** 裏での確認を、アラームを待たずに 1 回動かす (サイトへアクセスする。押したときだけ) */
    @JavascriptInterface
    fun runBgCheckNow() {
        if (host.appPageNow && Edition.DEBUG_TOOLS) {
            DevLog.eventOf(DevLog.Category.CHECK, "bg run now (debug button)")
            BgCheck.startNow(host.activity)
        }
    }

    /** エラーの記録を消す */
    @JavascriptInterface
    fun clearLog() {
        if (host.appPageNow) Diag.clear()
    }
}
