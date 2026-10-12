package com.tonbo2339.yomikura

/**
 * WebView に出ているページをどこまで信用するかの判定。URL だけから決める、Android に依存しない計算 (単体テストで動かせる)。
 *
 * - app: アプリ内ページ (https://yomikura.local/…)。端末の操作や更新などの窓口を使える
 * - trusted: アプリ内ページか、対応サイトの https のページ。保存データ (NHStore.get / set) とオフライン保存の書き込みはこれのときだけ受け付け、
 *   合言葉 (NHData.token) や画面の部品の JS もこのページにだけ渡す。サイトのリンクから別のサイトへ移ったら false になる。
 *   https 以外 (http・data・javascript など) は対応サイトのホストに見えても信用しない
 */
object TrustGate {
    const val APP_HOST = "yomikura.local"

    data class Page(val app: Boolean, val trusted: Boolean, val settings: Boolean, val queue: Boolean, val reader: Boolean) {
        /** 対応サイトの https ページ (アプリ内ページを除く) か。ここにだけ合言葉と画面の部品を注入する */
        val injectable: Boolean get() = trusted && !app

        companion object {
            val NONE = Page(app = false, trusted = false, settings = false, queue = false, reader = false)
        }
    }

    fun classify(url: String?): Page {
        if (SiteUrls.schemeOf(url) != "https") return Page.NONE
        val host = SiteUrls.hostOf(url).lowercase()
        val app = host == APP_HOST
        val path = SiteUrls.pathOf(url)
        return Page(
            app = app,
            trusted = app || SiteUrls.isSupportedHost(host),
            settings = app && path == "/settings.html",
            queue = app && path == "/queue.html",
            // オフライン保存の読書画面 (offline.html?ep=)。上部バーの出し入れは、ページの JS が NHOffline.readerBar で決める
            reader = app && path == "/offline.html" && SiteUrls.hasQueryParam(url, "ep"),
        )
    }
}
