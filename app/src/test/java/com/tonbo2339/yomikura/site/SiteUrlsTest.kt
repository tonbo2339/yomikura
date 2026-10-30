package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteUrlsTest {
    @Test fun 描画前の差し込みの対象に対応サイトのホストが漏れていない() {
        val hosts = listOf("syosetu.com", "ncode.syosetu.com", "syosetu.org", "kakuyomu.jp") + SiteUrls.R18_HOSTS
        hosts.forEach { h ->
            assertTrue("$h が INJECT_ORIGINS に入っていません", SiteUrls.INJECT_ORIGINS.any { SiteUrls.originMatches(it, h) })
        }
        // 対応サイトでないホストには当てはまらない
        assertFalse(SiteUrls.INJECT_ORIGINS.any { SiteUrls.originMatches(it, "example.com") })
        assertFalse(SiteUrls.INJECT_ORIGINS.any { SiteUrls.originMatches(it, "evilsyosetu.com") })
    }

    @Test
    fun workUrls() {
        assertTrue(SiteUrls.isWorkUrl("https://ncode.syosetu.com/n1234ab/12/"))
        assertTrue(SiteUrls.isWorkUrl("https://novel18.syosetu.com/n1234ab"))
        assertTrue(SiteUrls.isWorkUrl("https://syosetu.org/novel/12345/3.html"))
        assertTrue(SiteUrls.isWorkUrl("https://h.syosetu.org/novel/12345/3.html")) // ハーメルンの R-18
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

    // java.net.URI で読めない URL (WebView は `|` `{` `^` などをそのまま開く) でも、ホストを読む。NSFW モードの R18 の判定がすり抜けないこと
    @Test
    fun hostsOfUrlsThatJavaUriCannotRead() {
        assertEquals("noc.syosetu.com", SiteUrls.hostOf("https://noc.syosetu.com/top/top/?a=|"))
        assertTrue(SiteUrls.isR18Url("https://noc.syosetu.com/top/top/?a=|"))
        assertTrue(SiteUrls.isR18Url("https://novel18.syosetu.com/n1234ab/{x}"))
        assertTrue(SiteUrls.isR18Url("https://syosetu.com/favnovelmain18/list/?q=^"))
        assertTrue(SiteUrls.isR18Url("https://NOC.syosetu.com./rank/top/")) // 大文字・末尾の点
        assertEquals("evil.example", SiteUrls.hostOf("https://evil.example\\@syosetu.com/|")) // \ は / と同じ (ホストは前のほう)
        assertEquals("/novel/1/", SiteUrls.pathOf("https://h.syosetu.org/novel/1/?a=|#b"))
        assertEquals("", SiteUrls.hostOf("not a url"))
        assertEquals("", SiteUrls.hostOf(null))
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

    @Test
    // WebView で開く前の共通の確認: https だけ通し、javascript: などや、空白・制御文字を含む URL は落とす
    fun safeNavUrl() {
        assertEquals("https://ncode.syosetu.com/n1234ab/1/", SiteUrls.safeNavUrl("https://ncode.syosetu.com/n1234ab/1/"))
        assertEquals("https://yomikura.local/list.html#history", SiteUrls.safeNavUrl("https://yomikura.local/list.html#history"))
        assertEquals("HTTPS://kakuyomu.jp/works/1", SiteUrls.safeNavUrl("HTTPS://kakuyomu.jp/works/1"))
        assertEquals("https://syosetu.org/?mode=search&word=a|b", SiteUrls.safeNavUrl("https://syosetu.org/?mode=search&word=a|b"))
        assertNull(SiteUrls.safeNavUrl("javascript://yomou.syosetu.com/%0Aalert(1)"))
        assertNull(SiteUrls.safeNavUrl("javascript:alert(1)"))
        assertNull(SiteUrls.safeNavUrl("JavaScript:alert(1)"))
        assertNull(SiteUrls.safeNavUrl("http://syosetu.org/"))
        assertNull(SiteUrls.safeNavUrl("data:text/html,<script>1</script>"))
        assertNull(SiteUrls.safeNavUrl("file:///sdcard/a.html"))
        assertNull(SiteUrls.safeNavUrl("intent://x#Intent;end"))
        assertNull(SiteUrls.safeNavUrl("https:///path"))
        assertNull(SiteUrls.safeNavUrl("https://syosetu.org/\na"))
        assertNull(SiteUrls.safeNavUrl(" https://syosetu.org/"))
        assertNull(SiteUrls.safeNavUrl(""))
        assertNull(SiteUrls.safeNavUrl(null))
    }
}
