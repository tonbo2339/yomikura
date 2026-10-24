package com.tonbo2339.yomikura

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * 画面の WebView の WebViewClient: 通信の振り分け (アプリ内ページ・NSFW モード・止める通信の判定 HostFilter)、移動の可否、エラー画面、ページの開始 / 終了で窓口 (PageGate) と注入 JS を切り替える。
 * 画面を動かす部分 (バー・読み上げ・再起動など) は [Host] を通して頼む。
 */
class NovelClient(
    private val ctx: Context,
    private val gate: PageGate,
    private val cfg: AppSettings,
    private val hostFilter: HostFilter,
    private val offline: OfflineStore,
    /** 対応サイトのページに注入する JS (AppAssets.siteScripts) */
    private val injectedJs: String,
    private val events: Host,
) : WebViewClient() {

    interface Host {
        /** NSFW モード中に R18 へのリンクを押された (遷移はしない。下に小さく知らせる) */
        fun onR18LinkBlocked()
        /** 新しいページが始まった (ページ側の状態を直す: ページ内検索・読み上げを閉じ、バーを出す) */
        fun onPageStarted()
        /** ページの種類 (アプリ内ページか・保存キューか・設定か) が変わった: 帯とボタンを直す */
        fun onPageKindChanged()
        /** 読み込み直さない移動 (カクヨムの話から話へ) でパスが変わった */
        fun onPathChanged()
        /** WebView の表示用プロセスが落ちた。lastUrl = 落ちたときに開いていたページ */
        fun onRenderProcessGone(lastUrl: String?)
        /** ページの読み込みが終わった (最後に開いたページの控え・ログインの写し) */
        fun onPageFinished(url: String?)
    }

    /** 読み込み直さない移動 (カクヨムの話から話へ) では onPageStarted が呼ばれないので、パスが変わったら知らせる */
    private var historyPath: String? = null

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val host = request.url.host
        val main = request.isForMainFrame
        // 起動して最初のページは、onPageStarted (画面のスレッド) より先にページの JS が動くことがある (インストール直後など、画面のスレッドが混んでいるとき)。
        // そのままだと窓口が「信頼しないページ」として空を返し、リストが空で描かれる。まだ前のページが無いうちだけ、最初のページの URL で先に決める
        if (!gate.started && main) gate.set(request.url.toString())
        // 信用しないページへ移るときは、読み込みの前 (転送の 1 回ごとにも呼ばれる) に、ここで窓口を閉じる。
        // onPageStarted は画面のスレッドに遅れて届くので、それまでの間に新しいページの JS が、前のページ (アプリ内ページなど) の扱いで窓口を使えてしまうため。
        // 開くのはここではしない (開くのは onPageStarted。読み込まれなかった移動で、窓口が開いたままにならないように)
        if (gate.started && main) gate.navigating(request.url.toString()) // 信頼するほかのサイトへ移るときも、URL は先に新しいページのものにする (前のページの URL のまま窓口が答えないため)
        if (gate.started && main && !TrustGate.classify(request.url.toString()).trusted) gate.close()
        // アプリ内ページ (https://yomikura.local/xxx) は assets/web から、/js/xxx は assets/js から返す
        if (host == TrustGate.APP_HOST) {
            // アプリ内ページは GET だけ。ほかのサイトのフォームの送信 (POST) は shouldOverrideUrlLoading を通らないので、ここで断る
            // (アプリ内ページへは、アプリ内ページか対応サイトのページからだけ移れる、の決まりをすり抜けないように)
            if (main && request.method != "GET") return AppAssets.forbidden()
            val path = request.url.path.orEmpty()
            // 保存した挿絵 (読む画面が /offline-img/<サイト>/<ID>/<名前> で指す)。アプリ内ページからだけ
            // (サイトのページが <img> で保存した作品の有無を探れないようにする)
            if (path.startsWith("/offline-img/")) {
                return if (gate.app) AppAssets.offlineImage(offline, path.removePrefix("/offline-img/")) else AppAssets.forbidden()
            }
            return AppAssets.appAsset(ctx, path.trimStart('/').ifEmpty { "list.html" })
        }
        // NSFW モード: R18 のサイトへは一切アクセスしない (ページも画像も通信しない)
        if (cfg.nsfwOn() && SiteUrls.isR18Url(request.url.toString())) {
            Log.d(TAG, "nsfw blocked $host")
            return if (main) AppAssets.nsfwBlockedPage() else AppAssets.forbidden()
        }
        if (hostFilter.shouldBlock(host)) {
            Log.d(TAG, "blocked $host")
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }
        return null
    }

    // intent:// などの外部スキームは無視する
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme
        if (scheme != "http" && scheme != "https") return cancelled(view, request)
        // アプリ内ページ (端末の操作の窓口を持つ) へは、アプリ内ページか対応サイトのページからだけ移れる (ほかのサイトのリンクから開かせない)
        if (request.url.host == TrustGate.APP_HOST && !gate.trusted) return cancelled(view, request)
        // NSFW モード: R18 へのリンクは遷移せず、今のページに留まって下に小さく知らせる (専用の画面は出さない)
        if (cfg.nsfwOn() && SiteUrls.isR18Url(request.url.toString())) {
            events.onR18LinkBlocked()
            return cancelled(view, request)
        }
        return false
    }

    /**
     * 移動をやめた (今のページに留まる)。shouldInterceptRequest で、信用しない移動先のために先に閉じた窓口を、
     * 今のページの扱いに戻す (戻さないと、今のページで読書位置などが書けなくなる)。true を返す
     */
    private fun cancelled(view: WebView, request: WebResourceRequest): Boolean {
        if (request.isForMainFrame) gate.set(view.url)
        return true
    }

    // 読み込みに失敗したとき (電波が無い・タイムアウトなど): 英語のエラー画面の代わりに、アプリの画面を出す
    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame || request.url.host == TrustGate.APP_HOST) return
        val why = error.description?.toString().orEmpty()
        // 読み込み中に別のページへ移ったとき・ダウンロードなど (失敗ではない)。
        // 窓口はここでは戻さない (次の移動が先に窓口を閉じていたら、開け直してしまうため。ダウンロードは setDownloadListener が戻す)
        if ("ERR_ABORTED" in why) return
        view.loadUrl("https://${TrustGate.APP_HOST}/error.html?u=${Uri.encode(request.url.toString())}&m=${Uri.encode(why)}")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Diag.w(TAG, "render process gone (crashed=${detail.didCrash()})")
        events.onRenderProcessGone(view.url)
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        Log.d(TAG, "page $url")
        gate.set(url)
        gate.started = true
        events.onPageStarted()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        gate.navigating(url) // 履歴の書き換え (pushState) で URL が変わるとき
        val path = url?.let { Uri.parse(it).let { u -> "${u.host}${u.path}" } }
        if (path != historyPath) {
            historyPath = path
            events.onPathChanged()
        }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        // 合言葉と画面の部品は、対応サイトの https のページにだけ入れる (アプリ内ページは core/ と app.js を自分で読み込むので入れない)。
        // サーバー側の転送で別のサイトに着いていることもあるので、終わったときの URL でもう一度確かめる
        val page = TrustGate.classify(url)
        // 信用しないページへの移動のために閉じたあとなら、開け直さない (これは前のページの遅れて届いた知らせかもしれない。PageGate.closedForNavigation)
        if (page != gate.page && !gate.closedForNavigation) {
            gate.page = page
            events.onPageKindChanged()
        }
        gate.navigating(url) // 転送の先の URL で揃える (page と同じページを指す)
        if (page.injectable) view.evaluateJavascript(injectedJs, null)
        events.onPageFinished(url)
    }

    private companion object { const val TAG = "Yomikura" }
}
