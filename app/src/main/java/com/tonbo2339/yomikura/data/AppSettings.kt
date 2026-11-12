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

    /** 上部バーを置く位置: "top" (既定) / "left" / "right"。左右は縦のバーになる (主に広い画面向け) */
    fun barPos(): String = settings().optString("barPos", "top").let { if (it == "left" || it == "right") it else "top" }

    /** 上部バー: 普段は隠し、置いてある端から内側へスワイプしたときだけ出すか (既定オフ) */
    /** 左右のバーを、隠れているときに端からのスワイプで出すか (既定オン。隠れているバーの目印は、スワイプの設定に関係なく出る) */
    fun barSwipe(): Boolean = settings().optBoolean("barSwipe", true)

    fun barEdge(): Boolean = settings().optBoolean("barEdge", false) && barPos() != "top" // 上に置くときは、「端から出す」は使わない (棒が状態バーと重なるため)

    /** バー (上・左右) の背景色 (settings.barBg。"#RRGGBB")。選んでいなければ null (テーマの既定)。形が正しくない値も null */
    fun barBg(): Int? = settings().optString("barBg").takeIf { HEX_COLOR.matches(it) }?.let { android.graphics.Color.parseColor(it) }

    /** ボタンの分だけの浮かぶカードの濃さ (不透明度 %。60〜100。既定 95)。小さいほど、後ろの本文が透ける */
    fun barCardAlpha(): Int = settings().optInt("barCardAlpha", 95).coerceIn(60, 100)

    /** 重ねて出す左右のバーの縦の長さ: "auto" (既定。スマホ = 幅 600dp 未満は compact、広い画面は full) / "compact" (ボタンの分だけ) / "full" (画面いっぱい) */
    fun railHeight(): String = settings().optString("railHeight", "auto").let { if (it == "compact" || it == "full") it else "auto" }

    /** 重ねて出す左右のバーを、ボタンの分の高さだけにするか */
    fun railCompact(smallestWidthDp: Int): Boolean = when (railHeight()) { "compact" -> true; "full" -> false; else -> smallestWidthDp < 600 }

    /** 「端からスワイプで出す」でバーを隠している間の目印: "line" (細い棒。既定) / "button" (小さなボタン。押すとバーが出る) 。左右のバーだけ (上は棒) */
    fun barHidden(): String = settings().optString("barHidden", "line").let { if (it == "button") it else "line" }

    /** 目印が「小さなボタン」のときの色 (gray 既定 / green / blue / orange / pink) とマーク (more 既定 / dot / settings / book / arrow) */
    fun barButtonColor(): String = settings().optString("barButtonColor", "gray")
    /** 色が「自由」(custom) のときの色: 色相 (0〜359)・彩度 (0〜100 %。既定 80)・明るさ (20〜100 %。既定 70) */
    fun barButtonHue(): Int = settings().optInt("barButtonHue", 150).mod(360)
    fun barButtonSat(): Int = settings().optInt("barButtonSat", 80).coerceIn(0, 100)
    fun barButtonVal(): Int = settings().optInt("barButtonVal", 70).coerceIn(20, 100)
    fun barButtonIcon(): String = settings().optString("barButtonIcon", "more")
    /** 小さなボタンの形: "circle" (丸い背景つき。既定) / "plain" (マークだけ。背景なし) */
    fun barButtonShape(): String = if (settings().optString("barButtonShape") == "plain") "plain" else "circle"
    /** 小さなボタンの濃さ (透明度の逆。20〜100 %。既定 70)。数が大きいほど、はっきり見える */
    fun barButtonAlpha(): Int = settings().optInt("barButtonAlpha", 70).coerceIn(20, 100)

    /** 目印が「小さなボタン」のとき、ボタンを置いた高さ (画面の高さに対する 0〜1。既定は真ん中)。ボタンをドラッグして動かすと覚える */
    fun barButtonPos(): Double = settings().optDouble("barButtonPos", 0.5).let { if (it.isNaN()) 0.5 else it.coerceIn(0.0, 1.0) }

    /** 全画面 (状態バーとナビゲーションバーを隠し、画面の端から内側へスワイプすると一時的に出す)。既定オフ */
    fun fullscreen(): Boolean = settings().optBoolean("fullscreen", false)

    /**
     * 全画面にしなくても、状態バー (時計・電池・通知のアイコン) を残したまま、ページをその下まで広げるか (既定オン)。
     * 使わないとき: 全画面 (全画面の設定の時計を使う)、上のバーを「いつも表示」で上に固定しているとき (ページはバーの下から始まるので、広げる意味がない)
     */
    fun statusOverlay(): Boolean = !fullscreen() && settings().optBoolean("statusOverlay", true) && !(barPos() == "top" && !hideBarOnScroll())

    /** 新しい版が見つかったとき、⋮ / 歯車に小さな点を出すか (既定オン。UpdateNotice)。オフなら、アプリを開いたときの確認もしない */
    fun updateNotice(): Boolean = settings().optBoolean("updateNotice", true)

    /** 全画面のとき、余白に時計を出すか (既定オフ) */
    fun fullscreenClock(): Boolean = settings().optBoolean("fullscreenClock", false)

    /** 全画面のとき、カメラの切り欠きを: "ignore" (無視して、上まで内容を広げる。既定) / "avoid" (避ける。切り欠きの余白を空ける) */
    fun fullscreenCutout(): String = if (settings().optString("fullscreenCutout") == "avoid") "avoid" else "ignore"

    /** 時計を置く側: "end" (右。既定) / "start" (左) */
    fun fullscreenClockSide(): String = if (settings().optString("fullscreenClockSide") == "start") "start" else "end"

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
            "|" + st.optBoolean("tabsOneRow") + "|" + st.optBoolean("tabsTinted") + "|" + st.optString("r18Mode") + "|" + showReload() + "|" + barPos() + "|" + barEdge() + "|" + railHeight() + "|" + barCardAlpha() + "|" + settings().optString("barBg") + "|" + barHidden()
    }

    /** 裏での確認の設定をまとめた文字列 (変わったらアラームを掛け直す)。 */
    fun bgSignature(): String = settings().optJSONObject("bgCheck")?.toString() ?: ""

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
