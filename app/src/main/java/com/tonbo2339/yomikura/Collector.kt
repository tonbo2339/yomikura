package com.tonbo2339.yomikura

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * 画面に出さない WebView で、更新の巡回 (ui.js の #nh-collect-bg / #nh-collect-fg) を動かす。
 * 裏での確認 (BgCheckJob) と、アプリの「更新」ボタン・起動時の自動確認 (MainActivity) で使う。
 * 巡回が終わると ui.js が NHStore.bgDone(collectLog) を呼び、onDone(結果の JSON) が呼ばれる。
 * ページが進まなくなったら (ログイン画面に飛ばされた等) onDone(null) で打ち切る。
 * Cookie は CookieManager でアプリの画面と共通なので、画面でログインしていれば巡回でも使える。
 * メインスレッドで作り、onDone もメインスレッドで呼ばれる。
 */
class Collector(
    private val ctx: Context,
    blocker: HostFilter,
    url: String,
    /** 何サイト目 / 全何サイトか (ui.js が NHStore.progress で知らせる。リスト画面の「1 / 4」) */
    private val onProgress: (Int, Int) -> Unit = { _, _ -> },
    private val onDone: (String?) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private val stall = Runnable { Diag.w(TAG, "stalled at ${web?.url}"); finish(null) }

    val running get() = web != null

    init {
        val js = NHData.tokenJs + AppAssets.siteScripts(ctx)
        // 注意: この apply の中で handler と書くと WebView 自身の (画面に出していないので null の) Handler になる。mainHandler を使う
        web = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Store(), "NHStore")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
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

                override fun onPageFinished(view: WebView, url: String?) {
                    Log.d(TAG, "page $url")
                    val u = android.net.Uri.parse(url.orEmpty())
                    if (!allowedPage(u.scheme, u.host)) { mainHandler.post { if (web === view) finish(null) }; return }
                    mainHandler.removeCallbacks(stall)
                    mainHandler.postDelayed(stall, STALL_MS)
                    try { view.evaluateJavascript(js, null) } catch (e: Exception) { Diag.e(TAG, "inject", e); mainHandler.post { finish(null) } }
                }
            }
            loadUrl(url)
        }
        mainHandler.postDelayed(stall, STALL_MS)
    }

    /** 途中でやめる (onDone は呼ばない)。 */
    fun cancel() {
        mainHandler.removeCallbacks(stall)
        web?.apply { stopLoading(); destroy() }
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
            mainHandler.post { finish(log) }
        }

        @JavascriptInterface
        fun progress(i: String, n: String) {
            val a = i.toIntOrNull() ?: return
            val b = n.toIntOrNull() ?: return
            mainHandler.post { onProgress(a, b) }
        }
    }

    companion object {
        /** 巡回してよいページか (対応サイトの https のページだけ) */
        private fun allowedPage(scheme: String?, host: String?) =
            scheme == "https" && SiteUrls.isSupportedHost(host.orEmpty())

        private const val TAG = "Yomikura.collect"
        private const val STALL_MS = 90_000L
    }
}
