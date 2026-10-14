package com.tonbo2339.yomikura

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * サイトの公式のログアウトを、裏の WebView で行う (アプリの「ログアウト」を押したときだけ呼ぶ)。
 *
 * 手順はサイトごとの表 (PLANS) で決める。
 *  - Go(url): そのページを開く (読み込みが終わるまで待つ)
 *  - Click: 確認ボタン (フォームの送信) を 1 回押す。ボタンが無ければ何もしない。押したら読み込みを待つ
 *  - Ends(part): いまのページの URL に part が含まれれば成功 (ログイン画面に戻された、など)
 *  - Gone(selector): そのページに selector の要素が無ければ成功 (ログアウトしたので、ログアウトのボタンが消えた、など)
 * 確認画面 (Cloudflare) が出たら、通過させずに止める。全体で 45 秒を超えたら打ち切る。
 * 失敗したら done(false, …) を返す。呼び出し側は、サイトのページを画面で開いて、利用者に押してもらう。
 * 同じサイトのログアウトは、動いている間と、終わってから COOLDOWN_MS の間は始めない (続けて押しても、裏の WebView を重ねて動かさない)。
 */
object OfficialLogout {
    private sealed class Step {
        data class Go(val url: String) : Step()
        object Click : Step()
        data class Ends(val part: String) : Step()
        data class Gone(val selector: String) : Step()
    }

    private const val LOGOUT_BUTTON = "form[action*=\"logout\"] input[type=submit], form[action*=\"logout\"] button[type=submit], form[action*=\"logout\"] button"

    // サイトごとの手順。ハーメルン・カクヨムは、ログイン中の印 (Cookie) を消すのはアプリ側で行う
    private val PLANS: Map<String, List<Step>> = mapOf(
        // ログアウトの確認ページで送信 → ログイン中にしか開けないページを開くと、ログイン画面に戻される
        "narou" to listOf(
            Step.Go("https://syosetu.com/login/logout/"), Step.Click,
            Step.Go("https://syosetu.com/user/top/"), Step.Ends("/login/input/"),
        ),
        // ログアウトのリンクを開くとログアウトになる。マイページを開くと、ログイン画面 (login_entry) に戻される
        "hameln" to listOf(
            Step.Go("https://syosetu.org/?mode=logout"),
            Step.Go("https://syosetu.org/?mode=login"), Step.Ends("login_entry"),
        ),
        // トップページのログアウトのボタンを押すと、ログアウトのボタンが消える
        "kakuyomu" to listOf(
            Step.Go("https://kakuyomu.jp/"), Step.Click,
            Step.Go("https://kakuyomu.jp/"), Step.Gone(LOGOUT_BUTTON),
        ),
    )

    /** 終わってから次のログアウトを受け付けるまでの時間 */
    private const val COOLDOWN_MS = 30_000L
    /** サイト → 次のログアウトを受け付ける時刻 (elapsedRealtime)。動いている間は Long.MAX_VALUE */
    private val busyUntil = HashMap<String, Long>()

    /** 次のログアウトを受け付けるまでの秒数。0 = すぐできる、-1 = いま動いている */
    fun waitSeconds(site: String): Int {
        val until = synchronized(busyUntil) { busyUntil[site] } ?: return 0
        if (until == Long.MAX_VALUE) return -1
        val left = until - SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /** サイトの公式のページ (自動で失敗したときに、利用者に押してもらうため)。対応していないサイトは null */
    fun pageOf(site: String): String? = (PLANS[site]?.firstOrNull() as? Step.Go)?.url

    /**
     * ログアウトを始める。動いている途中か、終わってすぐ (waitSeconds が 0 でない) なら始めずに false を返す (done は呼ばない)。
     * どのスレッドから呼んでもよい (受け付けの判定は、呼んだその場で行う)。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun run(ctx: Context, site: String, done: (Boolean, String) -> Unit): Boolean {
        val plan = PLANS[site]
        if (plan == null) { done(false, "このサイトは自動では出せません"); return true }
        synchronized(busyUntil) {
            if (waitSeconds(site) != 0) return false
            busyUntil[site] = Long.MAX_VALUE
        }
        val main = Handler(Looper.getMainLooper())
        main.post {
            val wv = WebView(ctx.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            var index = 0
            var waitingForLoad = false // 読み込み (開いた・送信した) の完了を待っている
            var finished = false
            lateinit var timeout: Runnable
            fun finish(ok: Boolean, msg: String) {
                if (finished) return
                finished = true
                main.removeCallbacks(timeout)
                wv.stopLoading()
                wv.destroy()
                synchronized(busyUntil) { busyUntil[site] = SystemClock.elapsedRealtime() + COOLDOWN_MS }
                done(ok, msg)
            }
            // 次の手順へ進む。手順が終わったら、成功か失敗かを返す
            fun advance(view: WebView) {
                if (finished) return
                if (index >= plan.size) return finish(false, "手順が終わりませんでした")
                when (val step = plan[index]) {
                    is Step.Go -> { index++; waitingForLoad = true; view.loadUrl(step.url) }
                    is Step.Click -> view.evaluateJavascript(clickJs()) { r ->
                        index++
                        if (r == "\"clicked\"") { waitingForLoad = true } else advance(view) // 押すボタンが無いときは次へ
                    }
                    is Step.Ends -> {
                        val ok = view.url.orEmpty().contains(step.part)
                        finish(ok, if (ok) "サイトからもログアウトしました" else "サイト側のログインが残っています")
                    }
                    is Step.Gone -> view.evaluateJavascript(
                        "(function(){return document.querySelector(${JSONObject.quote(step.selector)})?'present':'gone';})()",
                    ) { r ->
                        val ok = r == "\"gone\""
                        finish(ok, if (ok) "サイトからもログアウトしました" else "サイト側のログインが残っています")
                    }
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    main.post { finish(false, "ブラウザの処理が止まりました") }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (finished) return
                    val title = view.title.orEmpty()
                    // 確認画面 (Cloudflare) は通過させず、止める
                    if (title.contains("Just a moment") || title.contains("しばらくお待ちください")) {
                        return finish(false, "確認画面が出たため止めました")
                    }
                    if (!waitingForLoad) return
                    waitingForLoad = false
                    advance(view)
                }
            }
            timeout = Runnable { finish(false, "時間内に終わりませんでした") }
            main.postDelayed(timeout, 45_000)
            advance(wv)
        }
        return true
    }

    // フォームの送信ボタンを 1 回だけ押す。押せたら "clicked"、無ければ "none"
    private fun clickJs(): String =
        "(function(){var b=document.querySelector(${JSONObject.quote(LOGOUT_BUTTON)});" +
            "if(b){b.click();return 'clicked';}return 'none';})()"
}
