package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumeTest {
    private fun data(vararg parts: String) = JSONObject("{" + parts.joinToString(",") + "}")

    @Test
    fun opensTheEpisodeOfTheMostRecentlyReadWork() {
        val d = data(
            """"history":[{"key":"narou:a","url":"https://ncode.syosetu.com/a/1/","appAt":100},{"key":"hameln:5","url":"https://syosetu.org/novel/5/2.html","appAt":300},{"key":"narou:b","url":"https://ncode.syosetu.com/b/","appAt":200}]""",
            """"progress":{"hameln:5":{"url":"https://syosetu.org/novel/5/7.html","y":0.4,"done":false,"next":null}}""",
        )
        assertEquals("https://syosetu.org/novel/5/7.html", Resume.latestUrl(d, nsfw = false)) // 読書位置の話
    }

    @Test
    fun goesToTheNextEpisodeWhenTheLastOneWasFinished() {
        val d = data(
            """"history":[{"key":"narou:a","url":"https://ncode.syosetu.com/a/1/","appAt":100}]""",
            """"progress":{"narou:a":{"url":"https://ncode.syosetu.com/a/3/","done":true,"next":"https://ncode.syosetu.com/a/4/"}}""",
        )
        assertEquals("https://ncode.syosetu.com/a/4/", Resume.latestUrl(d, false))
        val noNext = data(
            """"history":[{"key":"narou:a","url":"https://ncode.syosetu.com/a/1/","appAt":100}]""",
            """"progress":{"narou:a":{"url":"https://ncode.syosetu.com/a/3/","done":true,"next":null}}""",
        )
        assertEquals("https://ncode.syosetu.com/a/3/", Resume.latestUrl(noNext, false)) // 次が無ければ、読んでいた話
    }

    @Test
    fun fallsBackToTheHistoryUrlWithoutProgress() {
        val d = data(""""history":[{"key":"kakuyomu:9","url":"https://kakuyomu.jp/works/9/episodes/1","appAt":5}]""")
        assertEquals("https://kakuyomu.jp/works/9/episodes/1", Resume.latestUrl(d, false))
    }

    @Test
    fun ignoresWorksNeverOpenedInTheApp() {
        val d = data(""""history":[{"key":"narou:a","url":"https://ncode.syosetu.com/a/","siteAt":900},{"key":"narou:b","url":"https://ncode.syosetu.com/b/","appAt":0}]""")
        assertNull(Resume.latestUrl(d, false)) // 公式の履歴から取り込んだだけの作品は、「アプリで最後に読んだ」ではない
        assertNull(Resume.latestUrl(JSONObject(), false))
    }

    @Test
    fun skipsR18WorksWhileNsfwModeIsOn() {
        val d = data(
            """"history":[{"key":"narou18:x","site":"narou18","url":"https://novel18.syosetu.com/x/1/","appAt":900},{"key":"hameln:5","url":"https://syosetu.org/novel/5/1.html","appAt":100},{"key":"hameln:6","url":"https://syosetu.org/novel/6/1.html","appAt":500}]""",
            """"readLater":[{"key":"hameln:6","r18":true}]""",
        )
        assertEquals("https://novel18.syosetu.com/x/1/", Resume.latestUrl(d, nsfw = false))
        assertEquals("https://syosetu.org/novel/5/1.html", Resume.latestUrl(d, nsfw = true)) // R18 (サイトの印・リストの印) を飛ばす
    }

    @Test
    fun rejectsNonHttpsOrBrokenRecords() {
        val d = data(""""history":[{"key":"a","url":"javascript:alert(1)","appAt":5}]""", """"progress":{"a":{"url":"http://insecure/","done":true,"next":"file:///x"}}""")
        assertNull(Resume.latestUrl(d, false))
    }
}
