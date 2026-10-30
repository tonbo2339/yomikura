package com.tonbo2339.yomikura

import android.webkit.CookieManager

/**
 * R18 の年齢確認 (なろう系は「年齢確認」の画面、ハーメルンは「あなたは成人ですか？」) に答えたか。
 * 答えると、どちらも Cookie に over18 が入る (2026-10-27 に実機で確認。なろう系は .syosetu.com、ハーメルンは syosetu.org)。
 * 答えていないと、R18 のサイトを裏で調べる処理 (作品の判別) は、確認の画面しか読めずに空振りする (ハーメルンは止められる)。
 * アプリは年齢確認を自動では押さない。答えていないときは、答えるよう案内して、そのサイトへは調べに行かない。
 */
object AdultGate {
    /** 年齢確認が要るサイト。cookieUrl = Cookie を調べる URL、openUrl = 案内のボタンで開く URL (開くと年齢確認の画面が出る) */
    enum class Gate(val cookieUrl: String, val openUrl: String, val message: String, val button: String) {
        NAROU(
            "https://syosetu.com/", "https://noc.syosetu.com/top/top/",
            "なろうの成人向けの作品を調べるには、年齢確認に答える必要があります",
            "開く",
        ),
        HAMELN(
            "https://syosetu.org/", "https://h.syosetu.org/",
            "ハーメルンの成人向けの作品を調べるには、年齢確認に答える必要があります",
            "開く",
        ),
    }

    /** Cookie の文字列 (名前=値; …) に、年齢確認に答えた印 (over18) があるか。Android に依存しない (単体テスト用) */
    fun answered(cookies: String?): Boolean =
        cookies.orEmpty().split(";").any { it.substringBefore("=").trim() == "over18" }

    fun answered(gate: Gate): Boolean = answered(CookieManager.getInstance().getCookie(gate.cookieUrl))
}
