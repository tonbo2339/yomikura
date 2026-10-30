package com.tonbo2339.yomikura

import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener
import java.io.ByteArrayInputStream

/**
 * 画面に出さない WebView で、ページを 1 つずつ開いて、結果 (コード・転送先・HTML) を返す。作品の判別 (HamelnRating・R18Subs) の取得に使う。
 * アプリ自身の通信 (HttpURLConnection) は、サイトの Cloudflare にボットとして止められる (403 + cf-mitigated: challenge) ので、
 * 巡回 (Collector) と同じ WebView で開く。Cookie は CookieManager で画面と共通 (年齢確認を通った Cookie が使われる)。
 * 読むのは HTML だけなので、ページの部分 (画像・広告・スクリプトなど) の通信は全部止める。アプリの閲覧履歴の記録 (NovelClient) も通らない。
 * stopHosts のホストへの移動 (転送) は、開く前に止めて、そのホスト名を [Result.redirectedTo] で返す (そのドメインの中身は読まない)。
 * メインスレッドで使う。onResult もメインスレッドで呼ばれる。使い終わったら [destroy]。
 */
class PageProbe(private val ctx: Context, private val stopHosts: Set<String> = emptySet()) {
    /**
     * status: 取れたページのコード (エラーでなければ 200。WebView は成功のコードを教えない)。開けなかった・時間切れは 0。
     * redirectedTo: stopHosts のどれかへ移ろうとしたら、そのホスト名 (止めたので、html は空)。
     */
    data class Result(val status: Int, val redirectedTo: String?, val html: String)

    private val main = MainThread.handler
    private var web: WebView? = null
    private var callback: ((Result) -> Unit)? = null
    @Volatile private var status = 200
    @Volatile private var redirectedTo: String? = null
    // 時間切れのときは読み込みを止める (遅れて届く onPageFinished・エラーが、次の作品の結果に混ざらないように)
    private val timeout = Runnable { web?.stopLoading(); finish(Result(0, null, "")) }

    fun load(url: String, onResult: (Result) -> Unit) {
        status = 200
        redirectedTo = null
        callback = onResult
        val v = web ?: create().also { web = it }
        main.postDelayed(timeout, TIMEOUT_MS)
        v.stopLoading() // 前のページの読み込みが残っていれば止める
        v.loadUrl(url)
    }

    fun destroy() {
        main.removeCallbacks(timeout)
        callback = null
        web?.apply { stopLoading(); destroy() }
        web = null
    }

    private fun finish(r: Result) {
        val cb = callback ?: return
        callback = null
        main.removeCallbacks(timeout)
        try { cb(r) } catch (e: Exception) { Diag.e(TAG, "result", e) }
    }

    private fun stopBy(host: String) {
        redirectedTo = host
        main.post { finish(Result(status, host, "")) }
    }

    private fun create(): WebView = WebView(ctx).apply {
        settings.javaScriptEnabled = true // evaluateJavascript で HTML を読むため
        settings.loadsImagesAutomatically = false
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame) return EMPTY // ページの部分は取りに行かない
                NetLog.page("probe", request)
                val host = request.url.host.orEmpty()
                if (host in stopHosts) { stopBy(host); return EMPTY }
                return null
            }

            // 転送 (サーバーの 302 など) も、ここを通る
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                if (host in stopHosts) { stopBy(host); return true }
                return false
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                NetLog.webError("probe", request, response.statusCode, response.responseHeaders)
                if (request.isForMainFrame) status = response.statusCode
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) main.post { finish(Result(0, null, "")) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (callback == null || redirectedTo != null) return
                // 転送を開く前に止められなかったときの保険: 最後の URL が stopHosts なら、読まずに「そこへ移った」と返す
                val landed = android.net.Uri.parse(url.orEmpty()).host.orEmpty()
                if (landed in stopHosts) { redirectedTo = landed; finish(Result(status, landed, "")); return }
                view.evaluateJavascript("document.documentElement.outerHTML.slice(0, $MAX_HTML)") { raw ->
                    val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                    finish(Result(status, null, html))
                }
            }
        }
    }

    companion object {
        private const val TAG = "Yomikura.probe"
        private const val TIMEOUT_MS = 25_000L
        private const val MAX_HTML = 200_000
        private val EMPTY get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
