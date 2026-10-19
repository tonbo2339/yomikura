package com.tonbo2339.yomikura

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新を確認するサイトの判定。core/collect-rules.js の NH.collectOn / collectAny と同じ結果になること (js/core.test.mjs にも同じ内容がある)。 */
class CollectSitesTest {
    private fun data(settings: String) = JSONObject("""{"settings":$settings}""")

    @Test fun 未設定は全部対象() {
        assertTrue(SiteConfig.collectAny(JSONObject()))
        assertTrue(SiteConfig.collectOn(JSONObject(), "hameln"))
    }

    @Test fun collectSitesでオフにしたサイトは対象外() {
        val d = data("""{"collectSites":{"hameln":false}}""")
        assertFalse(SiteConfig.collectOn(d, "hameln"))
        assertTrue(SiteConfig.collectOn(d, "narou"))
    }

    @Test fun 表示しないサイトは対象外_R18は3サイトのどれかが表示中なら対象() {
        // nsfw を明示する (設定に無いときの既定は版で違う。ここで確かめたいのは「表示するサイト」による判定)
        val d = data("""{"nsfw":false,"sites":{"noc":false,"mnlt":false,"mid":true,"kakuyomu":false}}""")
        assertFalse(SiteConfig.collectOn(d, "kakuyomu"))
        assertTrue(SiteConfig.collectOn(d, "narou18"))
        assertFalse(SiteConfig.collectOn(data("""{"nsfw":false,"sites":{"noc":false,"mnlt":false,"mid":false}}"""), "narou18"))
    }

    @Test fun NSFWモード中はR18を確認しない() {
        val on = data("""{"nsfw":true}""")
        assertFalse(SiteConfig.collectOn(on, "narou18"))
        assertTrue(SiteConfig.collectOn(on, "narou"))
        assertTrue(SiteConfig.collectOn(on, "hameln"))
        assertTrue(SiteConfig.collectOn(data("""{"nsfw":false}"""), "narou18"))
    }

    @Test fun ログインしていないサイトは確認しない() {
        // nsfw を明示する (設定に無いときの既定は版で違う)
        val d = data("""{"nsfw":false}""").put("logins", JSONObject("""{"narou":true,"hameln":false,"kakuyomu":false}"""))
        assertTrue(SiteConfig.collectActive(d, "narou"))
        assertTrue(SiteConfig.collectActive(d, "narou18")) // R18 はなろうのログイン
        assertFalse(SiteConfig.collectActive(d, "hameln"))
        assertFalse(SiteConfig.collectActive(d, "kakuyomu"))
        assertTrue(SiteConfig.collectAnyActive(d))
        val none = data("""{"nsfw":false}""").put("logins", JSONObject("""{"narou":false,"hameln":false,"kakuyomu":false}"""))
        assertFalse(SiteConfig.collectAnyActive(none))
        assertNull(SiteConfig.collectStartUrl(none, "fg"))
        // logins をまだ写していなければ、ログイン済みとみなす
        assertTrue(SiteConfig.collectActive(data("""{"nsfw":false}"""), "hameln"))
    }

    @Test fun 巡回は最初のログイン済みの対象サイトから始まる() {
        val d = data("""{"nsfw":false}""").put("logins", JSONObject("""{"narou":true,"hameln":false,"kakuyomu":true}"""))
        assertTrue(SiteConfig.collectStartUrl(d, "bg")!!.startsWith("https://syosetu.com/favnovelmain/")) // ハーメルンは飛ばして、なろうから
    }

    @Test fun 休んでいるサイトは開きもせず次のサイトから始まる() {
        val now = 10_000_000L
        val d = data("""{"nsfw":false}""").put("rest", JSONObject("""{"hameln":${now - 30 * 60_000L}}""")) // 30 分前に止められた
        assertTrue(SiteConfig.collectStartUrl(d, "bg", now)!!.startsWith("https://syosetu.com/favnovelmain/")) // 自動は 60 分休む
        assertTrue(SiteConfig.collectStartUrl(d, "fg", now)!!.startsWith("https://syosetu.org/")) // 手動の休みは 10 分 (もう明けている)
        val all = data("""{"nsfw":false}""").put("rest", JSONObject().apply { listOf("hameln", "narou", "narou18", "kakuyomu").forEach { put(it, now) } })
        assertNull(SiteConfig.collectStartUrl(all, "fg", now)) // 全部休んでいる
        assertTrue(SiteConfig.collectAnyActive(all)) // 対象のサイトはある (画面では「休ませています」と出す)
    }

    @Test fun 巡回はハーメルンから始まる() {
        // 巡回の順は core/collect-rules.js の NH.COLLECT_ORDER と同じ (確認画面が出やすいハーメルンが先)
        val url = SiteConfig.collectStartUrl(data("""{"nsfw":false}"""), "bg")!!
        assertTrue(url.startsWith("https://syosetu.org/"))
        assertEquals("#nh-collect-bg", url.substring(url.indexOf('#')))
    }

    @Test fun 巡回は最初の対象サイトから始まる() {
        val d = data("""{"nsfw":false,"collectSites":{"hameln":false,"narou":false}}""")
        val url = SiteConfig.collectStartUrl(d, "fg")!!
        assertTrue(url.startsWith("https://syosetu.com/favnovelmain18/")) // ハーメルン・なろうを外すと R18 から
        assertEquals("#nh-collect-fg", url.substring(url.indexOf('#')))
    }

    @Test fun 対象が無ければ開始URLもない() {
        val d = data("""{"collectSites":{"narou":false,"narou18":false,"hameln":false,"kakuyomu":false}}""")
        assertFalse(SiteConfig.collectAny(d))
        assertNull(SiteConfig.collectStartUrl(d, "fg"))
    }
}
