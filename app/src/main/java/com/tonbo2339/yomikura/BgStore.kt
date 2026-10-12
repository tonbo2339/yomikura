package com.tonbo2339.yomikura

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * 画面に出さない WebView (巡回・オフライン保存) の JS に見せる、保存データの窓口 (NHStore のうち、裏の処理に要る分)。
 * 書き方は NHData を参照 (合言葉の確認・版の確認つきの書き込み)。
 * JS の窓口は名前 (NHStore) と引数の数で呼ばれるので、メソッドの形は変えない。
 * [onChanged] は保存データが書き換わったときに呼ばれる (JS の別スレッドから呼ばれるので、画面に触るなら post する)。
 */
open class BgStore(private val ctx: Context, private val onChanged: () -> Unit) {

    @JavascriptInterface
    fun get(t: String?): String = if (t == NHData.token) NHData.get(ctx) else ""

    @JavascriptInterface
    fun version(): Long = NHData.version

    @JavascriptInterface
    fun set(json: String, t: String?) {
        if (t != NHData.token) return
        NHData.set(ctx, json)
        onChanged()
    }

    @JavascriptInterface
    fun setIf(json: String, v: Long, t: String?): Boolean {
        if (t != NHData.token) return false
        return NHData.setIf(ctx, json, v).also { if (it) onChanged() }
    }
}
