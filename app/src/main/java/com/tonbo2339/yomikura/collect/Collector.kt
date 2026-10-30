package com.tonbo2339.yomikura

import android.content.Context
import android.os.Handler
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * 画面に出さない WebView で、更新の巡回 (ui.js の #nh-collect-bg / #nh-collect-fg) を動かす。
 * 裏での確認 (BgCheckJob) と、アプリの「更新」ボタン・起動時の自動確認 (CollectController) で使う。
 * 巡回が終わると ui.js が NHStore.bgDone(collectLog) を呼び、onDone(結果の JSON) が呼ばれる。
 * ページが進まなくなったら (ログイン画面に飛ばされた等) onDone(null) で打ち切る。
 * 確認画面 (Cloudflare) で止まったら onChallenge(サイト, 次のサイトの巡回ページ) で知らせる。holdable なら、この WebView は
 * そのまま待ち (画面に出して通してもらえる。[view])、次のサイトからは呼び出し側が別の Collector で続ける。holdable でなければ、次へ進む。
 * Cookie は CookieManager でアプリの画面と共通なので、画面でログインしていれば巡回でも使える。
 * メインスレッドで作り、onDone もメインスレッドで呼ばれる。
 */
class Collector(
    private val ctx: Context,
    blocker: HostFilter,
    url: String,
    /** 何サイト目 / 全何サイトか (ui.js が NHStore.progress で知らせる。リスト画面の「1 / 4」) */
    private val onProgress: (Int, Int) -> Unit = { _, _ -> },
    private val holdable: Boolean = false,
    private val onChallenge: (site: String, nextUrl: String) -> Unit = { _, _ -> },
    private val onDone: (String?) -> Unit,
) {
    private val mainHandler = MainThread.handler
    private var web: WebView? = null
    private var ready: PageReady? = null // 巡回中のページの、読み込みの見張り
    private val stall = Runnable { Diag.w(TAG, "stalled at ${web?.url}"); finish(null) }

    val running get() = web != null

    /** 確認画面で止まって待っているか (画面に出して通してもらう) */
    @Volatile var holding = false
        private set

    /** 画面に出すための WebView (終わった / 止めたあとは null)。出している間も巡回は進む */
    val view: WebView? get() = web

    init {
        val js = NHData.tokenJs + AppAssets.siteScripts(ctx)
        // 注意: この apply の中で handler と書くと WebView 自身の (画面に出していないので null の) Handler になる。mainHandler を使う
        web = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) // 確認画面 (Turnstile) は別オリジンの枠を使う
            addJavascriptInterface(Store(), "NHStore")
            val client = object : WebViewClient() {
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) =
                    NetLog.webError("collect", request, response.statusCode, response.responseHeaders)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    NetLog.page("collect", request)
                    // 対応サイトの https 以外のページは開かせない (合言葉と保存の窓口を、サイトの外へ渡さないため)
                    if (request.isForMainFrame && !allowedPage(request.url.scheme, request.url.host)) {
                        Diag.w(TAG, "off-site: ${request.url.host}")
                        mainHandler.post { if (web === view) finish(null) } // WebView の別のスレッドから呼ばれるので、画面のスレッドで片付ける
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return if (blocker.shouldBlock(request.url.host)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    !allowedPage(request.url.scheme, request.url.host)

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Diag.w(TAG, "render process gone")
                    mainHandler.post { if (web === view) finish(null) }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { ready?.started(url) }

                override fun onPageFinished(view: WebView, url: String?) {
                    Log.d(TAG, "page $url")
                    if (ready?.finished(url) == true) return // 見張り (PageReady) がもう入れた
                    inject(view, url)
                }

                /** 巡回の JS を入れる (onPageFinished か、完了の来ないときの見張りから) */
                fun inject(view: WebView, url: String?) {
                    val u = android.net.Uri.parse(url.orEmpty())
                    if (!allowedPage(u.scheme, u.host)) { mainHandler.post { if (web === view) finish(null) }; return }
                    mainHandler.removeCallbacks(stall)
                    mainHandler.postDelayed(stall, if (holding) HOLD_MS else STALL_MS) // 通してもらっている間は長く待つ
                    try { view.evaluateJavascript(js, null) } catch (e: Exception) { Diag.e(TAG, "inject", e); mainHandler.post { finish(null) } }
                }
            }
            webViewClient = client
            loadUrl(url)
            // 公開版は広告を止めないので、広告の枠が終わらず onPageFinished が来ないことがある。本文が読めたら入れる
            ready = PageReady(mainHandler, { v -> if (web === v) v.loadUrl(url) }) { v, u -> if (web === v) client.inject(v, u) }.also { it.watch(this) }
        }
        mainHandler.postDelayed(stall, STALL_MS)
        alive += this
    }

    /** 途中でやめる (onDone は呼ばない)。 */
    fun cancel() {
        alive -= this
        mainHandler.removeCallbacks(stall)
        ready?.stop()
        ready = null
        web?.apply {
            (parent as? android.view.ViewGroup)?.removeView(this) // 画面に出していたら外してから壊す
            stopLoading(); destroy()
        }
        web = null
    }

    private fun finish(log: String?) {
        if (web == null) return
        cancel()
        try { onDone(log) } catch (e: Exception) { Diag.e(TAG, "done", e) }
    }

    /** 巡回中の JS から見える保存データの窓口 (BgStore) に、巡回の知らせ (startUrl・bgDone・progress) を足したもの。 */
    private inner class Store : BgStore(ctx, { mainHandler.post { BgCheck.onDataChanged?.invoke() } }) {
        @JavascriptInterface
        fun startUrl(): String = SiteConfig.LIST_URL

        @JavascriptInterface
        fun bgDone(log: String) {
            mainHandler.post {
                OfflineAutoUpdate.onCollected(ctx, log)
                finish(log)
            }
        }

        /** この WebView が、自分のサイトだけで終わる側か (確認画面で止まって、続きを別の WebView に渡したあと) */
        @JavascriptInterface
        fun isSplit(): Boolean = holding

        /** 確認画面で止まった (ui.js)。"hold" を返したら、この WebView は待つ。そうでなければ、ui.js が次へ進む */
        @JavascriptInterface
        fun challenge(site: String, nextUrl: String): String {
            if (!SiteConfig.isCollectSite(site)) return ""
            // すでに待っている: 確認画面が読み込み直されて ui.js がもう一度知らせてきただけ。続きの巡回はもう別の WebView が始めているので、
            // 知らせ直さない (知らせ直すと、次のサイトからの Collector が二重にでき、問いかけも出直す)
            if (holding) return "hold"
            val next = nextUrl.takeIf { it.isEmpty() || nextPageOk(it) } ?: return ""
            if (holdable) {
                holding = true
                // 通してもらう時間は長めに取る (ページが読み込み直されても、待っている間は長いまま)
                mainHandler.post { mainHandler.removeCallbacks(stall); mainHandler.postDelayed(stall, HOLD_MS) }
            }
            mainHandler.post { onChallenge(site, next) }
            return if (holdable) "hold" else ""
        }

        @JavascriptInterface
        fun progress(i: String, n: String) {
            if (holding) return // 通ったあと自分のサイトだけを取る間は、続きの巡回の進み具合 (「3 / 4」など) を「1 / 4」に戻さない
            val a = i.toIntOrNull() ?: return
            val b = n.toIntOrNull() ?: return
            mainHandler.post { onProgress(a, b) }
        }
    }

    companion object {
        /** 動いている Collector (画面の確認・裏での確認の両方。メインスレッドで使う) */
        private val alive = mutableSetOf<Collector>()

        /**
         * 巡回が進んでいるか (確認画面で待っているだけのものは除く)。画面の確認と裏での確認を同時に動かさない
         * (同じサイトへ 2 つの WebView から続けてアクセスしてしまい、結果 collectLog も消し合うため)。メインスレッドから呼ぶ
         */
        fun crawling(): Boolean = alive.any { !it.holding }

        /** 確認画面で待っているもの (通してもらったあと、そのサイトを取っている途中のことがある) も含めて、動いているか。裏での確認はこれで待つ */
        fun anyAlive(): Boolean = alive.isNotEmpty()

        /** 巡回してよいページか (対応サイトの https のページだけ) */
        private fun allowedPage(scheme: String?, host: String?) =
            scheme == "https" && SiteUrls.isSupportedHost(host.orEmpty())

        private const val TAG = "Yomikura.collect"
        private const val STALL_MS = 90_000L
        private const val HOLD_MS = 10 * 60_000L

        /** 次のサイトとして渡された巡回ページか (対応サイトの https のページだけ) */
        private fun nextPageOk(url: String): Boolean {
            val u = android.net.Uri.parse(url)
            return allowedPage(u.scheme, u.host)
        }
    }
}
