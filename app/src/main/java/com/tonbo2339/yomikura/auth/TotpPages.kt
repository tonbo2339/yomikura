package com.tonbo2339.yomikura

/**
 * 認証アプリのコードを JS に渡してよいページの判定 (URL だけから決める。Android に依存しない)。
 * なろう (syosetu.com) の 2 段階認証の入力画面のときだけ。ほかのページやサイトの JS には、鍵があってもコードを渡さない。
 * ページの作りが変わったら、ここの [LOGIN_INPUT] だけを直す。
 */
object TotpPages {
    /** ログインの途中の、認証アプリのコードを入れる画面 (/user2stepauth/totpinput/authtoken/…) */
    private const val LOGIN_INPUT = "/user2stepauth/totpinput/"

    /** なろう (syosetu.com) の https のページか (サブドメインは含めない) */
    fun isNarou(url: String?) = SiteUrls.schemeOf(url) == "https" && SiteUrls.hostOf(url).lowercase() == "syosetu.com"

    fun isLoginInput(url: String?): Boolean = isNarou(url) && SiteUrls.pathOf(url).startsWith(LOGIN_INPUT)

    /** 鍵を登録してよいページ: 設定画面 (アプリ内) か、なろうの認証アプリの設定画面 (パスの終わりが totpedit のページ。セットアップキーはここに出る) */
    fun canRegisterFrom(url: String?, appPage: Boolean): Boolean =
        appPage || (isNarou(url) && SiteUrls.pathOf(url).contains("totpedit/"))
}
