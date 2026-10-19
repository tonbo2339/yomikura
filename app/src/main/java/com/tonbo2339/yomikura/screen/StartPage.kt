package com.tonbo2339.yomikura

import android.content.SharedPreferences
import android.net.Uri
import com.tonbo2339.yomikura.SiteConfig.LIST_URL
import com.tonbo2339.yomikura.SiteConfig.SITES

/**
 * 起動したときに最初に開くページと、そのための控え (最後に開いたページ・初回の画面を出すか)。
 * 設定: start = ranking / top / list / last、startSite = サイト。
 */
class StartPage(private val cfg: AppSettings, private val prefs: SharedPreferences, private val context: android.content.Context) {

    /** 起動時に最初に開くページ。 */
    fun url(): String {
        val st = cfg.settings()
        val start = st.optString("start", "list")
        if (start == "list") return LIST_URL
        if (start == "last") lastUrl()?.let { return it } // 最後に開いていたページ (無ければ、下のランキング)
        val site = SITES.firstOrNull { it.key == st.optString("startSite", "narou") && cfg.siteOn(it.key) }
            ?: SITES.firstOrNull { cfg.siteOn(it.key) }
            ?: return LIST_URL
        return if (start == "top") site.top else cfg.siteRanking(site.key)
    }

    /** 最後に開いていたサイトのページ。今は使わないサイト (表示オフ・18 歳未満の R18) のページなら null。 */
    private fun lastUrl(): String? {
        val url = prefs.getString(KEY_LAST_URL, null)?.let(::fixLegacyHost) ?: return null
        val host = Uri.parse(url).host.orEmpty()
        if (!SiteUrls.isSupportedHost(host) || !TrustGate.classify(url).injectable) return null
        val key = SiteUrls.siteKeyOf(url)
        val r18 = key in SiteConfig.R18_KEYS || host == "novel18.syosetu.com"
        if (r18 && (!cfg.settings().optBoolean("adult", true) || cfg.nsfwOn())) return null
        return if (key == null || cfg.siteOn(key)) url else null
    }

    /** 最後に開いていたサイトのページを控える (起動ページ「最後に開いていたページ」用)。年齢確認の画面や巡回用の印の付いた URL は除く。 */
    fun recordLast(url: String?) {
        val u = Uri.parse(url.orEmpty())
        if (!SiteUrls.isSupportedHost(u.host.orEmpty()) || "#nh-" in url.orEmpty() || "ageauth" in u.path.orEmpty()) return
        prefs.edit().putString(KEY_LAST_URL, url).apply()
    }

    /**
     * 初めての起動で、まだ使うサイトを選んでいない (welcome.html を出す)。
     * 保存データが空なら初回とみなし、選び終わる (settings.welcomed) まで覚えておく。
     * (初回をリンクから開くと、閲覧履歴などでデータが書かれるので、データの有無だけでは次の起動で判定できない)
     */
    fun needsWelcome(): Boolean {
        if (cfg.settings().optBoolean("welcomed")) {
            if (prefs.contains(KEY_NEEDS_WELCOME)) prefs.edit().remove(KEY_NEEDS_WELCOME).apply()
            return false
        }
        if (NHData.get(context).isEmpty()) prefs.edit().putBoolean(KEY_NEEDS_WELCOME, true).apply()
        return prefs.getBoolean(KEY_NEEDS_WELCOME, false)
    }

    /** 「すべてのデータを削除」のあと、初回の画面を出すようにする。 */
    fun markNeedsWelcome() = prefs.edit().putBoolean(KEY_NEEDS_WELCOME, true).apply()

    companion object {
        private const val KEY_LAST_URL = "lastUrl"
        private const val KEY_NEEDS_WELCOME = "needsWelcome"

        /** 旧版が控えたアプリ内ページの URL (旧ホスト名) を、今のホスト名に直す。旧版が無い版 (LEGACY_APP_HOST が空) では何もしない */
        fun fixLegacyHost(url: String): String =
            if (Edition.LEGACY_APP_HOST.isEmpty()) url else url.replace("//" + Edition.LEGACY_APP_HOST + "/", "//" + TrustGate.APP_HOST + "/")
    }
}
