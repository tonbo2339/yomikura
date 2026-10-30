package com.tonbo2339.yomikura

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import org.json.JSONTokener

/**
 * 画面に出さない WebView (更新の確認・オフライン保存) で、ページの読み込み完了 (onPageFinished) が来ないときの見張り。
 * 公開版は広告を止めないので、広告の同期用の枠などが終わらず、本文は読めているのに onPageFinished がいつまでも来ないことがある。
 * 本文を読み終えて (document.readyState が loading でなくなって) から [GRACE_MS] たっても完了しなければ、そのページで onReady を呼ぶ。
 * 呼ぶ側は、onPageFinished と onReady のどちらから来ても同じ処理 (JS を入れる) にする。
 * また、loadUrl したのに読み込みが始まらない (about:blank のまま) ことがあるので、[START_MS] たっても始まらなければ onNoStart を呼ぶ
 * (呼ぶ側が読み込み直す。[MAX_RESTARTS] 回まで)。
 * メインスレッドで使う。
 */
class PageReady(
    private val main: Handler,
    private val onNoStart: (WebView) -> Unit,
    private val onReady: (WebView, String) -> Unit,
) {
    private var view: WebView? = null
    private var watchAt = 0L // 見張りを始めた (読み込み直した) 時刻
    private var restarts = 0
    private var started = false // 一度でもページが始まった (https の URL になった)。そのあとは読み込み直さない (巡回の JS がページを移る途中は URL が取れない)
    private var finished: String? = null // onPageFinished が来たページ
    private var fired: String? = null // 見張りが onReady を呼んだページ
    private var seenUrl: String? = null // 本文を読み終えていたページと、そう見えた時刻
    private var seenAt = 0L

    private val tick: Runnable = Runnable {
        val v = view ?: return@Runnable
        v.evaluateJavascript("document.readyState + ' ' + location.href") { r ->
            if (view !== v) return@evaluateJavascript
            val s = runCatching { JSONTokener(r).nextValue() as? String }.getOrNull().orEmpty()
            val state = s.substringBefore(' ')
            val url = s.substringAfter(' ', "")
            val now = SystemClock.uptimeMillis()
            if (url.startsWith("https://")) started = true
            else if (!started && now - watchAt >= START_MS && restarts < MAX_RESTARTS) {
                restarts++
                watchAt = now
                Log.d(TAG, "load did not start; retry $restarts")
                onNoStart(v)
            }
            if (state != "loading" && url.startsWith("https://") && url != finished && url != fired) {
                if (url != seenUrl) { seenUrl = url; seenAt = now }
                else if (now - seenAt >= GRACE_MS) {
                    fired = url
                    Log.d(TAG, "ready before load finished: $url")
                    onReady(v, url)
                }
            }
            if (view === v) main.postDelayed(tick, POLL_MS)
        }
    }

    /** 見張りを始める (WebView を作って loadUrl したあと) */
    fun watch(v: WebView) {
        view = v
        watchAt = SystemClock.uptimeMillis()
        main.removeCallbacks(tick)
        main.postDelayed(tick, POLL_MS)
    }

    /** onPageStarted から呼ぶ。同じ URL を読み込み直したとき (エラーのあとのやり直し・確認画面を通したあと) も、新しいページとして見張る */
    fun started(url: String?) {
        finished = null
        fired = null
        seenUrl = null
    }

    /** onPageFinished から呼ぶ。true なら、見張りがすでにそのページで onReady を呼んだので、もう一度は入れない */
    fun finished(url: String?): Boolean {
        finished = url
        return url != null && url == fired
    }

    fun stop() {
        view = null
        main.removeCallbacks(tick)
    }

    private companion object {
        const val TAG = "Yomikura.ready"
        const val POLL_MS = 2_000L
        const val GRACE_MS = 5_000L
        const val START_MS = 20_000L
        const val MAX_RESTARTS = 2
    }
}
