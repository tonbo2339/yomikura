package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PageGate.url (主フレームの URL。認証アプリのコードを渡してよいページかの判定 = TotpPages が見る) の更新の決まり。 */
class PageGateUrlTest {
    private val input = "https://syosetu.com/user2stepauth/totpinput/authtoken/AbC/"
    private val other = "https://kakuyomu.jp/"

    @Test fun `set で信頼とURLが決まり、navigating はURLだけを新しくする`() {
        val g = PageGate()
        g.set(input)
        assertTrue(TotpPages.isLoginInput(g.url))
        g.navigating(other) // 信頼するほかのサイトへ移る途中: 前のページの URL のまま窓口が答えない
        assertEquals(other, g.url)
        assertFalse(TotpPages.isLoginInput(g.url))
        assertTrue(g.page.trusted) // 信頼の扱いは、onPageStarted の set までそのまま
    }

    @Test fun `信用しないページへ移る間は URL が空で、navigating では開け直さない`() {
        val g = PageGate()
        g.set(input)
        g.close()
        assertEquals("", g.url)
        g.navigating(input) // 閉じたあとの遅れた知らせ
        assertEquals("", g.url)
        g.set(other) // 新しいページが始まったら、また決まる
        assertEquals(other, g.url)
        assertFalse(g.closedForNavigation)
    }

    @Test fun `null の URL は空にする`() {
        val g = PageGate()
        g.set(input)
        g.navigating(null)
        assertEquals("", g.url)
        assertFalse(TotpPages.isLoginInput(g.url))
    }
}
