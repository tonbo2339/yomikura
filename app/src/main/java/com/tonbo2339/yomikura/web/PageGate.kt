package com.tonbo2339.yomikura

/**
 * いま WebView に出ているページの信頼の状態 (TrustGate)。JS のスレッドから見るので、ページの開始時に控えておく。
 * アプリ内ページ (app) は端末の操作や保存データの窓口を使える。
 * 対応サイトの https かアプリ内ページ (trusted) のときだけ、保存データ (NHStore.get / set) とオフライン保存の書き込みを受け付ける
 * (サイトのリンクから別のサイトへ移ったとき、そのサイトのスクリプトにリストや閲覧履歴を読ませないため)。
 */
class PageGate {
    @Volatile var page = TrustGate.Page.NONE

    /** いまのページの URL (set で控える)。窓口が「このページにだけ渡す」ものを決めるのに使う (認証アプリのコード = TotpPages)。信用しないページへ移る間は空 */
    @Volatile var url = ""
        private set

    /** この画面でページの読み込みが一度でも始まったか (onPageStarted)。まだなら、最初のページの通信のときに page を決める (NovelClient.shouldInterceptRequest) */
    @Volatile var started = false

    val app: Boolean get() = page.app
    val trusted: Boolean get() = page.trusted
    /** 保存キューの画面 (ここでは上部の保存の帯を出さない) */
    val queue: Boolean get() = page.queue
    val settings: Boolean get() = page.settings
    /** オフライン保存の読書画面 (offline.html?ep=) */
    val reader: Boolean get() = page.reader

    /**
     * 信用しないページへ移るために閉じてから、そのページが始まる (onPageStarted) か移動をやめる (set) までの間か。
     * この間は、前のページの遅れて届いた知らせ (onPageFinished) で窓口を開け直さない (新しいページの JS に、前のページの扱いで窓口を使わせないため)
     */
    @Volatile var closedForNavigation = false
        private set

    /** url のページの扱いに決める。 */
    fun set(url: String?) {
        page = TrustGate.classify(url)
        this.url = url.orEmpty()
        closedForNavigation = false
    }

    /**
     * 主フレームの URL だけを更新する (信頼の扱い page は変えない)。ページ単位のものを渡す窓口 (認証アプリのコード = TotpPages) が、
     * 信頼するほかのサイトへ移る途中・転送・履歴の書き換えの間も、前のページの URL のまま渡さないため。信用しないページへ移る間 (closedForNavigation) は空のまま。
     */
    fun navigating(url: String?) {
        if (!closedForNavigation) this.url = url.orEmpty()
    }

    /** 窓口を閉じる (信用しないページへ移るとき)。 */
    fun close() {
        page = TrustGate.Page.NONE
        url = ""
        closedForNavigation = true
    }
}
