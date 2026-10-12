package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteUrlsTest {
    @Test
    fun workUrls() {
        assertTrue(SiteUrls.isWorkUrl("https://ncode.syosetu.com/n1234ab/12/"))
        assertTrue(SiteUrls.isWorkUrl("https://novel18.syosetu.com/n1234ab"))
        assertTrue(SiteUrls.isWorkUrl("https://syosetu.org/novel/12345/3.html"))
        assertTrue(SiteUrls.isWorkUrl("https://kakuyomu.jp/works/1177354054/episodes/1"))
        assertFalse(SiteUrls.isWorkUrl("https://yomou.syosetu.com/rank/top/"))
        assertFalse(SiteUrls.isWorkUrl("https://kakuyomu.jp/rankings/all/daily"))
        assertFalse(SiteUrls.isWorkUrl(null))
        assertFalse(SiteUrls.isWorkUrl("not a url"))
    }

    @Test
    fun siteKeys() {
        assertEquals("narou", SiteUrls.siteKeyOf("https://yomou.syosetu.com/"))
        assertEquals("noc", SiteUrls.siteKeyOf("https://noc.syosetu.com/rank/top/"))
        assertEquals("hameln", SiteUrls.siteKeyOf("https://syosetu.org/"))
        assertNull(SiteUrls.siteKeyOf("https://novel18.syosetu.com/n1234ab/"))
        assertNull(SiteUrls.siteKeyOf("https://evilsyosetu.com/")) // 末尾が似ているだけのホストは対象外
    }

    @Test
    fun rankingPages() {
        assertTrue(SiteUrls.isRankingPage("narou", "https://yomou.syosetu.com/rank/top/"))
        assertTrue(SiteUrls.isRankingPage("hameln", "https://syosetu.org/?mode=rank_all"))
        assertFalse(SiteUrls.isRankingPage("hameln", "https://syosetu.org/?mode=login"))
        assertTrue(SiteUrls.isRankingPage("kakuyomu", "https://kakuyomu.jp/rankings/all/daily"))
        assertFalse(SiteUrls.isRankingPage("unknown", "https://kakuyomu.jp/rankings/all/daily"))
    }

    @Test
    fun r18Urls() {
        // NSFW モードで通信を止める R18 のサイト
        assertTrue(SiteUrls.isR18Url("https://novel18.syosetu.com/n6255kw/"))
        assertTrue(SiteUrls.isR18Url("https://noc.syosetu.com/rank/list/type/daily_total/"))
        assertTrue(SiteUrls.isR18Url("https://mnlt.syosetu.com/"))
        assertTrue(SiteUrls.isR18Url("https://mid.syosetu.com/"))
        assertTrue(SiteUrls.isR18Url("https://h.syosetu.org/?mode=ss_detail&nid=427872"))
        assertTrue(SiteUrls.isR18Url("https://syosetu.com/favnovelmain18/list/?nowcategory=1"))
        // 通常のサイトは対象外 (ハーメルンの R-15 もここ)
        assertFalse(SiteUrls.isR18Url("https://ncode.syosetu.com/n2267be/"))
        assertFalse(SiteUrls.isR18Url("https://syosetu.com/favnovelmain/list/"))
        assertFalse(SiteUrls.isR18Url("https://syosetu.org/novel/429422/"))
        assertFalse(SiteUrls.isR18Url("https://kakuyomu.jp/works/1"))
        assertFalse(SiteUrls.isR18Url(null))
    }

    @Test
    fun r18WorkOfUrl() {
        // NSFW モード中に外から来た R18 の作品は、サイトへアクセスせず URL だけでリストに入れる
        assertEquals(SiteUrls.R18Work("narou18", "n6255kw", "https://novel18.syosetu.com/n6255kw/", null), SiteUrls.r18WorkOf("https://novel18.syosetu.com/n6255kw/12/"))
        assertEquals("hameln", SiteUrls.r18WorkOf("https://h.syosetu.org/novel/427872/3.html")?.site)
        assertEquals("https://syosetu.org/novel/427872/", SiteUrls.r18WorkOf("https://h.syosetu.org/novel/427872/")?.url)
        assertNull(SiteUrls.r18WorkOf("https://noc.syosetu.com/rank/list/type/daily_total/")) // ランキングは作品ではない
        assertNull(SiteUrls.r18WorkOf("https://ncode.syosetu.com/n2267be/")) // R18 でない
    }

    @Test
    fun sharedText() {
        assertEquals("https://kakuyomu.jp/works/1", SiteUrls.firstUrlIn("面白い「作品」https://kakuyomu.jp/works/1。ぜひ"))
        assertNull(SiteUrls.firstUrlIn("URL なし"))
        assertEquals("https://syosetu.org/novel/1/", SiteUrls.toSupportedLink("http://syosetu.org/novel/1/"))
        assertNull(SiteUrls.toSupportedLink("https://example.com/"))
    }

    @Test fun 外から来たリンクは_http_https_だけ受け付ける() {
        assertNull(SiteUrls.toSupportedLink("javascript://syosetu.com/%0Aalert(1)"))
        assertNull(SiteUrls.toSupportedLink("JavaScript://syosetu.com/%0Aalert(1)"))
        assertNull(SiteUrls.toSupportedLink("file://syosetu.com/etc/passwd"))
        assertNull(SiteUrls.toSupportedLink("content://syosetu.com/x"))
        assertNull(SiteUrls.toSupportedLink("intent://syosetu.com/#Intent;end"))
        assertNull(SiteUrls.toSupportedLink("syosetu.com/novel/1/"))
        assertEquals("https://syosetu.org/novel/1/", SiteUrls.toSupportedLink("HTTP://syosetu.org/novel/1/"))
        assertEquals("https://syosetu.org/novel/1/", SiteUrls.toSupportedLink("HTTPS://syosetu.org/novel/1/"))
        assertEquals("https://ncode.syosetu.com/n1234ab/", SiteUrls.toSupportedLink("https://ncode.syosetu.com/n1234ab/"))
    }

    @Test fun スキームとクエリの確認() {
        assertEquals("https", SiteUrls.schemeOf("HTTPS://a.example/"))
        assertEquals("", SiteUrls.schemeOf("::bad"))
        assertTrue(SiteUrls.hasQueryParam("https://yomikura.local/offline.html?ep=3", "ep"))
        assertTrue(SiteUrls.hasQueryParam("https://yomikura.local/offline.html?a=1&ep=", "ep"))
        assertFalse(SiteUrls.hasQueryParam("https://yomikura.local/offline.html?site=1", "ep"))
        assertFalse(SiteUrls.hasQueryParam(null, "ep"))
    }
}
