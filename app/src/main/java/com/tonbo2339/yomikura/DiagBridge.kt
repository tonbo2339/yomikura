package com.tonbo2339.yomikura

import android.content.ClipData
import android.content.ClipboardManager
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
            host.activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("診断情報", text))
        }
        return true
    }

    /** エラーの記録を消す */
    @JavascriptInterface
    fun clearLog() {
        if (host.appPageNow) Diag.clear()
    }
}
