package com.tonbo2339.yomikura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustGateTest {
    @Test fun アプリ内ページは信用する() {
        val p = TrustGate.classify("https://yomikura.local/list.html")
        assertTrue(p.app)
        assertTrue(p.trusted)
        assertFalse(p.injectable) // アプリ内ページには合言葉と画面の部品を注入しない (自分で読み込む)
        assertFalse(p.settings || p.queue || p.reader)
    }

    @Test fun 画面ごとの印() {
        assertTrue(TrustGate.classify("https://yomikura.local/settings.html").settings)
        assertTrue(TrustGate.classify("https://yomikura.local/settings.html#top").settings)
        assertTrue(TrustGate.classify("https://yomikura.local/queue.html").queue)
        assertTrue(TrustGate.classify("https://yomikura.local/offline.html?site=narou&ep=3").reader)
        assertFalse(TrustGate.classify("https://yomikura.local/offline.html?site=narou").reader) // 作品の目次
        assertFalse(TrustGate.classify("https://yomikura.local/list.html").settings)
    }

    @Test fun 対応サイトのhttpsは信用して注入する() {
        for (u in listOf("https://ncode.syosetu.com/n1234ab/1/", "https://syosetu.org/novel/1/", "https://kakuyomu.jp/works/1", "https://noc.syosetu.com/rank/top/")) {
            val p = TrustGate.classify(u)
            assertTrue(u, p.trusted)
            assertTrue(u, p.injectable)
            assertFalse(u, p.app)
        }
    }

    @Test fun それ以外は信用しない() {
        assertEquals(TrustGate.Page.NONE, TrustGate.classify(null))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify(""))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("about:blank"))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("https://example.com/"))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("https://yomikura.local.evil.example/list.html"))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("https://evil.example/?u=https://yomikura.local/list.html"))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("https://syosetu.com.evil.example/"))
    }

    @Test fun https以外は対応サイトやアプリのホストでも信用しない() {
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("http://syosetu.com/"))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("http://yomikura.local/list.html"))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("javascript://syosetu.com/%0Aalert(1)"))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("file://syosetu.com/x"))
        assertEquals(TrustGate.Page.NONE, TrustGate.classify("content://yomikura.local/x"))
    }

    @Test fun ホストの大文字小文字は区別しない() {
        assertTrue(TrustGate.classify("HTTPS://YOMIKURA.LOCAL/queue.html").queue)
        assertTrue(TrustGate.classify("https://NCODE.syosetu.com/n1234ab/").injectable)
    }
}
