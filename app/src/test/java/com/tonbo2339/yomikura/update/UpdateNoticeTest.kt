package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNoticeTest {
    private val hour = 60L * 60 * 1000

    @Test
    fun 覚えた版が今の版より新しいときだけ入れられる版になる() {
        assertEquals("1.3.7", UpdateNotice.Logic.pending("1.3.7", "1.3.6"))
        assertEquals("", UpdateNotice.Logic.pending("1.3.6", "1.3.6")) // 更新したあと (同じ版)
        assertEquals("", UpdateNotice.Logic.pending("1.3.5", "1.3.6"))
        assertEquals("", UpdateNotice.Logic.pending("", "1.3.6"))
    }

    @Test
    fun 点は新しい版を見つけてまだ見せていないときだけ出る() {
        assertTrue(UpdateNotice.Logic.dotVisible(true, "1.3.7", "", "1.3.6"))
        assertFalse(UpdateNotice.Logic.dotVisible(true, "1.3.7", "1.3.7", "1.3.6")) // 見せた
        assertTrue(UpdateNotice.Logic.dotVisible(true, "1.3.8", "1.3.7", "1.3.6"))  // 次の新しい版は、また 1 回出る
        assertFalse(UpdateNotice.Logic.dotVisible(false, "1.3.7", "", "1.3.6"))     // 印をオフ
        assertFalse(UpdateNotice.Logic.dotVisible(true, "", "", "1.3.6"))
        assertFalse(UpdateNotice.Logic.dotVisible(true, "1.3.6", "", "1.3.6"))      // 更新を済ませた
    }

    @Test
    fun 確認は半日に1回まで_オフのときはしない() {
        assertTrue(UpdateNotice.Logic.due(true, 0L, 100 * hour))
        assertFalse(UpdateNotice.Logic.due(true, 100 * hour, 105 * hour))
        assertTrue(UpdateNotice.Logic.due(true, 100 * hour, 112 * hour))
        assertTrue(UpdateNotice.Logic.due(true, 100 * hour, 90 * hour)) // 時計が戻っていたら、やり直す
        assertFalse(UpdateNotice.Logic.due(false, 0L, 100 * hour))
    }
}
