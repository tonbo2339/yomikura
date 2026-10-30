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

    /** 対応サイト以外のリンク (広告など) をタップしたとき、端末のブラウザで開くか (settings.externalLinks が "app" 以外。既定は端末のブラウザ。"app" はアプリの中で開く) */
    fun externalLinksInBrowser(): Boolean = settings().optString("externalLinks", "browser") != "app"

    /** 画面 (サイト・アプリ内ページ) の文字を、Android の「文字サイズ」の設定に合わせるか (既定オン。オフなら、その設定にかかわらず標準の大きさ) */
    fun followSystemTextSize(): Boolean = settings().optBoolean("followSystemTextSize", true)

    /** 画面に戻ったとき、WebView の描画を促す (スクロール位置を 1px 動かして戻す) か (既定オン。切り分けのため、診断情報のページの差し込みでオフにできる版がある) */
    fun repaintOnResume(): Boolean = settings().optBoolean("repaintOnResume", true)

    /** 開発用ログ ([DevLog]) を取るか (既定オフ。Edition.DEBUG_TOOLS が true の版の、設定の「デバッグ」のスイッチ。設定名 `netLog` は前の名前のまま) */
    fun devLogOn(): Boolean = Edition.DEBUG_TOOLS && settings().optBoolean("netLog", false)

    /** 開発用ログの分類 ([DevLog.Category.key]) ごとのスイッチ (既定オン。全体のスイッチがオンのときだけ効く) */
    fun devLogCategory(key: String): Boolean = settings().optBoolean(key, true)

    /** 開発用ログを残す大きさ (MB。この大きさを超えたら、古い分を 1 つ前のファイルに回す。最大で 2 倍まで使う) */
    fun devLogMaxMb(): Int = settings().optInt("devLogMaxMb", 2).coerceIn(1, 50)

    /** 開発用ログを残す日数 (これより古い行は、起動のときに捨てる) */
    fun devLogDays(): Int = settings().optInt("devLogDays", 7).coerceIn(1, 365)

    /** 手動の「更新を確認」を、サイトの休止 (SiteRest) と「前回から 10 分」の制限に関わらず行うか (既定オフ。デバッグのスイッチ)。確認画面が出たら、今までどおり画面に出して人間に通してもらう (自動では通さない) */
    fun forceManualCheck(): Boolean = forceManualCheck(settings())

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

    /** 起動時の自動チェックをするか (設定が有効で、前回の確認 (始めた時刻も数える。途中で止まった確認のすぐあとにも走らせない) から一定時間たっている)。 */
    fun autoCheckDue(): Boolean = autoCheck().due

    /** 起動時の自動チェックの判断と、その中身 (開発用ログに残す)。sinceMin = 前回の確認 (始めた時刻も数える) から何分か (一度も無ければ -1) */
    data class AutoCheck(val enabled: Boolean, val anySite: Boolean, val sinceMin: Long, val hours: Int, val due: Boolean)

    fun autoCheck(): AutoCheck {
        val enabled = settings().optBoolean("autoCheck", true)
        val anySite = SiteConfig.collectAnyActive(data())
        val hours = settings().optInt("autoCheckHours", AUTO_DEFAULT_HOURS).coerceAtLeast(1)
        val now = System.currentTimeMillis()
        val last = CollectCooldown.lastOf(data())
        return AutoCheck(enabled, anySite, if (last > 0L) (now - last) / 60_000L else -1L, hours, enabled && anySite && CollectCooldown.autoDue(last, hours, now))
    }

    companion object {
        private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")
        /** forceManualCheck の判定だけ (保存データの設定から。窓口 (StoreBridge) も使う) */
        fun forceManualCheck(settings: JSONObject): Boolean = Edition.DEBUG_TOOLS && settings.optBoolean("forceManualCheck", false)

        private const val AUTO_DEFAULT_HOURS = 3 // 自動チェックの最短間隔 (アクセスしすぎを避ける)
    }
}
