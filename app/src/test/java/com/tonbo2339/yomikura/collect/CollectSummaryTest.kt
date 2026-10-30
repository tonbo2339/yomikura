package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectSummaryTest {
    private fun result(added: Int = 0, fresh: List<String> = emptyList(), errors: List<String> = emptyList(), challenge: List<String> = emptyList(), login: List<String> = emptyList()) =
        BgCheck.Result(added, fresh, errors, challenge, login)

    // 自動の確認は、新しい話も確認画面も無ければ何も出さない
    @Test
    fun autoCheckIsQuietWhenNothingHappened() {
        assertNull(CollectSummary.of(result(), auto = true, onAppPage = false))
        assertEquals("新しい話のある作品はありませんでした", CollectSummary.of(result(), auto = false, onAppPage = true)!!.text)
    }

    // 新しく入った作品の数を先に出す。サイトのページを見ているときは「リスト」のボタン
    @Test
    fun addedWorksComeFirstWithListButton() {
        val s = CollectSummary.of(result(added = 2, fresh = listOf("A", "B", "C")), auto = false, onAppPage = false)!!
        assertEquals("2 作品の更新をリストに追加しました", s.text)
        assertEquals(CollectSummary.Action.List, s.action)
        assertNull(CollectSummary.of(result(fresh = listOf("A")), auto = false, onAppPage = true)!!.action) // リストを見ているならボタンは要らない
    }

    // 確認画面で止まったサイトは、名前と「開く」。ログイン切れより優先する
    @Test
    fun challengeWinsOverLogin() {
        val s = CollectSummary.of(result(challenge = listOf("hameln"), login = listOf("narou"), errors = listOf("x")), auto = true, onAppPage = false)!!
        assertTrue(s.text.contains("ハーメルン は確認画面で止まりました"))
        assertEquals(CollectSummary.Action.OpenSite("hameln"), s.action)
        val login = CollectSummary.of(result(login = listOf("kakuyomu"), errors = listOf("カクヨム (ログイン)")), auto = false, onAppPage = true)!!
        assertEquals(CollectSummary.Action.Login("kakuyomu"), login.action)
        assertTrue(login.text.endsWith("\n確認できなかった: カクヨム (ログイン)"))
    }
}
