package com.tonbo2339.yomikura

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.tonbo2339.yomikura.SiteConfig.LIST_URL
import com.tonbo2339.yomikura.SiteConfig.QUEUE_URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * ほかのアプリや通知・ショートカットから渡された URL を開く流れ。
 * 渡された Intent から開く URL を取り出し ([linkFrom])、作品なら「後で読む」に追加するか聞いてから開く ([open])。
 * NSFW モード中の R18 のリンクは、サイトへアクセスせず「後で読む」に入れるだけにする。
 * 開き方 (navigate) は画面から受け取る。
 */
class LinkOpener(
    private val activity: AppCompatActivity,
    private val theme: BarTheme,
    private val cfg: AppSettings,
    private val prefs: SharedPreferences,
    private val webView: () -> WebView,
    private val startUrl: () -> String,
    private val navigate: (String) -> Unit,
    private val onListChanged: () -> Unit,
) {
    /** 「後で読む」に追加してから戻る先 (#nh-later-back のページの JS が NHStore.laterBack で呼ぶ。1 回だけ使う) */
    private var laterBackUrl: String? = null
    /** いま出ている選択のシート (重ねて出さないため) */
    private var openSheet: BottomSheet? = null

    /** 起動時の Intent から開く URL (direct = 「続きから読む」のショートカット)。開くものが無い・すでに開いたものなら null。 */
    fun atStart(intent: Intent?, saved: Bundle?): StartLink? {
        // 作り直し (文字の大きさ・画面分割などの変更、プロセスが終わったあとの復帰) では、起動時の Intent はもう扱ったので開かず、前の画面を戻す
        // (「続きから読む」は開くたびに行き先が変わるので、行き先で比べると開き直してしまう。共有の「対応していないサイト」も出し直さない)
        if (saved != null) return null
        val linked = linkFrom(intent)
        val external = intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND
        // 履歴 (最近使ったアプリ) から開き直したときだけ、前と同じ外部のリンクは開かない。新しく同じリンクを開いたのなら、毎回開く
        // (Android は、プロセスが終わったあとに履歴からアプリを開き直すと、最初に起動したときの Intent (外部のリンク) で作り直すので、
        //  一度開いた外部のリンクは控えておく。通知の「リストを開く」と、新しく渡されたリンクは毎回開く)
        val fromHistory = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val alreadyOpened = external && fromHistory && linked != null && linked == prefs.getString(KEY_LAST_EXTERNAL_LINK, null)
        if (linked == null || alreadyOpened) return null
        if (external) prefs.edit().putString(KEY_LAST_EXTERNAL_LINK, linked).apply()
        return StartLink(linked, direct = intent?.action == Shortcuts.ACTION_RESUME)
    }

    class StartLink(val url: String, val direct: Boolean)

    /** 「すべてのデータを削除」で、外部のリンクの控えも消す。 */
    fun forgetExternal() = prefs.edit().remove(KEY_LAST_EXTERNAL_LINK).apply()

    fun dismissSheet() { openSheet?.dismiss() } // 開いたままの Dialog は、Activity が終わると WindowLeaked になる

    /** 起動中にほかのアプリから URL を渡された (singleTask)。 */
    fun onNewIntent(intent: Intent) {
        linkFrom(intent)?.let { open(it, coldStart = false, direct = intent.action == Shortcuts.ACTION_RESUME) }
    }

    /**
     * 渡された Intent から開く URL を取り出す。
     *  - VIEW: リンクそのもの
     *  - SEND: 共有されたテキストの中の最初の URL
     * 対応サイト以外の URL は開かない (共有のときだけ、その旨を知らせる)。
     */
    private fun linkFrom(intent: Intent?): String? {
        if (intent?.action == BgCheck.ACTION_OPEN_LIST) return LIST_URL // 更新の通知・ショートカット「リストを開く」をタップした
        // ショートカット「続きから読む」: アプリで最後に読んだ作品の続き (読書位置は、開いたページが復元する)。まだ読んだ作品が無ければリスト
        if (intent?.action == Shortcuts.ACTION_RESUME) {
            return Resume.latestUrl(cfg.data(), cfg.nsfwOn())?.let(SiteUrls::toSupportedLink) ?: LIST_URL
        }
        if (intent?.action == OfflineService.ACTION_OPEN_QUEUE) return QUEUE_URL // 保存の通知をタップした
        val raw = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            // 装飾つきの文字 (CharSequence) で渡すアプリもある (getStringExtra だと null になり、黙って無視してしまう)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(SiteUrls::firstUrlIn)
            else -> null
        } ?: return null
        SiteUrls.toSupportedLink(raw)?.let { return it }
        if (intent?.action == Intent.ACTION_SEND) Messages.show(activity, "対応していないサイトの URL です")
        return null
    }

    /**
     * ほかのアプリから渡された URL を開く。作品 (作品トップ・話ページ) なら「後で読む」に追加するか聞く:
     *   追加して、読んでいた作品に戻る / 追加して、この作品を開く / 追加せずに開く
     * 作品名は開くまで分からないので、追加はリンク先のページで ui.js が行う (#nh-later / #nh-later-back)。
     * direct = 「続きから読む」のショートカット。追加の確認は出さず、そのまま開く (すでに読んでいた作品)。
     */
    fun open(url: String, coldStart: Boolean, direct: Boolean = false) {
        // まだ何も開いていない (起動したばかりで、前のリンクのシートを閉じずに次のリンクが来た) ときも、起動したばかりとして扱う
        // (やめたときに何も開かず、白い画面のままにならないように)
        val cold = coldStart || webView().url == null
        if (cfg.nsfwOn() && SiteUrls.isR18Url(url)) { // NSFW モード中は R18 のリンクを開かず、後で読むに入れておく (サイトへはアクセスしない)
            addR18Later(url)
            if (cold) navigate(startUrl())
            return
        }
        if (direct || !SiteUrls.isWorkUrl(url)) { navigate(url); return }
        val prev = readingNow()
        val base = url.substringBefore('#')
        openSheet?.dismiss() // 出ている途中に別のリンクが届いたら、前のシートは閉じる
        val sheet = BottomSheet(activity, theme.dp, theme.sheetColors(), BarTheme.ACCENT).also { openSheet = it }
        sheet.header(siteOfWorkUrl(url), "後で読むに追加しますか？")
        if (prev != null) sheet.button("追加して、読んでいた作品に戻る", prev.second, BottomSheet.Style.PRIMARY) {
            laterBackUrl = prev.first
            navigate("$base#nh-later-back")
        }
        sheet.button("追加して、この作品を開く", null, if (prev == null) BottomSheet.Style.PRIMARY else BottomSheet.Style.TONAL) { navigate("$base#nh-later") }
        sheet.button("追加せずに開く", null, BottomSheet.Style.PLAIN) { navigate(url) }
        // 閉じたら: 起動したばかりなら読んでいた作品 (無ければ起動ページ) を開き、使っている途中ならそのまま
        sheet.show { if (cold) navigate(prev?.first ?: startUrl()) }
    }

    /** 外部のリンクの作品を「後で読む」に追加したあと、読んでいた作品に戻る (open で選んだときだけ動く。1 回だけ使う)。 */
    fun finishLaterBack(result: String) {
        val back = laterBackUrl ?: return
        laterBackUrl = null
        val msg = when (result) { "added" -> "「後で読む」に追加しました"; "exists" -> "すでにリストにあります"; else -> "作品のページを開けなかったので、追加していません" }
        Messages.show(activity, msg)
        // 1 つ前のページがその作品なら戻る (スクロール位置もそのまま)。違えば (起動したばかり・ランキングにいた等) 開く
        val web = webView()
        val list = web.copyBackForwardList()
        val prevUrl = if (list.currentIndex > 0) list.getItemAtIndex(list.currentIndex - 1)?.url else null
        if (prevUrl == back) web.goBack() else navigate(back)
    }

    /**
     * NSFW モード中に外から来た R18 の URL を、サイトへアクセスせず「後で読む」に入れて、下にお知らせを出す。
     * 作品の名前は分からないので作品の ID を仮の名前にする (NSFW モードをオフにして開けば、リストの作品名はそのまま。開いた作品は名前を覚え直す)。
     * R18 の作品の URL でなければ (ランキングなど) 開かずに、お知らせだけ出す。
     */
    private fun addR18Later(url: String) {
        val w = SiteUrls.r18WorkOf(url)
        if (w == null) { Messages.show(activity, Edition.R18_OFF_WHY + "、R18 のページは開きません"); return }
        val key = "${w.site}:${w.id}"
        var added = false
        NHData.update(activity) { data ->
            val arr = data.optJSONArray("readLater") ?: JSONArray().also { data.put("readLater", it) }
            if ((0 until arr.length()).any { arr.optJSONObject(it)?.optString("key") == key }) return@update
            arr.put(JSONObject().put("key", key).put("site", w.site).put("id", w.id).put("url", w.url).put("title", w.id)
                .put("addedAt", System.currentTimeMillis()).put("done", false).put("kind", "later")
                .also { o -> w.sub?.let { o.put("sub", it) }; if (w.site == "hameln") o.put("r18", true) })
            added = true
        }
        Messages.show(activity, if (added) "開いたリンクの作品を「後で読む」に追加しました" else "開いたリンクの作品はすでに「後で読む」にあります")
        onListChanged()
    }

    /** 作品の URL → (タブの名前, 点の色)。シートの見出しに出す。 */
    private fun siteOfWorkUrl(url: String): Pair<String, Int> {
        // サイトの見分けは SiteUrls にまとめてある (ハーメルンの R-18 の h.syosetu.org もハーメルン)。R18 の作品 (novel18) はキーが無い
        val key = SiteUrls.siteKeyOf(url) ?: return "R18" to BarTheme.R18
        val site = SiteConfig.SITES.firstOrNull { it.key == key } ?: SiteConfig.SITES.first()
        return cfg.siteName(key, site.label) to (cfg.siteColor(key) ?: BarTheme.siteColor(key))
    }

    /** さっきまで読んでいた作品 (URL, 作品名): 今開いている作品のページ、無ければアプリで最後に読んだ作品 (閲覧履歴)。 */
    private fun readingNow(): Pair<String, String>? {
        val web = webView()
        val cur = web.url
        if (SiteUrls.isWorkUrl(cur)) return cur!! to web.title.orEmpty().replace(Regex(" - (ハーメルン|カクヨム|小説家になろう)$"), "")
        val h = cfg.data().optJSONArray("history") ?: return null
        for (i in 0 until h.length()) {
            val e = h.optJSONObject(i) ?: continue
            // 保存データは対応サイトのスクリプトも書けるので、開く前に対応サイトの https の URL か確かめる
            if (e.has("appAt") && TrustGate.classify(e.optString("url")).injectable) return e.optString("url") to e.optString("title")
        }
        return null
    }

    private companion object {
        /** 最後に開いた外部のリンク (履歴からの作り直しで、同じリンクを開き直さないため) */
        const val KEY_LAST_EXTERNAL_LINK = "lastExternalLink"
    }
}
