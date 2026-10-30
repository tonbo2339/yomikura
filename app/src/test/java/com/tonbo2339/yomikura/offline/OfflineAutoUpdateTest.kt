package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineAutoUpdateTest {
    private val log = """{"narou":{"n":2,"keys":["narou:n1234ab","narou:n9999zz"]},"narou18":{"keys":["narou18:n5555xx"]},"hameln":{"keys":["hameln:100","bad key"]},"kakuyomu":{"err":"login"}}"""

    @Test
    // 保存してある作品だけを選ぶ (順序は問わない。保存の無い作品・形の違うキー・エラーのサイトは無視する)
    fun picksOnlySavedWorks() {
        val saved = setOf("narou" to "n1234ab", "hameln" to "100", "narou18" to "n5555xx")
        val got = OfflineAutoUpdate.candidates(log, { s, id -> (s to id) in saved }, nsfwOn = false)
        assertEquals(setOf("narou" to "n1234ab", "narou18" to "n5555xx", "hameln" to "100"), got.toSet())
    }

    @Test
    // NSFW モード中は R18 の作品を入れない
    fun skipsR18WhileNsfwOn() {
        val got = OfflineAutoUpdate.candidates(log, { _, _ -> true }, nsfwOn = true)
        assertEquals(setOf("narou" to "n1234ab", "narou" to "n9999zz", "hameln" to "100"), got.toSet())
    }

    @Test
    // 読めない結果・空は、何も選ばない
    fun badLogIsEmpty() {
        assertEquals(emptyList<Pair<String, String>>(), OfflineAutoUpdate.candidates("not json", { _, _ -> true }, false))
        assertEquals(emptyList<Pair<String, String>>(), OfflineAutoUpdate.candidates("{}", { _, _ -> true }, false))
    }
}
