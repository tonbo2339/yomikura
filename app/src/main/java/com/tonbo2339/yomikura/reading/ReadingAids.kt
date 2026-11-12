package com.tonbo2339.yomikura

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    /**
     * 次の話へ自動で進む途中か (設定 autoNextEpisode)。最後まで読み終わったとき、次の話があれば、ページの JS (nhGoNext) で開き、
     * 開けたら ([onPageFinished]) 続けて読み始める。進む途中は、通知 (前景サービス) を消さない (消すと、画面を消している間に、アプリが止められる)。
     * 一定の時間 ([CONTINUE_MS]) のうちにページが開かなければ、あきらめて止める
     */
    private var continuing = false
    private val giveUp = Runnable { if (continuing) { continuing = false; ReadAloudService.hide(ctx) } }

    private fun readAloud(): ReadAloud = readAloud ?: ReadAloud(
        ctx.applicationContext,
        { js -> web().evaluateJavascript(js, null) },
        { r -> web().post { r() } },
        { on -> if (on) { continuing = false; showNotice(false) } else if (!continuing) { disarmTimer(); ReadAloudService.hide(ctx) } },
        { msg -> continuing = false; Messages.show(ctx, msg) },
        { paused -> showNotice(paused) },
        { goNext() },
    ).also { readAloud = it; ReadAloudControl.handler = ::onControl }

    /** 最後まで読み終わった: 設定がオンで、次の話があれば、開く。無ければ (最終話・話ページでない) そのまま終わる */
    private fun goNext() {
        if (timerExpired()) { stopByTimer(); return } // 時間がきていたら、次の話へは進まない
        if (!settings().optBoolean("autoNextEpisode", false)) return
        continuing = true
        web().removeCallbacks(giveUp)
        web().postDelayed(giveUp, CONTINUE_MS)
        web().evaluateJavascript("window.nhGoNext ? nhGoNext('tts') : false") { r ->
            if (r != "true") { continuing = false; web().removeCallbacks(giveUp); ReadAloudService.hide(ctx) }
        }
    }

    /** ページを開き終えた (MainActivity から)。次の話へ自動で進んだあとなら、続けて読み始める */
    fun onPageFinished() {
        if (!continuing || speaking) return
        web().postDelayed({ if (continuing && !speaking) startReading(quiet = true, retries = 3) }, START_DELAY_MS)
    }

    /** 通知・ロック画面に出す (作品名つき) */
    private fun showNotice(paused: Boolean) = ReadAloudService.show(ctx, paused, web().title.orEmpty())

    /** 通知のボタンから (どのスレッドからでも) */
    private fun onControl(action: String) {
        web().post {
            when (action) {
                ReadAloudControl.PAUSE -> readAloud?.pause()
                ReadAloudControl.RESUME -> readAloud?.resume()
                ReadAloudControl.STOP -> { continuing = false; disarmTimer(); stopReading() }
            }
        }
    }

    val speaking: Boolean get() = readAloud?.speaking == true

    /** 読み上げを始める・止める。本文はページの JS (reader.js の nhTtsParagraphs) が段落に分けて渡す */
    fun toggleReadAloud() {
        if (speaking) { continuing = false; disarmTimer(); stopReading(); return }
        continuing = false
        armTimer()
        startReading(quiet = false, retries = 0)
    }

    // ---- 読み上げのタイマー (設定 ttsTimer = 分。0 = なし) ----
    // 読み上げを始めた (自分で始めた) ときに数え始め、時間がきたら止める。次の話へ自動で進んで読み直すときは、数え直さない。
    // 時刻 (elapsedRealtime) でも確かめる: 画面を消している間は Handler の呼び出しが遅れることがあるので、話の終わりでも見る

    private val handler = Handler(Looper.getMainLooper())
    private val sleepStop = Runnable { stopByTimer() }
    private var timerDeadline = 0L

    private fun armTimer() {
        disarmTimer()
        val min = settings().optInt("ttsTimer", 0)
        if (min <= 0) return
        timerDeadline = SystemClock.elapsedRealtime() + min * 60_000L
        handler.postDelayed(sleepStop, min * 60_000L)
    }

    private fun disarmTimer() {
        timerDeadline = 0L
        handler.removeCallbacks(sleepStop)
    }

    /** タイマーの時間がきたか (話の終わりで、次の話へ進む前に確かめる) */
    private fun timerExpired() = timerDeadline != 0L && SystemClock.elapsedRealtime() >= timerDeadline

    /** 時間がきた: 読み上げを止め、次の話へも進まない。通知も消す */
    private fun stopByTimer() {
        disarmTimer()
        continuing = false
        web().removeCallbacks(giveUp)
        val was = speaking
        stopReading()
        ReadAloudService.hide(ctx)
        if (was) Messages.show(ctx, "タイマーで読み上げを止めました")
    }

    /**
     * 読み上げを始める。quiet = 次の話へ自動で進んだあと (始めたことを知らせない)。本文がまだ読めない (ページの JS の準備前) ときは、retries 回まで、少し待って試し直す
     */
    private fun startReading(quiet: Boolean, retries: Int) {
        web().evaluateJavascript("window.nhTtsParagraphs ? nhTtsParagraphs() : null") { r ->
            val list = runCatching {
                val arr = JSONArray(JSONTokener(r).nextValue() as String)
                List(arr.length()) { arr.optString(it) }
            }.getOrNull().orEmpty()
            if (list.isEmpty() && retries > 0) {
                web().postDelayed({ if (quiet && !continuing) return@postDelayed; startReading(quiet, retries - 1) }, START_DELAY_MS)
                return@evaluateJavascript
            }
            val rate = settings().optDouble("ttsRate", 1.0).toFloat()
            // 読み上げには画面のボタンが無いので、始めたときに止めるボタンを出す (あとは ⋮ メニューの「読み上げを止める」)
            val err = readAloud().start(list, rate)
            if (err != null) { continuing = false; disarmTimer() }
            if (!quiet || err != null) Messages.show(ctx, err ?: "読み上げを始めました (⋮ メニューでも止められます)", if (err == null) "止める" to { stopReading() } else null)
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
        continuing = false
        disarmTimer()
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
        if (event.action == KeyEvent.ACTION_DOWN) page(forward = code == KeyEvent.KEYCODE_VOLUME_DOWN)
        return true
    }

    /** 先頭へ (end = false) / 末尾へ (end = true)。⋮ メニューの上段 */
    fun scrollTo(end: Boolean) = verticalFirst("seek(${if (end) 1 else 0})") { if (end) web().pageDown(true) else web().pageUp(true) }

    /** 1 画面送る (forward = true で、読み進める向き) */
    fun page(forward: Boolean) = verticalFirst("page(${if (forward) 1 else -1})") { if (forward) web().pageDown(false) else web().pageUp(false) }

    /**
     * 縦書きのページ (本文が横にスクロールする。ページの JS の window.nhVerticalScroll) なら、そこへ頼む。そうでなければ (ふつうのページ) fallback = WebView のページ送り。
     * WebView の pageUp / pageDown は縦にしか動かないので、縦書きのページでは何も起きない
     */
    private fun verticalFirst(call: String, fallback: () -> Unit) {
        web().evaluateJavascript("(window.nhVerticalScroll&&nhVerticalScroll.active()?(nhVerticalScroll.$call,true):false)") { r ->
            if (r != "true") fallback()
        }
    }

    private companion object {
        /** 次の話へ自動で進んでから、ページが開くまで待つ長さ (これを過ぎたら、あきらめて止める) */
        const val CONTINUE_MS = 45_000L
        /** ページを開き終えてから、読み始める (本文の準備を待つ) までの間 */
        const val START_DELAY_MS = 1_200L
    }

}
