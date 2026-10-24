package com.tonbo2339.yomikura

import android.webkit.JavascriptInterface

/**
 * JS から使う窓口 (NHTotp)。なろうのログインで、認証アプリのコードを自動で入れるのに使う。
 * 秘密鍵そのものは JS に返さない (返すのは「登録済みか」と、いまのコードだけ)。
 * JS からは名前と引数の数で呼ばれるので、メソッドの名前と形は変えない。
 *
 * addJavascriptInterface はページの iframe (広告・解析など) からも見えるので、サイトのページからは、ページ本体だけが持つ合言葉 (NHData.token) を添えた呼び出しだけ受け付ける。
 * そのうえで、渡すものはページの URL でも絞る (いまのページの URL は PageGate.url = 主フレームのもの):
 *  - [has]: アプリ内ページ (合言葉なし) か、なろうのページ (登録済みかだけ)
 *  - [code]: なろうの 2 段階認証の入力画面のときだけコードを返す ([TotpPages.isLoginInput])
 *  - [save]: アプリ内ページか、なろうの認証アプリの設定画面 ([TotpPages.canRegisterFrom])
 *  - [clear]: アプリ内ページだけ
 */
class TotpBridge(host: BridgeHost) : GuardedBridge(host) {
    private val vault by lazy { TotpVault(ctx) }

    @JavascriptInterface
    fun has(t: String?): Boolean = (host.appPageNow || (trusted(t) && TotpPages.isNarou(host.pageUrlNow))) && vault.has()

    @JavascriptInterface
    fun code(t: String?): String = if (trusted(t) && TotpPages.isLoginInput(host.pageUrlNow)) vault.code().orEmpty() else ""

    @JavascriptInterface
    fun save(key: String?, t: String?): Boolean =
        (host.appPageNow || trusted(t)) && TotpPages.canRegisterFrom(host.pageUrlNow, host.appPageNow) && vault.save(key)

    @JavascriptInterface
    fun clear(): Boolean = forApp(false) { vault.clear(); true }
}
