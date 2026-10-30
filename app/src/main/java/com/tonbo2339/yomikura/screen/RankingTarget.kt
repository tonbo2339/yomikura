package com.tonbo2339.yomikura

import android.content.Context
import android.webkit.WebView
import com.tonbo2339.yomikura.SiteConfig.SITES
import org.json.JSONObject

/**
 * サイトのランキングの行き先の設定 (settings.rankings[サイト] = {url, label, custom}) の、今開いているページに対する操作。
 * 読み取りは AppSettings.siteRanking、ここは「このページを行き先にする」「初期設定に戻す」(⋮ メニューから)。
 */
class RankingTarget(
    private val ctx: Context,
    private val cfg: AppSettings,
    private val webView: () -> WebView,
    private val onChanged: () -> Unit,
) {
    /** 今開いているのが、どこかのサイトのランキングのページなら、そのサイトのキー。 */
    fun siteOf(url: String?): String? = SITES.map { it.key }.firstOrNull { SiteUrls.isRankingPage(it, url) }

    /** このページが、設定してあるランキングの行き先そのものか (初期設定のままなら false)。 */
    fun isHere(key: String, url: String?): Boolean = url != null && cfg.hasRankingSetting(key) && cfg.siteRanking(key) == url

    fun reset(key: String) {
        NHData.update(ctx) { data -> NHData.settingsOf(data).optJSONObject("rankings")?.remove(key) }
        onChanged()
        Messages.show(ctx, "ランキングの行き先を初期設定に戻しました")
    }

    /** 今開いているページを、そのサイトのランキングの行き先にする (設定画面では「登録したページ: タイトル」と出る)。 */
    fun setHere(key: String) {
        val web = webView()
        val url = web.url ?: return
        val title = web.title.orEmpty().substringBefore(" | ").substringBefore(" - ").take(40)
        NHData.update(ctx) { data ->
            val st = NHData.settingsForWrite(data)
            val rk = st.optJSONObject("rankings") ?: JSONObject().also { st.put("rankings", it) }
            rk.put(key, JSONObject().put("url", url).put("label", title.ifEmpty { "このページ" }).put("custom", true))
        }
        onChanged()
        Messages.show(ctx, "このページをランキングの行き先にしました")
    }
}
