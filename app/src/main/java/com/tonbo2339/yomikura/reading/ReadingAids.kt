package com.tonbo2339.yomikura

import android.content.Context
import android.view.KeyEvent
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 読書の補助: 本文の読み上げ (ReadAloud)・自動スクロール (ページの JS)・音量キーでページを送る。始めるのは ⋮ メニュー。
 * 本文の段落分け・自動スクロールの動き・止めるボタンは、ページの JS (reader.js)。
 *
 * - 読み上げは、ページが変わる・画面を離れるときに呼ぶ側 (MainActivity) が [stopReading] する。アプリを閉じるときは [shutdown]
 * - settings = 保存データの settings (ttsRate = 読み上げの速さ、1.0 が標準。volumeKeys = 音量キーでページを送る)
 * - onWorkPage = いまサイトの作品のページを開いているか (音量キーは、そのときだけ送る)
 */
class ReadingAids(
    private val ctx: Context,
    private val web: () -> WebView,
    private val settings: () -> JSONObject,
    private val onWorkPage: () -> Boolean,
) {
    private var readAloud: ReadAloud? = null

    private fun readAloud(): ReadAloud = readAloud ?: ReadAloud(
        ctx.applicationContext,
        { js -> web().evaluateJavascript(js, null) },
        { r -> web().post { r() } },
        { on -> if (on) showNotice(false) else ReadAloudService.hide(ctx) },
        { msg -> Messages.show(ctx, msg) },
        { paused -> showNotice(paused) },
    ).also { readAloud = it; ReadAloudControl.handler = ::onControl }

    /** 通知・ロック画面に出す (作品名つき) */
    private fun showNotice(paused: Boolean) = ReadAloudService.show(ctx, paused, web().title.orEmpty())

    /** 通知のボタンから (どのスレッドからでも) */
    private fun onControl(action: String) {
        web().post {
            when (action) {
                ReadAloudControl.PAUSE -> readAloud?.pause()
                ReadAloudControl.RESUME -> readAloud?.resume()
                ReadAloudControl.STOP -> stopReading()
            }
        }
    }

    val speaking: Boolean get() = readAloud?.speaking == true

    /** 読み上げを始める・止める。本文はページの JS (reader.js の nhTtsParagraphs) が段落に分けて渡す */
    fun toggleReadAloud() {
        if (speaking) { stopReading(); return }
        web().evaluateJavascript("window.nhTtsParagraphs ? nhTtsParagraphs() : null") { r ->
            val list = runCatching {
                val arr = JSONArray(JSONTokener(r).nextValue() as String)
                List(arr.length()) { arr.optString(it) }
            }.getOrNull().orEmpty()
            val rate = settings().optDouble("ttsRate", 1.0).toFloat()
            // 読み上げには画面のボタンが無いので、始めたときに止めるボタンを出す (あとは ⋮ メニューの「読み上げを止める」)
            val err = readAloud().start(list, rate)
            Messages.show(ctx, err ?: "読み上げを始めました (⋮ メニューでも止められます)", if (err == null) "止める" to { stopReading() } else null)
        }
    }

    /** 自動スクロールを始める・止める (ページの JS。止めるボタンと速さの調整は、ページの下に出る) */
    fun toggleAutoScroll() {
        web().evaluateJavascript("window.nhAutoScroll ? nhAutoScroll() : null", null)
    }

    /** 読み上げを止める (読んでいなければ何もしない) */
    fun stopReading() {
        readAloud?.stop()
    }

    fun shutdown() {
        readAloud?.shutdown()
        readAloud = null
        ReadAloudControl.handler = null
        ReadAloudService.hide(ctx)
    }

    /**
     * 音量キーでページを送る (設定 volumeKeys。既定はオフ): 作品のページを読んでいるあいだ、音量の「下」で 1 画面下へ、「上」で 1 画面上へ。
     * 送ったら true (そのあいだは、音量の調整には使わない)。押しっぱなしでも続けて送る
     */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP) return false
        if (!settings().optBoolean("volumeKeys", false) || !onWorkPage()) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) web().pageDown(false) else web().pageUp(false)
        }
        return true
    }

}
