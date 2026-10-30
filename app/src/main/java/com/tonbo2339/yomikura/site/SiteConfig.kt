package com.tonbo2339.yomikura

import org.json.JSONObject

/** 対応サイトの定義 (サイト一覧・メニュー・更新の確認の対象)。画面 (MainActivity) から切り離した、Android の画面に依存しない値と計算。 */
object SiteConfig {
    class Site(val key: String, val label: String, val ranking: String, val top: String, val r18: Boolean = false)

    // ---- アプリ内ページ ----
    const val LIST_URL = "https://${TrustGate.APP_HOST}/list.html"
    const val QUEUE_URL = "https://${TrustGate.APP_HOST}/queue.html"
    const val SETTINGS_URL = "https://${TrustGate.APP_HOST}/settings.html"
    const val WELCOME_URL = "https://${TrustGate.APP_HOST}/welcome.html"

    // ---- サイト ----
    const val NAROU_RANKING = "https://yomou.syosetu.com/rank/top/"
    const val HAMELN_RANKING = "https://syosetu.org/?mode=rank"
    const val KAKUYOMU_RANKING = "https://kakuyomu.jp/rankings/all/daily"
    const val R18_BOOKMARK = "https://syosetu.com/favnovelmain18/list/?nowcategory=1&order=newlist"
    const val NAROU_MYPAGE = "https://syosetu.com/user/top/"
    /** しおり・ブックマークは、絞り込みなどをしていない素のページ (更新の確認は COLLECT_PAGES の絞り込み付きのページを使う) */
    const val R18_BOOKMARK_PLAIN = "https://syosetu.com/favnovelmain18/list/"

    /** R18 のサイトのキー (NSFW モードでタブ・起動ページから外す) */
    val R18_KEYS = setOf("noc", "mnlt", "mid")

    val SITES = listOf(
        Site("narou", "なろう", NAROU_RANKING, "https://yomou.syosetu.com/"),
        Site("hameln", "ハーメルン", HAMELN_RANKING, "https://syosetu.org/"),
        Site("kakuyomu", "カクヨム", KAKUYOMU_RANKING, "https://kakuyomu.jp/"),
        // R18 のトップは /top/top/ (ホストの / は毎回出る年齢確認のページ)
        Site("noc", "ノクターン", "https://noc.syosetu.com/rank/top/", "https://noc.syosetu.com/top/top/", true),
        Site("mnlt", "ムーンライト", "https://mnlt.syosetu.com/rank/top/", "https://mnlt.syosetu.com/top/top/", true),
        Site("mid", "ミッドナイト", "https://mid.syosetu.com/rank/top/", "https://mid.syosetu.com/top/top/", true),
    )

    /** メニューの「ランキング」を設定の行き先に差し替える目印 */
    const val RANKING_SLOT = "nh:ranking"

    // タブのメニュー: どのサイトも トップページ / ランキング / 作品検索 / しおり・ブックマーク / マイページ
    val NAROU_MENU = listOf(
        "トップページ" to "https://yomou.syosetu.com/",
        "ランキング" to RANKING_SLOT,
        "作品検索" to "https://yomou.syosetu.com/search.php",
        "ブックマーク" to "https://syosetu.com/favnovelmain/list/",
        "マイページ" to NAROU_MYPAGE,
    )
    val HAMELN_MENU = listOf(
        "トップページ" to "https://syosetu.org/",
        "ランキング" to RANKING_SLOT,
        "作品検索" to "https://syosetu.org/search/?mode=search",
        "しおり" to "https://syosetu.org/?mode=siori2_view&type=2",
        "マイページ" to "https://syosetu.org/?mode=login",
    )
    val KAKUYOMU_MENU = listOf(
        "トップページ" to "https://kakuyomu.jp/",
        "ランキング" to RANKING_SLOT,
        "作品検索" to "https://kakuyomu.jp/search",
        "フォロー中の小説" to "https://kakuyomu.jp/my/antenna/works",
        "マイページ" to "https://kakuyomu.jp/my",
    )

