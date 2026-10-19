package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ハーメルンの小説情報のタグ行から R-18 を判別する (HamelnRating.isR18)。 */
class HamelnRatingTest {
    private fun page(tags: String) = "<table><tr><th>タグ</th><td>$tags</td></tr><tr><th>掲載開始</th><td>2026年</td></tr></table>"

    @Test fun R18のタグがあればtrue() {
        assertEquals(true, HamelnRating.isR18(page("R-18 ガールズラブ オリ主 短編集")))
    }

    @Test fun R15やタグ無しはfalse() {
        assertEquals(false, HamelnRating.isR18(page("R-15 オリ主 残酷な描写")))
        assertEquals(false, HamelnRating.isR18(page("オリ主 恋愛 R-18文字列の一部ではない")))
    }

    @Test fun タグ行が無ければ判別できない() {
        assertNull(HamelnRating.isR18("<html><body>何もない</body></html>"))
    }
}
