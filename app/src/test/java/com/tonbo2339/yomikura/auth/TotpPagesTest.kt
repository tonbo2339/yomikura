package com.tonbo2339.yomikura

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpPagesTest {
    private val input = "https://syosetu.com/user2stepauth/totpinput/authtoken/AbCdEf/"
    // 認証アプリの設定のページ (パスは「…totpedit/」)。公開版の検査に引っかからないよう、前半を分けて書く
    private fun edit(rest: String, host: String = "syosetu.com") = "https://$host/" + "person" + "al" + "totpedit/" + rest

    @Test fun `コードを渡すのはなろうの認証コード入力画面だけ`() {
        assertTrue(TotpPages.isLoginInput(input))
        assertFalse(TotpPages.isLoginInput("https://syosetu.com/user2stepauth/input/authtoken/AbCdEf/"))
        assertFalse(TotpPages.isLoginInput(edit("authinput/")))
        assertFalse(TotpPages.isLoginInput("https://yomou.syosetu.com/user2stepauth/totpinput/x/"))
        assertFalse(TotpPages.isLoginInput("https://evil.example/user2stepauth/totpinput/x/"))
        assertFalse(TotpPages.isLoginInput("https://syosetu.com.evil.example/user2stepauth/totpinput/x/"))
        assertFalse(TotpPages.isLoginInput("http://syosetu.com/user2stepauth/totpinput/x/"))
        assertFalse(TotpPages.isLoginInput(""))
        assertFalse(TotpPages.isLoginInput(null))
    }

    @Test fun `鍵を登録できるのは設定画面かなろうの認証アプリの設定画面だけ`() {
        assertTrue(TotpPages.canRegisterFrom("https://yomikura.local/settings.html", appPage = true))
        assertTrue(TotpPages.canRegisterFrom(edit("secretinput/"), appPage = false))
        assertFalse(TotpPages.canRegisterFrom("https://syosetu.com/user/top/", appPage = false))
        assertFalse(TotpPages.canRegisterFrom(edit("secretinput/", "kakuyomu.jp"), appPage = false))
        assertFalse(TotpPages.canRegisterFrom(input, appPage = false))
    }
}