    /** 各サイトのログイン画面 (巡回の結果の「ログイン」ボタン) */
    val LOGIN_PAGES = mapOf(
        "narou" to "https://syosetu.com/login/input/",
        "narou18" to "https://syosetu.com/login/input/",
        "hameln" to "https://syosetu.org/?mode=login",
        "kakuyomu" to "https://kakuyomu.jp/my",
    )

    // ---- 更新の確認 ----

    /** 更新の確認で見るサイトごとの一覧 (巡回の開始ページ。確認画面で止まったときの「開く」でも開く) */
    val COLLECT_PAGES = mapOf(
        "narou" to "https://syosetu.com/favnovelmain/list/?nowcategory=1&order=newlist",
        "narou18" to R18_BOOKMARK,
        "hameln" to "https://syosetu.org/?mode=siori2_view&type=2&filter=1",
        "kakuyomu" to "https://kakuyomu.jp/my/antenna/works",
    )

    /** 更新の確認の対象になるサイトを巡回する順 (core/collect-rules.js の NH.COLLECT_ORDER と同じ。確認画面が出やすいハーメルンが先) */
    private val COLLECT_ORDER = listOf("hameln", "narou", "narou18", "kakuyomu")

    /**
     * 更新を確認するサイトか。表示するサイト (settings.sites、R18 は 3 サイトのどれか) で、settings.collectSites[id] がオフでない。
     * core/collect-rules.js の NH.collectOn と同じ判定。data は保存データ全体。
     */
    fun collectOn(data: JSONObject, id: String): Boolean {
        val st = NHData.settingsOf(data)
        if (id == "narou18" && NHData.nsfwOn(st)) return false // NSFW モード中は R18 を確認しない
        val sites = st.optJSONObject("sites")
        val shown = if (id == "narou18") R18_KEYS.any { sites?.optBoolean(it, true) ?: true } else sites?.optBoolean(id, true) ?: true
        return shown && (st.optJSONObject("collectSites")?.optBoolean(id, true) ?: true)
    }

    fun isCollectSite(id: String): Boolean = id in COLLECT_ORDER

    fun collectAny(data: JSONObject): Boolean = COLLECT_ORDER.any { collectOn(data, it) }

    /**
     * 実際に確認するか = 設定で対象で、ログインしている (保存データの logins。まだ写していなければログイン済みとみなす)。
     * core/collect-rules.js の NH.collectActive と同じ。ログインしていないサイトは、巡回で飛ばす (開くとログイン画面になるため)
     */
    fun collectActive(data: JSONObject, id: String): Boolean =
        collectOn(data, id) && (data.optJSONObject("logins")?.optBoolean(if (id == "narou18") "narou" else id, true) ?: true)

    fun collectAnyActive(data: JSONObject): Boolean = COLLECT_ORDER.any { collectActive(data, it) }

    /**
     * 更新の巡回の最初に開くページ (最初の対象サイトのブックマーク / しおり / フォロー)。mode = bg / fg / fg-auto。対象のサイトが無ければ null。
     * アクセスを止められて休んでいるサイト (SiteRest。手動 fg は 10 分、自動は 60 分) は飛ばす (開くだけでもアクセスになるため。全部休んでいれば null)。
     * そこから先のサイトは、ui-collect.js が同じ規則 (NH.isResting) で順にたどり、最後にリスト / 起動ページへ進む。
     * forceManual (AppSettings.forceManualCheck) のときの手動 (fg) は、休止を見ずに開く (確認画面が出たら、人間に通してもらう)。
     */
    fun collectStartUrl(data: JSONObject, mode: String, now: Long = System.currentTimeMillis(), forceManual: Boolean = false): String? {
        val rest = data.optJSONObject("rest")
        val ignoreRest = forceManual && mode == "fg"
        return COLLECT_ORDER.firstOrNull { collectActive(data, it) && (ignoreRest || !SiteRest.isResting(rest, it, auto = mode != "fg", now)) }
            ?.let { COLLECT_PAGES.getValue(it) + "#nh-collect-$mode" }
    }
}
