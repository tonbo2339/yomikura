package com.tonbo2339.yomikura

import android.content.Context
import com.tonbo2339.yomikura.SiteConfig.R18_KEYS
import com.tonbo2339.yomikura.SiteConfig.SITES
import org.json.JSONObject

/**
 * 保存データ (JS 側の NH.load() と同じ JSON) の、画面側 (Kotlin) からの読み取り口。読むだけで、書き換えは NHData.update で行う。
 * 上部バーを作るときや、読書位置の保存のたびの onDataChanged で何度も呼ばれ、そのたびに全体を解析すると重いので、
 * 版 (NHData.version) の組にして控える。JS のスレッドからも呼ばれるので、版と中身は 1 組でまとめて差し替える
 * (版は解析の前に読む: 途中で書かれたら、次は読み直しになる)。
 *
 * 設定の項目名と既定値は、ここに集める (画面のあちこちに同じ `optBoolean("…", true)` を書かない)。
 * 既定値は JS 側 (core/store.js の NH.flag の既定 = オン) と同じにする。
 */
class AppSettings(private val ctx: Context) {
    @Volatile private var cache: Pair<Long, JSONObject>? = null

    /** 保存データ全体。版が変わっていなければ前に解析したものを使い回す。返したものは書き換えない。 */
    fun data(): JSONObject {
        val v = NHData.version
        cache?.let { (cv, o) -> if (cv == v) return o }
        return NHData.obj(ctx).also { cache = v to it }
    }

    /** 設定 (data().settings)。無ければ空。 */
    fun settings(): JSONObject = NHData.settingsOf(data())

    /** NSFW モードか (settings.nsfw)。オンの間は R18 のサイトへ一切アクセスしない。通信の判定にも使うので、保存データの版ごとに控えた結果を使う。 */
    fun nsfwOn(): Boolean = NHData.nsfwOn(settings())

    /** 上部バー: スクロールで隠すか (既定オン) */
    fun hideBarOnScroll(): Boolean = settings().optBoolean("hideBarOnScroll", true)

    /** 上部バー: 左端に再読み込みを出すか (既定オン) */
    fun showReload(): Boolean = settings().optBoolean("showReload", true)

    /** 読み込みの進みの線を出すか (既定オン) */
    fun showLoadBar(): Boolean = settings().optBoolean("showLoadBar", true)

    /** 表示するサイトか。既定は表示。NSFW モード中は R18 のサイトを出さない (設定の「表示するサイト」は変えない)。 */
    fun siteOn(key: String): Boolean {
        if (key in R18_KEYS && nsfwOn()) return false
        return settings().optJSONObject("sites")?.optBoolean(key, true) ?: true
    }

    /** タブに出すサイトの名前。設定で付けた名前 (settings.siteNames) があればそれ、無ければ既定。 */
    fun siteName(key: String, default: String): String =
        settings().optJSONObject("siteNames")?.optString(key)?.trim()?.take(12)?.takeIf { it.isNotEmpty() } ?: default

    /** タブの点の色 (settings.siteColors、"#RRGGBB")。設定が無い・形が違うときは null。 */
    fun siteColor(key: String): Int? {
        val c = settings().optJSONObject("siteColors")?.optString(key).orEmpty()
        return if (HEX_COLOR.matches(c)) android.graphics.Color.parseColor(c) else null
    }

    /** サイトのランキングの行き先。設定があればそれ (そのサイトの URL のときだけ)、無ければ既定。 */
    fun siteRanking(key: String): String {
        val url = rankingSetting(key)?.optString("url").orEmpty()
        return if (SiteUrls.safeNavUrl(url) != null && SiteUrls.siteKeyOf(url) == key) url else SITES.first { it.key == key }.ranking
    }

    /** ランキングの行き先を設定してあるか (settings.rankings[サイト])。 */
    fun hasRankingSetting(key: String): Boolean = rankingSetting(key) != null

    private fun rankingSetting(key: String): JSONObject? = settings().optJSONObject("rankings")?.optJSONObject(key)

    /** 上部バーを作り直すかどうかの判定に使う、「表示するサイト」「タブの名前」ほかの設定をまとめた文字列。 */
    fun barSignature(): String {
        val st = settings()
        return listOf("sites", "siteNames", "siteColors").joinToString("|") { st.optJSONObject(it)?.toString() ?: "" } +
            "|nsfw=" + nsfwOn() +
            "|" + (st.optJSONArray("tabOrder")?.toString() ?: "") +
            "|" + st.optBoolean("tabsOneRow") + "|" + st.optBoolean("tabsTinted") + "|" + st.optString("r18Mode") + "|" + showReload()
    }

    /** 裏での確認の設定をまとめた文字列 (変わったらアラームを掛け直す)。 */
    fun bgSignature(): String = settings().optJSONObject("bgCheck")?.toString() ?: ""

    /** 起動時の自動チェックをするか (設定が有効で、前回の確認から一定時間たっている)。 */
    fun autoCheckDue(): Boolean {
        val st = settings()
        if (!st.optBoolean("autoCheck", true) || !SiteConfig.collectAnyActive(data())) return false
        val hours = st.optInt("autoCheckHours", AUTO_DEFAULT_HOURS).coerceAtLeast(1)
        return System.currentTimeMillis() - data().optLong("lastCollect", 0L) > hours * 3_600_000L
    }

    companion object {
        private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")
        private const val AUTO_DEFAULT_HOURS = 3 // 自動チェックの最短間隔 (アクセスしすぎを避ける)
    }
}
