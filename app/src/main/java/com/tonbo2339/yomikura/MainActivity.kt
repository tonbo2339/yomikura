package com.tonbo2339.yomikura

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import android.animation.ValueAnimator
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import com.tonbo2339.yomikura.SiteConfig.COLLECT_PAGES
import com.tonbo2339.yomikura.SiteConfig.HAMELN_MENU
import com.tonbo2339.yomikura.SiteConfig.KAKUYOMU_MENU
import com.tonbo2339.yomikura.SiteConfig.LIST_URL
import com.tonbo2339.yomikura.SiteConfig.LOGIN_PAGES
import com.tonbo2339.yomikura.SiteConfig.NAROU_MENU
import com.tonbo2339.yomikura.SiteConfig.NAROU_MYPAGE
import com.tonbo2339.yomikura.SiteConfig.QUEUE_URL
import com.tonbo2339.yomikura.SiteConfig.R18_BOOKMARK_PLAIN
import com.tonbo2339.yomikura.SiteConfig.R18_KEYS
import com.tonbo2339.yomikura.SiteConfig.RANKING_SLOT
import com.tonbo2339.yomikura.SiteConfig.SETTINGS_URL
import com.tonbo2339.yomikura.SiteConfig.SITES
import com.tonbo2339.yomikura.SiteConfig.WELCOME_URL

class MainActivity : AppCompatActivity(), BridgeHost {

    private lateinit var webView: WebView
    private lateinit var bar: LinearLayout
    /** タブが 1 段に収まらないときの 2 段目 (横スクロールにせず、段を分ける) */
    private lateinit var tabRow: LinearLayout
    private lateinit var root: LinearLayout
    /** 上部バー + タブの 2 段目 + 区切り線をまとめた箱 (スクロールで隠す) */
    private lateinit var header: LinearLayout
    private lateinit var divider: View
    /** 読み込み中の進み具合を示す細い線 (WebView の上端に重ねる) */
    private lateinit var loadBar: View
    /** 上部バーの下の、保存の進みの帯 (header の中。バーと一緒に隠れる) */
    private lateinit var saveStrip: LinearLayout
    private lateinit var saveText: TextView
    private lateinit var saveLine: View
    /** ページ内検索のバー (WebView の上端に重ねる) */
    private lateinit var findBar: FindBar
    /** 上部バー左端の「再読み込み」。アプリ内の画面 (リスト・設定など) では見えなくする */
    private var reloadButton: View? = null
    /** 上部バー右端の「⋮」。リストなどアプリ内の画面 (設定を除く) では歯車になり、押すと設定を開く */
    private var menuButton: View? = null
    private lateinit var badgeView: TextView
    private lateinit var hostFilter: HostFilter
    private lateinit var injectedJs: String
    override val updater: Updater by lazy { Updater(this, Edition.UPDATE_REPO, Edition.UPDATE_NEEDS_LOGIN) }
    override val launcherIcon: LauncherIcon by lazy { LauncherIcon(this) }
    override val activity: AppCompatActivity get() = this
    private val prefs by lazy { getSharedPreferences(Edition.PREFS_NAME, Context.MODE_PRIVATE) }
    private val dp by lazy { resources.displayMetrics.density }
    /** 読書の補助 (読み上げ・自動スクロール・音量キー)。読み上げは、画面を離れる・別のページへ移るときに止める */
    /** いま開いているページへの操作 (共有・URL のコピー・ほかのブラウザ・表示の診断) */
    private val pageActions by lazy { PageActions(this, { webView }, dp) }
    private val aids by lazy { ReadingAids(this, { webView }, ::settingsObj, { !appPageNow && isWorkUrl(webView.url) }) }
    private val offline by lazy { OfflineStore(java.io.File(filesDir, "offline")) }
    private val saveQueue by lazy { SaveQueue(java.io.File(filesDir, SaveQueue.FILE_NAME)) }

    /**
     * 通知の許可 (Android 13 以降) を求めてから、保存のサービスを始める。どのスレッドから呼んでもよい。
     * 裏に回っていて前景サービスを始められないとき (Android 12 以降) は、キューは順番待ちのまま残して、お知らせだけ出す。
     */
    override fun startSaving() = runOnUiThread {
        if (Build.VERSION.SDK_INT >= 33 && !BgCheck.notifyAllowed(this)) {
            requestNotifyPermission()
        }
        try {
            OfflineService.start(this)
        } catch (e: Exception) {
            Diag.w(TAG, "startForegroundService failed", e)
            showSnack("保存を始められませんでした。アプリを開いた状態で、保存キューからもう一度始めてください")
        }
    }

    /** キューが書き換わった (JS の窓口から)。上部の保存の帯を直す */
    override fun queueChanged() = runOnUiThread { refreshSaveStrip() }

    /** オフライン保存をすべて消す。保存待ち・保存中のキューも空にして、サービスを止める */
    override fun dropOfflineAll(): Boolean {
        saveQueue.clearAll()
        stopService(Intent(this, OfflineService::class.java))
        queueChanged()
        return offline.deleteAll()
    }
    /** 版によってだけある連携 (アプリを開いたときに、時期なら自動で送る) */
    private lateinit var extras: ExtraHooks
    /**
     * いま WebView に出ているページの信頼の状態 (TrustGate)。JS のスレッドから見るので、ページの開始時に控えておく。
     * アプリ内ページ (appPageNow) は端末の操作や保存データの窓口を使える。
     * 対応サイトの https かアプリ内ページ (trustedPageNow) のときだけ、保存データ (NHStore.get / set) とオフライン保存の書き込みを受け付ける
     * (サイトのリンクから別のサイトへ移ったとき、そのサイトのスクリプトにリストや閲覧履歴を読ませないため)。
     */
    @Volatile private var gate = TrustGate.Page.NONE
    /** この画面でページの読み込みが一度でも始まったか (onPageStarted)。まだなら、最初のページの通信のときに gate を決める (NovelClient.shouldInterceptRequest) */
    @Volatile private var pageStarted = false
    override val appPageNow: Boolean get() = gate.app
    override val trustedPageNow: Boolean get() = gate.trusted
    /** 保存キューの画面 (ここでは上部の保存の帯を出さない) */
    private val onQueuePage: Boolean get() = gate.queue
    private val onSettingsPage: Boolean get() = gate.settings
    /** オフライン保存の読書画面 (offline.html?ep=) */
    private val onReaderPage: Boolean get() = gate.reader

    private var currentTheme = "auto"
    /** 上部バーを作ったときの「表示するサイト」設定。変わったらバーを作り直す。 */
    private var sitesJson = ""
    /** 裏での確認の設定 (変わったらアラームを掛け直す) */
    private var bgJson = ""
    /** 裏での確認がデータを書き換えたときに呼ばれる (BgCheck.onDataChanged に登録する。自分のものだけ外すため保持する) */
    private val bgListener: () -> Unit = { onDataChanged() }
    /** 画面に出さない更新の確認 (「更新」ボタン / 起動時の自動確認)。確認中だけある */
    private var collector: Collector? = null
    @Volatile private var collecting = false
    override val collectingNow: Boolean get() = collecting
    /** 確認の進み具合 (「2 / 4」)。リストを後から開いたときにも出せるように持っておく */
    @Volatile private var collectProgress = ""
    override val collectProgressText: String get() = collectProgress
    /** 起動時の Intent で開いた URL (作り直したときに、もう一度開かないため)。 */
    private var openedLink: String? = null

    /** バックアップのファイルへの書き出し・読み込み (設定の「データの管理」)。registerForActivityResult のため、onCreate より前に作る */
    private val backupFiles = BackupFiles(this, ::evalJs, ::emitJs)

    override fun startExport(mode: String, files: List<Pair<String, String>>) = backupFiles.startExport(mode, files)

    override fun startImport() = backupFiles.startImport()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Diag.init(this) // エラーの記録 (診断情報に載せる)
        Thread { Shortcuts.publish(this) }.start() // ランチャーの長押しのショートカット (続きから読む・リストを開く)
        // 入れ直した直後 (保存データが空) なら、Android のバックアップから戻った設定の写しを使う (SettingsBackup)
        SettingsBackup.restoreIfEmpty(this)
        // 保存済みのテーマは、画面を作る前に適用する (起動時の作り直しを避ける)
        currentTheme = settingsObj().optString("theme", "auto")
        applyTheme(currentTheme)
        super.onCreate(savedInstanceState)
        backupFiles.restore(savedInstanceState)
        launcherIcon.applyPending() // 「後で」で覚えたランチャーアイコンを、この起動で適用する

        hostFilter = Edition.hostFilter(this)
        injectedJs = NHData.tokenJs + AppAssets.siteScripts(this)

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(StoreBridge(this@MainActivity), "NHStore")
            addJavascriptInterface(DiagBridge(this@MainActivity), "NHDiag")
            addJavascriptInterface(OfflineBridge(this@MainActivity, offline, saveQueue, hostFilter), "NHOffline")
            // 版によってだけある連携 (Edition.installExtras)。公開版は何もしない
            extras = Edition.installExtras(this@MainActivity, this, offline, ::emitJs) { appPageNow }
            webViewClient = NovelClient()
            // WebChromeClient が無いと、サイトの確認ダイアログ (「本当に削除しますか？」など) が出ない。進み具合も受け取る
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) = showLoadProgress(newProgress)
            }
            setOnScrollChangeListener { _, _, scrollY, _, oldY -> onWebScroll(scrollY, oldY) }
        }
        // ダークテーマのときは、ダーク未対応のサイトも自動で暗くする
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }
        // ヘッダー / フッターを隠す CSS は、ちらつかないようページ描画前に入れる
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origins = setOf("https://*.syosetu.com", "https://syosetu.com", "https://syosetu.org", "https://kakuyomu.jp")
            WebViewCompat.addDocumentStartJavaScript(
                webView, NHData.tokenJs + AppAssets.editionJs + AppAssets.readAsset(this, "js/sites.js") + AppAssets.readAsset(this, "js/early.js"), origins
            )
            // ページの読み込み完了 (onPageFinished) が遅れることがあり、そのままだと画面の部品の注入も遅れる。
            // そこで DOM が読めた時点でも注入する (onPageFinished の注入と二重になっても、注入 JS 側の仕組みで二重にならない)
            if (Edition.INJECT_AT_DOM_READY) {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    "(function(){var run=function(){" + injectedJs + "\n};" +
                        "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run);else run();})();",
                    origins
                )
            }
        }
        sitesJson = sitesSetting()
        bgJson = bgSetting()
        setContentView(buildLayout())
        // 裏での確認: 書き換えを画面 (バッジ・上部バー) に反映し、アラームを掛け直す (アプリの更新後など)
        BgCheck.onDataChanged = bgListener
        BgCheck.reschedule(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findBar.isShowing) findBar.hide() else if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        // ほかのアプリから URL を渡されて起動した: 前回の画面の復元や自動チェックより、その URL を優先する
        // (バックグラウンドで終了されたあとは savedInstanceState もあるので、こちらを先に見る)
        // ただし、開いたあとの作り直し (設定変更など) では、その後に移ったページを復元する。
        // また Android は、プロセスが終わったあとに履歴からアプリを開き直すと、最初に起動したときの Intent (外部のリンク) で作り直すので、
        // 一度開いた外部のリンクは控えておき、履歴からの作り直しでは同じものを開かない (通知の「リストを開く」と、新しく渡されたリンクは毎回開く)
        val linked = linkFrom(intent)
        val welcome = needsWelcome()
        // 初期値が違う旧版から入れ替えたときは、NSFW モードをオフのまま引き継ぐ (一度だけ)。
        // 必ず初回判定 (needsWelcome。保存データが空かを見る) のあと: 先に印を書くと、新規インストールで初回画面が出なくなる
        if (Edition.KEEP_R18_ON_UPGRADE && NHData.obj(this).optInt("nsfwMigrated") != 1) NHData.update(this) { NHData.migrateNsfwDefault(it) }
        // WebView のプロセスが落ちて作り直したとき (NovelClient.onRenderProcessGone): 落ちる前に開いていたページを開き直す
        val crashedAt = prefs.getString(KEY_CRASH_URL, null)?.let(::fixLegacyHost)
        if (crashedAt != null) prefs.edit().remove(KEY_CRASH_URL).apply()
        openedLink = savedInstanceState?.getString(KEY_OPENED_LINK)
        val external = intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND
        // 履歴 (最近使ったアプリ) から開き直したときだけ、前と同じ外部のリンクは開かない。新しく同じリンクを開いたのなら、毎回開く
        val fromHistory = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val alreadyOpened = external && fromHistory && linked != null && linked == prefs.getString(KEY_LAST_EXTERNAL_LINK, null)
        if (linked != null && linked != openedLink && !alreadyOpened) {
            openedLink = linked
            if (external) prefs.edit().putString(KEY_LAST_EXTERNAL_LINK, linked).apply()
            openLink(linked, coldStart = true, direct = intent?.action == Shortcuts.ACTION_RESUME)
        } else if (crashedAt != null) {
            webView.loadUrl(crashedAt)
        } else if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else if (welcome) {
            // 初めての起動: 使うサイトを選んでもらう (選び終わると起動ページへ進む)
            webView.loadUrl(WELCOME_URL)
        } else {
            webView.loadUrl(startUrl())
            // 起動時の自動チェック: 起動ページを開いたまま、画面に出さずに確認する。ログインしているサイトだけが対象なので、
            // 先にログインの控え (logins) を最新にする (無いと、ログインしていない初回の起動でも確認が始まってしまう)
            LoginCookies.sync(this)
            if (autoCheckDue()) collectQuiet(auto = true)
        }
    }

    override fun onResume() {
        super.onResume()
        BgCheck.foreground = true
        OfflineStatus.onChange = { runOnUiThread { refreshSaveStrip() } }
        refreshSaveStrip()
        // 裏での確認で増えた分をバッジに出す
        updateListCount()
        // 連携先へ送る: 前回から設定の間隔がたっていれば送る
        extras.autoIfDue()
    }

    override fun onPause() {
        super.onPause()
        SettingsBackup.save(this) // Android のバックアップの対象は設定の写しだけ。裏に回るときに写す
        BgCheck.foreground = false
        OfflineStatus.onChange = null
    }

    override fun onDestroy() {
        collector?.cancel()
        collector = null
        barAnim?.cancel()
        openSheet?.dismiss() // 開いたままの Dialog は、Activity が終わると WindowLeaked になる
        aids.shutdown()
        if (BgCheck.onDataChanged === bgListener) BgCheck.onDataChanged = null
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /** 起動中にほかのアプリから URL を渡された (singleTask)。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { openLink(it, coldStart = false, direct = intent.action == Shortcuts.ACTION_RESUME) }
    }

    /** 「後で読む」に追加してから戻る先 (#nh-later-back のページの JS が NHStore.laterBack で呼ぶ。1 回だけ使う) */
    private var laterBackUrl: String? = null
    /** いま出ている選択のシート (重ねて出さないため) */
    private var openSheet: BottomSheet? = null

    /**
     * ほかのアプリから渡された URL を開く。作品 (作品トップ・話ページ) なら「後で読む」に追加するか聞く:
     *   追加して、読んでいた作品に戻る / 追加して、この作品を開く / 追加せずに開く
     * 作品名は開くまで分からないので、追加はリンク先のページで ui.js が行う (#nh-later / #nh-later-back)。
     */
    private fun openLink(url: String, coldStart: Boolean, direct: Boolean = false) {
        if (nsfwOn() && SiteUrls.isR18Url(url)) { // NSFW モード中は R18 のリンクを開かず、後で読むに入れておく (サイトへはアクセスしない)
            addR18Later(url)
            if (coldStart) webView.loadUrl(startUrl())
            return
        }
        // direct = 「続きから読む」のショートカット。追加の確認は出さず、そのまま開く (すでに読んでいた作品)
        if (direct || !isWorkUrl(url)) { webView.loadUrl(url); return }
        val prev = readingNow()
        val base = url.substringBefore('#')
        openSheet?.dismiss() // 出ている途中に別のリンクが届いたら、前のシートは閉じる
        val sheet = BottomSheet(this, dp, BottomSheet.Colors(pal.bg, pal.divider, pal.text, pal.subtext, pal.ripple), ACCENT_COLOR).also { openSheet = it }
        sheet.header(siteOfWorkUrl(url), "後で読むに追加しますか？")
        if (prev != null) sheet.button("追加して、読んでいた作品に戻る", prev.second, BottomSheet.Style.PRIMARY) {
            laterBackUrl = prev.first
            webView.loadUrl("$base#nh-later-back")
        }
        sheet.button("追加して、この作品を開く", null, if (prev == null) BottomSheet.Style.PRIMARY else BottomSheet.Style.TONAL) { webView.loadUrl("$base#nh-later") }
        sheet.button("追加せずに開く", null, BottomSheet.Style.PLAIN) { webView.loadUrl(url) }
        // 閉じたら: 起動したばかりなら読んでいた作品 (無ければ起動ページ) を開き、使っている途中ならそのまま
        sheet.show { if (coldStart) webView.loadUrl(prev?.first ?: startUrl()) }
    }

    /**
     * NSFW モード中に外から来た R18 の URL を、サイトへアクセスせず「後で読む」に入れて、下にお知らせを出す。
     * 作品の名前は分からないので作品の ID を仮の名前にする (NSFW モードをオフにして開けば、リストの作品名はそのまま。開いた作品は名前を覚え直す)。
     * R18 の作品の URL でなければ (ランキングなど) 開かずに、お知らせだけ出す。
     */
    private fun addR18Later(url: String) {
        val w = SiteUrls.r18WorkOf(url)
        if (w == null) { showSnack(Edition.R18_OFF_WHY + "、R18 のページは開きません"); return }
        val key = "${w.site}:${w.id}"
        var added = false
        NHData.update(this) { data ->
            val arr = data.optJSONArray("readLater") ?: JSONArray().also { data.put("readLater", it) }
            if ((0 until arr.length()).any { arr.optJSONObject(it)?.optString("key") == key }) return@update
            arr.put(JSONObject().put("key", key).put("site", w.site).put("id", w.id).put("url", w.url).put("title", w.id)
                .put("addedAt", System.currentTimeMillis()).put("done", false).put("kind", "later")
                .also { o -> w.sub?.let { o.put("sub", it) }; if (w.site == "hameln") o.put("r18", true) })
            added = true
        }
        showSnack(if (added) "開いたリンクの作品を「後で読む」に追加しました" else "開いたリンクの作品はすでに「後で読む」にあります")
        updateListCount()
    }

    /** 作品の URL → (タブの名前, 点の色)。シートの見出しに出す。 */
    private fun siteOfWorkUrl(url: String): Pair<String, Int> = when (Uri.parse(url).host) {
        "novel18.syosetu.com" -> "R18" to R18_COLOR
        "syosetu.org" -> tabName("hameln", "ハーメルン") to tabColor("hameln", HAMELN_COLOR)
        "kakuyomu.jp" -> tabName("kakuyomu", "カクヨム") to tabColor("kakuyomu", KAKUYOMU_COLOR)
        else -> tabName("narou", "なろう") to tabColor("narou", NAROU_COLOR)
    }

    private fun isWorkUrl(url: String?) = SiteUrls.isWorkUrl(url)

    /** さっきまで読んでいた作品 (URL, 作品名): 今開いている作品のページ、無ければアプリで最後に読んだ作品 (閲覧履歴)。 */
    private fun readingNow(): Pair<String, String>? {
        val cur = webView.url
        if (isWorkUrl(cur)) return cur!! to webView.title.orEmpty().replace(Regex(" - (ハーメルン|カクヨム|小説家になろう)$"), "")
        val h = dataObj().optJSONArray("history") ?: return null
        for (i in 0 until h.length()) {
            val e = h.optJSONObject(i) ?: continue
            // 保存データは対応サイトのスクリプトも書けるので、開く前に対応サイトの https の URL か確かめる
            if (e.has("appAt") && TrustGate.classify(e.optString("url")).injectable) return e.optString("url") to e.optString("title")
        }
        return null
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
            return Resume.latestUrl(dataObj(), nsfwOn())?.takeIf { SiteUrls.toSupportedLink(it) != null } ?: LIST_URL
        }
        if (intent?.action == OfflineService.ACTION_OPEN_QUEUE) return QUEUE_URL // 保存の通知をタップした
        val raw = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(SiteUrls::firstUrlIn)
            else -> null
        } ?: return null
        SiteUrls.toSupportedLink(raw)?.let { return it }
        if (intent?.action == Intent.ACTION_SEND) Toast.makeText(this, "対応していないサイトの URL です", Toast.LENGTH_SHORT).show()
        return null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putString(KEY_OPENED_LINK, openedLink)
        backupFiles.save(outState)
    }

    // ---- 画面 ----

    /**
     * 上部バー + WebView。カメラや各バーに被らないよう inset を適用する。
     * キーボード (ime) の分も足す: Android 15 以降 (targetSdk 35 以上) は画面の端まで描く作りが強制され、adjustResize ではもう縮まないので、
     * 入れないと、設定の入力欄 (検索など) などがキーボードの下に隠れる。キーボードが出ているあいだは、下の余白がキーボードの高さになる
     */
    private fun buildLayout(): LinearLayout {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
        }
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((2 * dp).toInt(), 0, (2 * dp).toInt(), (4 * dp).toInt())
            visibility = View.GONE
        }
        fillBar()
        // 上部バー・タブの 2 段目・境目の線は 1 つの箱 (header) に入れる。スクロールで箱ごと隠す (下の「上部バーのスクロール連動」)
        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        header.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, (52 * dp).toInt()))
        header.addView(tabRow, LinearLayout.LayoutParams(MATCH_PARENT, (40 * dp).toInt()))
        // 保存中だけ出る進みの帯 (タップで保存キューの画面)。バーと同じ箱なので、スクロールで一緒に隠れる
        saveText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        }
        saveLine = View(this).apply { pivotX = 0f }
        saveStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setOnClickListener { webView.loadUrl(QUEUE_URL) }
            contentDescription = "保存キューを開く"
            background = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).run { getDrawable(0).also { recycle() } }
            addView(saveText, LinearLayout.LayoutParams(MATCH_PARENT, (30 * dp).toInt()))
            addView(saveLine, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(2, (2 * dp).toInt())))
        }
        header.addView(saveStrip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // バーとページの境目の細い線
        divider = View(this)
        header.addView(divider, LinearLayout.LayoutParams(MATCH_PARENT, maxOf(1, (0.5f * dp).toInt())))
        root.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // WebView の上に、下から出るお知らせのバー (showSnack) を重ねる
        val content = FrameLayout(this)
        content.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        findBar = FindBar(this, webView, pal.bg, pal.text, pal.subtext, dp)
        content.addView(findBar.view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        loadBar = View(this).apply { setBackgroundColor(ACCENT_COLOR); visibility = View.GONE; pivotX = 0f; scaleX = 0f }
        content.addView(loadBar, FrameLayout.LayoutParams(MATCH_PARENT, maxOf(2, (2 * dp).toInt()), Gravity.TOP))
        snackView = buildSnack()
        content.addView(snackView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            val m = (12 * dp).toInt(); setMargins(m, 0, m, m)
        })
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        applyBarColors()
        return root
    }

    // ---- 上部バーのスクロール連動 (Chrome のように、下へスクロールすると隠し、上へ戻すと出す) ----
    // サイトのページだけ。アプリ内のページ (リスト・設定など) では隠さない。設定 settings.hideBarOnScroll (既定オン)。
    // 隠すときは header の高さを 0 まで縮め、中身を上へずらして「せり上がる」ように見せる (WebView はその分だけ広がる)。

    private var barHidden = false
    private var barFullHeight = 0
    private var barAnim: ValueAnimator? = null
    /** 下 / 上へ動いた量の積算 (向きが変わると 0 から数え直す) */
    private var scrollAccum = 0f
    /** バーが動いている間は、WebView の広がり縮みで起きるスクロールの知らせを無視する (ページ末尾で出たり消えたりを繰り返さないため) */
    private var ignoreScrollUntil = 0L

    private fun onWebScroll(scrollY: Int, oldY: Int) {
        if (onReaderPage) return
        if (appPageNow || !settingsObj().optBoolean("hideBarOnScroll", true)) {
            if (barHidden) showBarNow()
            return
        }
        if (android.os.SystemClock.uptimeMillis() < ignoreScrollUntil) return
        if (scrollY <= 0) { scrollAccum = 0f; setBarHidden(false); return }
        val dy = (scrollY - oldY).toFloat()
        if (dy > 0) {
            scrollAccum = maxOf(0f, scrollAccum) + dy
            if (!barHidden && scrollAccum > 24 * dp && scrollY > 56 * dp) { scrollAccum = 0f; setBarHidden(true) }
        } else if (dy < 0) {
            scrollAccum = minOf(0f, scrollAccum) + dy
            if (barHidden && scrollAccum < -16 * dp) { scrollAccum = 0f; setBarHidden(false) }
        }
    }

    /** バーを動かして隠す / 出す。 */
    private fun setBarHidden(hide: Boolean) {
        if (hide == barHidden) return
        if (hide) {
            if (header.height <= 0) return
            barFullHeight = header.height
        } else if (header.visibility != View.VISIBLE) {
            header.visibility = View.VISIBLE
        }
        val from = (header.layoutParams.height.takeIf { it >= 0 } ?: header.height)
        val to = if (hide) 0 else barFullHeight
        barHidden = hide
        barAnim?.cancel()
        val ms = 160L
        ignoreScrollUntil = android.os.SystemClock.uptimeMillis() + ms + 120
        barAnim = ValueAnimator.ofInt(from, to).apply {
            duration = ms
            addUpdateListener { setHeaderHeight(it.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (cancelled) return
                    if (hide) header.visibility = View.GONE else showBarNow()
                }
            })
            start()
        }
    }

    /** 箱の高さを h にして、中身を (その分だけ) 上へずらす。 */
    private fun setHeaderHeight(h: Int) {
        val lp = header.layoutParams
        lp.height = h
        header.layoutParams = lp
        for (i in 0 until header.childCount) header.getChildAt(i).translationY = (h - barFullHeight).toFloat()
    }

    /** アニメーションなしですぐ出す (ページを開いたとき・バーを作り直したとき・設定をオフにしたとき)。 */
    private fun showBarNow() {
        barAnim?.cancel()
        barAnim = null
        barHidden = false
        scrollAccum = 0f
        header.visibility = View.VISIBLE
        val lp = header.layoutParams
        lp.height = WRAP_CONTENT
        header.layoutParams = lp
        for (i in 0 until header.childCount) header.getChildAt(i).translationY = 0f
    }

    /** 上部バーの配色。iOS のナビゲーションバー風に、ライトは明るい地 + 細い区切り線、ダークは黒系。 */
    private data class Palette(val bg: Int, val divider: Int, val text: Int, val subtext: Int, val ripple: Int)

    private val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val pal: Palette
        get() = if (isNight) DARK_BAR else LIGHT_BAR

    /**
     * 上部の保存の帯を、キューとサービスの様子に合わせて直す。保存待ち・保存中・Wi-Fi 待ち・一時停止・確認が必要のどれかがあるときだけ出す。
     * 色: 保存中 = 緑、待ち・一時停止 = 橙、確認が必要 = 赤。保存キューの画面では出さない (同じ内容が画面にあるため)。
     */
    private fun refreshSaveStrip() {
        if (!::saveStrip.isInitialized) return
        val jobs = saveQueue.all()
        val live = OfflineStatus.live
        val active = jobs.count { it.status.active }
        val needsCheck = jobs.any { it.status == SaveQueue.Status.NEEDS_CHECK }
        if (onQueuePage || (active == 0 && !needsCheck)) { saveStrip.visibility = View.GONE; return }
        val more = if (active > 1) " ・ 他 ${active - 1} 作品" else ""
        // 題名は長いことがあるので最後に置く (1 行に収まらなければ題名の末尾だけが「…」になり、件数は必ず見える)
        val progress = live?.let { " ${it.ok} / ${it.total} 話$more ・ ${it.title}" }.orEmpty()
        val wifiJobs = jobs.count { it.status == SaveQueue.Status.WAIT_WIFI }
        // 状態の言葉 (色付きの太字) と、そのあとの説明
        val (head, rest, color) = when {
            live != null && live.paused -> Triple("一時停止中", progress, SAVE_WAIT_COLOR)
            live != null && live.waitingWifi -> Triple("Wi-Fi 待ち", progress, SAVE_WAIT_COLOR)
            live != null -> Triple("保存中", progress, ACCENT_COLOR)
            wifiJobs > 0 -> Triple("Wi-Fi 待ち", " $wifiJobs 作品", SAVE_WAIT_COLOR)
            active > 0 -> Triple("順番待ち", " $active 作品", ACCENT_COLOR)
            else -> Triple("確認が必要", " タップして保存キューを開く", SAVE_ALERT_COLOR)
        }
        saveText.text = android.text.SpannableString(head + rest).apply {
            setSpan(android.text.style.ForegroundColorSpan(color), 0, head.length, 0)
            setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, head.length, 0)
        }
        saveText.setTextColor(pal.subtext)
        saveLine.setBackgroundColor(color)
        saveLine.scaleX = if (live != null && live.total > 0) maxOf(live.ok.toFloat() / live.total, 0.02f) else 1f
        saveStrip.visibility = View.VISIBLE
    }

    /** バーの地色・区切り線・ステータスバーの文字色を今のテーマに合わせる。 */
    private fun applyBarColors() {
        root.setBackgroundColor(pal.bg)
        divider.setBackgroundColor(pal.divider)
        refreshSaveStrip()
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    // テーマ (ライト / ダーク) が変わった: manifest の configChanges に uiMode があるので、作り直さずここで塗り直す
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bar.isInitialized) {
            applyBarColors()
            fillBar()
        }
    }

    // ---- お知らせのバー (スナックバー) ----
    // トーストは Android 12 から 2 行までで、押せるボタンも付けられないので、更新の結果などはこれで出す

    private lateinit var snackView: LinearLayout
    private lateinit var snackText: TextView
    private lateinit var snackAction: TextView
    private val hideSnack = Runnable { snackView.animate().alpha(0f).setDuration(180).withEndAction { snackView.visibility = View.GONE }.start() }

    private fun buildSnack(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        elevation = 6 * dp
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
        background = GradientDrawable().apply { setColor(0xF02B2621.toInt()); cornerRadius = 14 * dp }
        isClickable = true
        setOnClickListener { hideSnack.run() } // 押すと閉じる
        snackText = TextView(this@MainActivity).apply { textSize = 14f; setTextColor(0xFFF2EDE7.toInt()); setLineSpacing(0f, 1.15f) }
        addView(snackText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        snackAction = TextView(this@MainActivity).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF5CC58C.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
        }
        addView(snackAction, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    /** 下にお知らせを出す (文字は折り返して全部出す)。action があればボタンを付ける。しばらくすると消える。 */
    private fun showSnack(text: String, action: Pair<String, () -> Unit>? = null) {
        snackView.removeCallbacks(hideSnack)
        snackView.animate().cancel()
        snackText.text = text
        snackAction.visibility = if (action == null) View.GONE else View.VISIBLE
        snackAction.text = action?.first.orEmpty()
        snackAction.setOnClickListener { hideSnack.run(); action?.second?.invoke() }
        snackView.alpha = 0f
        snackView.visibility = View.VISIBLE
        snackView.animate().alpha(1f).setDuration(160).start()
        snackView.postDelayed(hideSnack, if (action == null && text.length < 30) 3500L else 7000L)
    }

    /** 押したときのリップル (mask の形に収まる)。 */
    private fun ripple(mask: GradientDrawable, content: GradientDrawable? = null) =
        RippleDrawable(ColorStateList.valueOf(pal.ripple), content, mask)

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 999 * dp }

    /** 右端などに置くアイコンボタン。badge = true ならリストの件数バッジを重ねる。 */
    private fun iconButton(icon: Int, desc: String, badge: Boolean = false, onClick: (View) -> Unit): View {
        val size = (44 * dp).toInt()
        val frame = FrameLayout(this).apply {
            contentDescription = desc
            background = ripple(pill(Color.WHITE))
            isClickable = true
            setOnClickListener { onClick(it) }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.CENTER_VERTICAL }
        }
        frame.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(pal.text)
        }, FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), Gravity.CENTER))
        if (badge) {
            badgeView = TextView(this).apply {
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = (17 * dp).toInt()
                setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                background = pill(ACCENT_COLOR).apply { setStroke((2 * dp).toInt(), pal.bg) }
            }
            frame.addView(badgeView, FrameLayout.LayoutParams(WRAP_CONTENT, (17 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
                topMargin = (4 * dp).toInt(); marginEnd = (2 * dp).toInt()
            })
        }
        return frame
    }

    /**
     * サイトのタブ: 色の点 + 名前 + ▾。compact (幅が足りないとき) は ▾ と余白を省き、名前の文字を幅に合わせて縮める。
     * tinted なら、タブの背景をサイトの色で薄く塗る。
     * fixedSp > 0 (1 段に固定して幅をそろえたとき) は、全タブ同じ文字の大きさにして、入りきらなければ 2 行まで折り返す。
     */
    private fun siteTab(label: String, dot: Int, weight: Float, compact: Boolean, tinted: Boolean, fixedSp: Float = 0f): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(((if (compact) 3 else 6) * dp).toInt(), 0, ((if (compact) 3 else 6) * dp).toInt(), 0)
            background = ripple(pill(Color.WHITE), if (tinted) pill((dot and 0xFFFFFF) or TINT_ALPHA) else null)
            isClickable = true
            addView(View(this@MainActivity).apply { background = pill(dot) },
                LinearLayout.LayoutParams(((if (compact) 5 else 7) * dp).toInt(), ((if (compact) 5 else 7) * dp).toInt()).apply { marginEnd = ((if (compact) 3 else 6) * dp).toInt() })
            val name = TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(pal.text)
                maxLines = 1
            }
            if (fixedSp > 0) {
                name.textSize = fixedSp
                name.maxLines = 2
                name.ellipsize = TextUtils.TruncateAt.END // 2 行でも入りきらない長い名前は「…」で省く
                name.setLineSpacing(0f, 0.9f)
                addView(name, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            } else if (compact) {
                // 自動縮小は高さが決まっていないと効かないので、タブの高さいっぱいにして縦は中央に置く
                name.gravity = Gravity.CENTER_VERTICAL
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(name, 9, 13, 1, TypedValue.COMPLEX_UNIT_SP)
                addView(name, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            } else {
                addView(name)
                addView(TextView(this@MainActivity).apply {
                    text = "▾"
                    textSize = 10f
                    setTextColor(pal.subtext)
                    setPadding((3 * dp).toInt(), 0, 0, 0)
                })
            }
            layoutParams = LinearLayout.LayoutParams(0, (36 * dp).toInt(), weight).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = (2 * dp).toInt(); marginEnd = (2 * dp).toInt()
            }
        }
    }

    /** タブ 1 つぶんの内容: 名前・点の色・押したときの動作の設定。 */
    private class TabSpec(val key: String, val label: String, val color: Int, val setup: (LinearLayout) -> Unit)

    /** タブ 1 つを縮めずに出すのに要る幅 (px)。siteTab の余白・点・▾ の寸法と合わせる。 */
    private fun tabNeed(label: String, compact: Boolean): Float {
        // 余白 (左右) + 点 + 点の右 + タブの外側の余白 (左右)
        var w = textWidth(label, 13f) + (if (compact) 3 + 3 + 5 + 3 + 2 + 2 else 6 + 6 + 7 + 6 + 2 + 2) * dp
        if (!compact) w += textWidth("▾", 10f, medium = false) + 3 * dp
        return w + 1 // 端数の切り捨てで 1px 足りなくならないように
    }

    /** タブの文字の幅 (px)。 */
    private fun textWidth(text: String, sp: Float, medium: Boolean = true): Float = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
    }.measureText(text)

    /** 1 段に固定して幅をそろえたときの文字の大きさと各タブの文字 (計算は TabLayout)。 */
    private fun oneRowText(labels: List<String>, tabWidth: Float): Pair<Float, List<String>> {
        val room = (tabWidth - (tabNeed("", true) - 1)) * 0.92f // 端数や字間で 1 文字はみ出さないよう、少し余裕をみる
        return TabLayout.oneRowText(labels, room) { text, sp -> textWidth(text, sp) }
    }

    /**
     * 上部バーの中身。設定で非表示にしたサイトは出さない。並びは settings.tabOrder。サイトのヘッダーを消したので、主な行き先はここのメニューから行く。
     * タブは名前の文字の幅を測って、1 段 (更新 / タブ / リスト / 設定) に収まるか決める:
     *   そのまま収まる → 1 段。▾ と余白を削れば収まる → 1 段 (compact)。収まらない → タブだけ 2 段目 (横スクロールにはしない)。
     *   設定の「タブを 1 段にまとめる」(settings.tabsOneRow) がオンなら、いつも 1 段で、タブの幅をそろえる
     *   (いちばん長い名前が入らなければ ▾ と余白を省き、それでも入らなければ文字を縮める)。
     * タブの点の色は settings.siteColors、背景を塗るかは settings.tabsTinted。
     * メニューは開くたびに作る (ランキングの行き先や「このページを…」は今の設定とページで変わる)。
     */
    private fun fillBar() {
        bar.removeAllViews()
        tabRow.removeAllViews()
        val specs = mutableListOf<TabSpec>()
        // R18 は、既定ではバーに出さず「なろう」を素早く 2 回タップしたときだけメニューが出る。
        // 設定で「サイト名のタブ」にするか、なろうを隠しているときは、ノクターンなどを個別のタブで出す
        val r18Sites = SITES.filter { it.r18 && siteOn(it.key) }
        val r18Tabs = r18Sites.isNotEmpty() && (!siteOn("narou") || settingsObj().optString("r18Mode") == "tabs")
        if (siteOn("narou")) specs += TabSpec("narou", tabName("narou", "なろう"), tabColor("narou", NAROU_COLOR)) { t ->
            if (!r18Tabs && r18Sites.isNotEmpty()) t.setOnClickListener(DoubleTapMenu(true, { r18Menu(r18Sites) }, { menuFor("narou", NAROU_MENU) }))
            else t.setOnClickListener { v -> siteMenu(v, menuFor("narou", NAROU_MENU)) }
        }
        if (siteOn("hameln")) specs += TabSpec("hameln", tabName("hameln", "ハーメルン"), tabColor("hameln", HAMELN_COLOR)) { t -> t.setOnClickListener { v -> siteMenu(v, menuFor("hameln", HAMELN_MENU)) } }
        if (siteOn("kakuyomu")) specs += TabSpec("kakuyomu", tabName("kakuyomu", "カクヨム"), tabColor("kakuyomu", KAKUYOMU_COLOR)) { t -> t.setOnClickListener { v -> siteMenu(v, menuFor("kakuyomu", KAKUYOMU_MENU)) } }
        if (r18Tabs) r18Sites.forEach { site ->
            specs += TabSpec(site.key, tabName(site.key, site.label), tabColor(site.key, R18_COLOR)) { t -> t.setOnClickListener { v -> siteMenu(v, r18Menu(listOf(site))) } }
        }
        // 並び (settings.tabOrder)。書いていないサイトは既定の順のまま後ろへ (sortBy は安定)
        val order = settingsObj().optJSONArray("tabOrder")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        specs.sortBy { sp -> order.indexOf(sp.key).let { if (it < 0) Int.MAX_VALUE else it } }

        // 1 段目でタブに使える幅 = 画面の幅 - バーの左右の余白 - アイコン 3 つ
        val avail = resources.configuration.screenWidthDp * dp - 8 * dp - 3 * 44 * dp
        val oneRow = settingsObj().optBoolean("tabsOneRow")
        // 幅をそろえるなら「いちばん長い名前 × タブの数」、そろえないなら「名前ごとの幅の合計」が入るか
        val total = { compact: Boolean ->
            if (oneRow) (specs.maxOfOrNull { tabNeed(it.label, compact) } ?: 0f) * specs.size
            else specs.sumOf { tabNeed(it.label, compact).toDouble() }.toFloat()
        }
        val compact = total(false) > avail
        val twoRows = compact && !oneRow && total(true) > avail
        val tinted = settingsObj().optBoolean("tabsTinted")
        val tabs = if (twoRows) tabRow else bar
        // 左端は「再読み込み」(ブラウザと同じ)。更新の確認は、リスト画面の更新アイコンから
        // 設定 showReload (既定オン)。オフでもメニュー (⋮) から再読み込みできる
        reloadButton = if (settingsObj().optBoolean("showReload", true)) iconButton(R.drawable.ic_refresh, "再読み込み") { webView.reload() }.also { bar.addView(it) } else null
        updateReloadButton()
        if (twoRows) bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) // タブは 2 段目。1 段目は左右のアイコンだけ
        // 幅: 1 段に固定ならそろえる。そうでなければ名前の文字の幅に比例させる (余った幅も、足りない分も、長い名前ほど多く受け持つ)
        val (fixedSp, texts) = if (oneRow && compact) oneRowText(specs.map { it.label }, avail / specs.size) else 0f to specs.map { it.label }
        specs.forEachIndexed { i, sp ->
            val weight = if (oneRow) 1f else tabNeed(sp.label, compact)
            tabs.addView(siteTab(texts[i], sp.color, weight, compact, tinted, fixedSp).also(sp.setup))
        }
        bar.addView(iconButton(R.drawable.ic_book, "リスト", badge = true) { webView.loadUrl(LIST_URL) })
        menuButton = iconButton(R.drawable.ic_more, "メニュー") { if (useGear()) webView.loadUrl(SETTINGS_URL) else showMainMenu(it) }.also { bar.addView(it) }
        updateMenuButton()
        tabRow.visibility = if (twoRows) View.VISIBLE else View.GONE
        if (barHidden) showBarNow() // 段数が変わるかもしれないので、作り直したときは出して測り直す
        updateListCount()
    }

    // ---- ランキングの行き先 (設定の settings.rankings[サイト] = {url, label}) ----

    private fun siteKeyOf(url: String?) = SiteUrls.siteKeyOf(url)

    /** サイトのランキングの行き先。設定があればそれ (そのサイトの URL のときだけ)、無ければ既定。 */
    private fun siteRanking(key: String): String {
        val url = settingsObj().optJSONObject("rankings")?.optJSONObject(key)?.optString("url").orEmpty()
        return if (url.isNotEmpty() && siteKeyOf(url) == key) url else SITES.first { it.key == key }.ranking
    }

    /** サイトのメニュー (タブから開く): 「ランキング」は設定の行き先へ差し替える。項目はどのサイトも同じ (トップページ / ランキング / 作品検索 / しおり・ブックマーク / マイページ)。 */
    private fun menuFor(key: String, base: List<Pair<String, String>>): List<Pair<String, String>> =
        base.map { (label, url) -> label to (if (url == RANKING_SLOT) siteRanking(key) else url) }

    private fun isRankingPage(key: String, url: String?) = SiteUrls.isRankingPage(key, url)

    /** 今開いているのが、どこかのサイトのランキングのページなら、そのサイトのキー。 */
    private fun rankingSiteOf(url: String?): String? = SITES.map { it.key }.firstOrNull { isRankingPage(it, url) }

    /** 今開いている作品を「後で読む」に追加する (ページ側の nhAddCurrent を呼ぶ)。 */
    private fun addCurrentToList() {
        webView.evaluateJavascript("window.nhAddCurrent ? nhAddCurrent() : ''") { r ->
            val msg = when (r?.trim('"')) {
                "added" -> "「後で読む」に追加しました"
                "exists" -> "すでにリストにあります"
                "failed" -> "作品の情報を読み取れなかったので、追加していません"
                else -> "作品のページではありません。作品か話のページで押してください"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 右端のメニュー (⋮。サイトのページだけ。リストなどアプリ内の画面では歯車、設定の画面では何も出さない):
     * Chrome のように、上段に 戻る・進む・再読み込み・先頭へ・末尾へ、下段に作品の追加・ランキングの設定・ページ内検索・共有・設定。
     * 「この作品を後で読むに追加」は作品 / 話のページだけ、「このページをランキングに設定」はランキングのページだけ出す (それ以外では項目ごと消す)。
     * 戻れない・進めない項目は薄く出す。
     */
    private fun showMainMenu(anchor: View) {
        // 作品のページなら、リストにあるか (「後で読むに追加」/「リストから削除」) と端末に保存してあるかを、ページに聞いてから出す
        if (isWorkUrl(webView.url)) {
            webView.evaluateJavascript("window.nhWorkState ? nhWorkState() : null") { r ->
                val work = runCatching { JSONObject(r) }.getOrNull()
                // 自動スクロール中か (メニューの項目名を「止める」にするため)
                webView.evaluateJavascript("window.nhAutoScrollOn ? nhAutoScrollOn() : false") { a -> showMainMenu(anchor, work, a == "true") }
            }
        } else showMainMenu(anchor, null)
    }

    /** work = ページの nhWorkState() ({inList, saved, url})。作品のページでなければ null */
    private fun showMainMenu(anchor: View, work: JSONObject?, autoScrolling: Boolean = false) {
        val inList = work?.optBoolean("inList") == true
        val url = webView.url
        val rankKey = rankingSiteOf(url)
        val onSite = url != null && (url.startsWith("http://") || url.startsWith("https://"))
        val c = pal
        fun action(icon: Int, label: String, enabled: Boolean = true, run: () -> Unit) = MainMenu.Action(icon, label, enabled, run)
        // 下段の項目は、設定 (上部バー → メニューに出す項目) で出すと選んだものだけ (MenuItems)
        val st = settingsObj()
        fun shown(key: String) = MenuItems.on(st, key)
        MainMenu(this, dp, c.bg, c.text, c.subtext, c.divider, c.ripple).show(
            anchor,
            top = listOf(
                action(R.drawable.ic_arrow_back, "戻る", webView.canGoBack()) { webView.goBack() },
                action(R.drawable.ic_arrow_forward, "進む", webView.canGoForward()) { webView.goForward() },
                action(R.drawable.ic_refresh, "再読み込み") { webView.reload() },
                action(R.drawable.ic_vertical_align_top, "先頭へ") { webView.pageUp(true) },
                action(R.drawable.ic_vertical_align_bottom, "末尾へ") { webView.pageDown(true) },
            ),
            groups = listOf(
                // 作品のページ・ランキングのページだけ出す (それ以外のページでは項目ごと出さない)
                buildList {
                    if (shown(MenuItems.LATER)) {
                        if (inList) add(action(R.drawable.ic_close, "この作品をリストから削除") { removeCurrentFromList() })
                        else if (isWorkUrl(url)) add(action(R.drawable.ic_bookmark_add, "この作品を後で読むに追加") { addCurrentToList() })
                    }
                    // オフライン保存 (作品のページを #nh-save 付きで開くと、リストからの保存と同じ画面になる)
                    work?.optString("url")?.takeIf { shown(MenuItems.SAVE) && it.startsWith("https://") }?.let { workUrl ->
                        val label = if (work.optBoolean("saved")) "端末の保存を更新" else "この作品を端末に保存"
                        add(action(R.drawable.ic_download, label) { webView.loadUrl("$workUrl#nh-save") })
                    }
                    // ランキングの行き先がこのページなら「初期設定に戻す」
                    if (rankKey != null && shown(MenuItems.RANKING)) {
                        if (rankingIsHere(rankKey, url)) add(action(R.drawable.ic_trophy, "ランキングの行き先を初期設定に戻す") { resetRanking(rankKey) })
                        else add(action(R.drawable.ic_trophy, "このページをランキングに設定") { setRankingHere(rankKey) })
                    }
                },
                // 読書の補助: 作品のページだけ (本文の読み上げ・ゆっくり下へ進む自動スクロール)
                // 読み上げ中・自動スクロール中は、オフにしていても「止める」を出す (止められなくならないように)
                if (isWorkUrl(url)) buildList {
                    if (shown(MenuItems.READ_ALOUD) || aids.speaking) add(action(R.drawable.ic_read_aloud, if (aids.speaking) "読み上げを止める" else "読み上げ") { aids.toggleReadAloud() })
                    if (shown(MenuItems.AUTO_SCROLL) || autoScrolling) add(action(R.drawable.ic_auto_scroll, if (autoScrolling) "自動スクロールを止める" else "自動スクロール") { aids.toggleAutoScroll() })
                } else emptyList(),
                buildList {
                    if (shown(MenuItems.FIND)) add(action(R.drawable.ic_search, "ページ内を検索") { findBar.show() })
                    if (shown(MenuItems.SHARE)) add(action(R.drawable.ic_share, "共有", onSite) { pageActions.share() })
                    if (shown(MenuItems.COPY_URL)) add(action(R.drawable.ic_copy, "URL をコピー", onSite) { pageActions.copyUrl() })
                    if (shown(MenuItems.OPEN_BROWSER)) add(action(R.drawable.ic_open_browser, "ほかのブラウザで開く", onSite) { pageActions.openInBrowser() })
                },
                buildList {
                    if (shown(MenuItems.DIAGNOSE)) add(action(R.drawable.ic_bug, "このページの表示を診断", onSite) { pageActions.diagnose() })
                    add(action(R.drawable.ic_settings, "設定") { webView.loadUrl(SETTINGS_URL) })
                },
            ).filter { it.isNotEmpty() },
        )
    }

    /** 音量キーでページを送る (設定 volumeKeys。ReadingAids.onKey) */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = aids.onKey(event) || super.dispatchKeyEvent(event)

    override fun onStop() {
        super.onStop()
        aids.stopReading() // 画面を離れたら、読み上げは止める
    }

    /** 歯車にするか: リストなどアプリ内の画面で、設定の画面そのものではないとき (メニューに出す項目が少ないので、設定へ直接行ける)。 */
    private fun useGear() = appPageNow && !onSettingsPage

    /** 右端のボタンを、今の画面に合わせる: サイトのページは ⋮、リストなどアプリ内の画面は歯車、設定の画面は何も出さない (場所は残す)。 */
    private fun updateMenuButton() {
        val gear = useGear()
        menuButton?.visibility = if (onSettingsPage) View.INVISIBLE else View.VISIBLE
        (menuButton as? FrameLayout)?.let { f ->
            f.contentDescription = if (gear) "設定" else "メニュー"
            (f.getChildAt(0) as? ImageView)?.setImageResource(if (gear) R.drawable.ic_settings else R.drawable.ic_more)
        }
    }

    /** 再読み込みは、サイトのページだけ。アプリ内の画面 (リスト・設定など) では、場所は残したまま見えなくする。 */
    private fun updateReloadButton() {
        reloadButton?.visibility = if (appPageNow) View.INVISIBLE else View.VISIBLE
    }

    /** 読み込みの進み具合 (0〜100) を、WebView の上端の線で示す。100 で消す。 */
    private fun showLoadProgress(percent: Int) {
        loadBar.animate().cancel()
        // 設定 showLoadBar (既定オン)。アプリ内の画面 (リスト・設定など) は一瞬で開くので、サイトのページだけ
        if (appPageNow || !settingsObj().optBoolean("showLoadBar", true)) { loadBar.visibility = View.GONE; return }
        if (percent >= 100) {
            loadBar.animate().scaleX(1f).setDuration(120).withEndAction { loadBar.visibility = View.GONE; loadBar.scaleX = 0f }.start()
        } else {
            loadBar.visibility = View.VISIBLE
            loadBar.scaleX = maxOf(percent, 5) / 100f
        }
    }

    /** ノクターンなどの作品検索 (トップは /top/top/ なので、ホストから作る)。 */
    private fun r18Search(key: String) = "https://$key.syosetu.com/search/search/"

    /**
     * R18 のメニュー。サイト名のタブ (1 サイト) と、「なろう」の素早い 2 回タップ (表示中の R18 全部) で同じ項目を出す。
     * 複数サイトのときは「サイト名 + 項目名」で、項目ごとにまとめて並べる。しおり・ブックマークとマイページはなろうと共通。
     */
    private fun r18Menu(sites: List<SiteConfig.Site>): List<Pair<String, String>> {
        val multi = sites.size > 1
        fun named(s: SiteConfig.Site, item: String) = if (multi) "${s.label} $item" else item
        return sites.map { named(it, "トップページ") to it.top } +
            sites.map { named(it, "ランキング") to siteRanking(it.key) } +
            sites.map { named(it, "作品検索") to r18Search(it.key) } +
            // しおり・マイページは、R18 の 3 サイトで 1 つずつ (サイト名を付けて、なろうのマイページと区別する)
            listOf((if (multi) "R18 ブックマーク" else "ブックマーク") to R18_BOOKMARK_PLAIN,
                (if (multi) "R18 マイページ" else "マイページ") to NAROU_MYPAGE)
    }

    /** このページが、設定してあるランキングの行き先そのものか (初期設定のままなら false)。 */
    private fun rankingIsHere(key: String, url: String?): Boolean =
        url != null && settingsObj().optJSONObject("rankings")?.optJSONObject(key) != null && siteRanking(key) == url

    private fun resetRanking(key: String) {
        NHData.update(this) { data -> data.optJSONObject("settings")?.optJSONObject("rankings")?.remove(key) }
        onDataChanged()
        Toast.makeText(this, "ランキングの行き先を初期設定に戻しました", Toast.LENGTH_SHORT).show()
    }

    private fun removeCurrentFromList() {
        webView.evaluateJavascript("window.nhRemoveCurrent ? nhRemoveCurrent() : ''") { r ->
            Toast.makeText(this, if (r?.trim('"') == "removed") "リストから削除しました" else "リストにありませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    /** 今開いているページを、そのサイトのランキングの行き先にする (設定画面では「このページ: タイトル」と出る)。 */
    private fun setRankingHere(key: String) {
        val url = webView.url ?: return
        val title = webView.title.orEmpty().substringBefore(" | ").substringBefore(" - ").take(40)
        NHData.update(this) { data ->
            val st = data.optJSONObject("settings") ?: JSONObject().also { data.put("settings", it) }
            val rk = st.optJSONObject("rankings") ?: JSONObject().also { st.put("rankings", it) }
            rk.put(key, JSONObject().put("url", url).put("label", title.ifEmpty { "このページ" }).put("custom", true))
        }
        onDataChanged()
        Toast.makeText(this, "このページをランキングの行き先にしました", Toast.LENGTH_SHORT).show()
    }

    /** 1 回タップで通常のメニュー、素早く 2 回タップで別のメニュー (2 回目を待つぶん少し遅れて開く)。 */
    private inner class DoubleTapMenu(
        private val hasSecond: Boolean,
        private val second: () -> List<Pair<String, String>>,
        private val first: () -> List<Pair<String, String>>,
    ) : View.OnClickListener {
        private var pending: Runnable? = null

        override fun onClick(v: View) {
            pending?.let {
                v.removeCallbacks(it)
                pending = null
                siteMenu(v, second())
                return
            }
            if (!hasSecond) return siteMenu(v, first()) // 2 回目のメニューが無ければ待たない
            val open = Runnable { pending = null; siteMenu(v, first()) }
            pending = open
            v.postDelayed(open, DOUBLE_TAP_MS)
        }
    }

    /** サイトのタブのメニュー: 記号付きの項目を並べ、選んだ URL を開く。 */
    private fun siteMenu(anchor: View, entries: List<Pair<String, String>>) {
        val c = pal
        val items = entries.map { (label, url) -> MainMenu.Action(menuIconFor(label), label) { webView.loadUrl(url) } }
        MainMenu(this, dp, c.bg, c.text, c.subtext, c.divider, c.ripple).show(anchor, top = emptyList(), groups = listOf(items), alignEnd = false, widthDp = if (entries.size > 5) 320 else 260) // 3 サイト分まとめた R18 の一覧は、名前が長いので広めに
    }

    /** タブのメニューの項目名から記号を選ぶ (「ノクターン ランキング」のように前にサイト名が付いていても、後ろの言葉で決める)。 */
    private fun menuIconFor(label: String): Int = when {
        label.endsWith("トップページ") -> R.drawable.ic_home
        label.endsWith("ランキング") -> R.drawable.ic_trophy
        label.endsWith("作品検索") -> R.drawable.ic_search
        label.endsWith("マイページ") -> R.drawable.ic_person
        else -> R.drawable.ic_book // しおり・ブックマーク・フォロー中の小説
    }

    /** リストのバッジ (読了以外の件数) を保存データから更新する。 */
    private fun updateListCount() {
        val arr = dataObj().optJSONArray("readLater")
        val nsfw = nsfwOn()
        // NSFW モード中は R18 の作品を数えない (リストにも出さないため)
        val n = if (arr == null) 0 else (0 until arr.length()).count {
            val x = arr.optJSONObject(it)
            x?.optBoolean("done") == false && !(nsfw && (x.optString("site") == "narou18" || x.optBoolean("r18")))
        }
        badgeView.text = n.toString()
        badgeView.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    // ---- 保存データ (JS 側の NH.load() と同じ JSON) ----

    /**
     * 上部バーを作るときや、読書位置の保存のたびの onDataChanged で何度も呼ばれ、そのたびに全体を解析すると重いので、
     * 版 (NHData.version) の組にして控える。JS のスレッドからも呼ばれるので、版と中身は 1 組でまとめて差し替える
     * (版は解析の前に読む: 途中で書かれたら、次は読み直しになる)。
     */
    @Volatile private var dataCache: Pair<Long, JSONObject>? = null

    /** 保存データ (読むだけ)。版が変わっていなければ前に解析したものを使い回す。返したものは書き換えない (書き換えは NHData.update で)。 */
    private fun dataObj(): JSONObject {
        val v = NHData.version
        dataCache?.let { (cv, o) -> if (cv == v) return o }
        return NHData.obj(this).also { dataCache = v to it }
    }

    private fun settingsObj(): JSONObject = dataObj().optJSONObject("settings") ?: JSONObject()

    /** 上部バーを作り直すかどうかの判定に使う、「表示するサイト」「タブの名前」ほかの設定をまとめた文字列。 */
    private fun sitesSetting(): String {
        val st = settingsObj()
        return listOf("sites", "siteNames", "siteColors").joinToString("|") { st.optJSONObject(it)?.toString() ?: "" } +
            "|nsfw=" + nsfwOn() +
            "|" + (st.optJSONArray("tabOrder")?.toString() ?: "") +
            "|" + st.optBoolean("tabsOneRow") + "|" + st.optBoolean("tabsTinted") + "|" + st.optString("r18Mode") + "|" + st.optBoolean("showReload", true)
    }

    private fun bgSetting(): String = settingsObj().optJSONObject("bgCheck")?.toString() ?: ""

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) webView.evaluateJavascript("window.nhPlanChanged && nhPlanChanged()", null)
    }

    /** タブに出すサイトの名前。設定で付けた名前 (settings.siteNames) があればそれ、無ければ既定。 */
    private fun tabName(key: String, default: String): String =
        settingsObj().optJSONObject("siteNames")?.optString(key)?.trim()?.take(12)?.takeIf { it.isNotEmpty() } ?: default

    /** タブの点の色。設定で選んだ色 (settings.siteColors、"#RRGGBB") があればそれ、無ければ既定。 */
    private fun tabColor(key: String, default: Int): Int {
        val c = settingsObj().optJSONObject("siteColors")?.optString(key).orEmpty()
        return if (Regex("#[0-9a-fA-F]{6}").matches(c)) Color.parseColor(c) else default
    }

    /** 表示するサイトか。既定値は core.js の SITE_DEFAULT と同じ (全部表示)。 */
    private fun siteOn(key: String): Boolean {
        if (key in R18_KEYS && nsfwOn()) return false // NSFW モード中は R18 のサイトを出さない (設定の「表示するサイト」は変えない)
        return settingsObj().optJSONObject("sites")?.optBoolean(key, true) ?: true
    }

    /** NSFW モードか (settings.nsfw)。オンの間は R18 のサイトへ一切アクセスしない。通信の判定にも使うので、保存データの版ごとに控えた結果を使う。 */
    override fun nsfwOn(): Boolean = NHData.nsfwOn(settingsObj())

    /** 最後に開いていたサイトのページ。今は使わないサイト (表示オフ・18 歳未満の R18) のページなら null。 */
    private fun lastUrl(): String? {
        val url = prefs.getString(KEY_LAST_URL, null)?.let(::fixLegacyHost) ?: return null
        val host = Uri.parse(url).host.orEmpty()
        if (!isSupportedHost(host) || !TrustGate.classify(url).injectable) return null
        val key = siteKeyOf(url)
        val r18 = key in R18_KEYS || host == "novel18.syosetu.com"
        if (r18 && (!settingsObj().optBoolean("adult", true) || nsfwOn())) return null
        return if (key == null || siteOn(key)) url else null
    }

    /** 起動時に最初に開くページ。設定: start = ranking / top / list、startSite = サイト。 */
    override fun startUrl(): String {
        val st = settingsObj()
        val start = st.optString("start", "list")
        if (start == "list") return LIST_URL
        if (start == "last") lastUrl()?.let { return it } // 最後に開いていたページ (無ければ、下のランキング)
        val site = SITES.firstOrNull { it.key == st.optString("startSite", "narou") && siteOn(it.key) }
            ?: SITES.firstOrNull { siteOn(it.key) }
            ?: return LIST_URL
        return if (start == "top") site.top else siteRanking(site.key)
    }

    /**
     * 初めての起動で、まだ使うサイトを選んでいない (welcome.html を出す)。
     * 保存データが空なら初回とみなし、選び終わる (settings.welcomed) まで覚えておく。
     * (初回をリンクから開くと、閲覧履歴などでデータが書かれるので、データの有無だけでは次の起動で判定できない)
     */
    private fun needsWelcome(): Boolean {
        if (settingsObj().optBoolean("welcomed")) {
            if (prefs.contains(KEY_NEEDS_WELCOME)) prefs.edit().remove(KEY_NEEDS_WELCOME).apply()
            return false
        }
        if (NHData.get(this).isEmpty()) prefs.edit().putBoolean(KEY_NEEDS_WELCOME, true).apply()
        return prefs.getBoolean(KEY_NEEDS_WELCOME, false)
    }

    /** 起動時の自動チェックをするか (設定が有効で、前回の確認から一定時間たっている)。 */
    private fun autoCheckDue(): Boolean {
        val st = settingsObj()
        if (!st.optBoolean("autoCheck", true) || !SiteConfig.collectAnyActive(dataObj())) return false
        val hours = st.optInt("autoCheckHours", AUTO_DEFAULT_HOURS).coerceAtLeast(1)
        return System.currentTimeMillis() - dataObj().optLong("lastCollect", 0L) > hours * 3_600_000L
    }

    /** テーマ (auto / light / dark) をアプリ全体に適用する。ダークのときはサイトも自動で暗くなる。 */
    private fun applyTheme(theme: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // ---- 更新の確認 (画面に出さない) ----

    /**
     * 更新の確認を、画面を切り替えずに行う (Collector の WebView で巡回)。終わったら結果だけ出す。
     * auto = 起動時の自動確認: 何も無ければ黙っている。
     */
    private fun collectQuiet(auto: Boolean) {
        if (collector != null) {
            if (!auto) showSnack("更新を確認しています…")
            return
        }
        // 手動の確認は、前回の確認から 10 分は始めない (サイトへ続けてアクセスしないため。CollectCooldown)
        if (!auto) {
            val wait = CollectCooldown.remainingMs(dataObj().optLong("lastCollect", 0L), System.currentTimeMillis())
            if (wait > 0) {
                showSnack("続けて確認できるのは、前回から ${CollectCooldown.MINUTES} 分後です (あと ${CollectCooldown.remainingMinutes(wait)} 分)")
                return
            }
        }
        val startUrl = SiteConfig.collectStartUrl(dataObj(), if (auto) "fg-auto" else "fg")
        if (startUrl == null) {
            if (!auto) showSnack("更新を確認するサイトがありません。設定の「更新の確認」で選べます")
            return
        }
        collector = Collector(this, hostFilter, startUrl, onProgress = { i, n ->
            collectProgress = "$i / $n"
            if (collectingNow) webView.evaluateJavascript("window.nhProgress && nhProgress($i,$n)", null)
        }) { log ->
            collector = null
            showCollecting(false)
            onCollected(log, auto)
        }
        showCollecting(true)
    }

    /** 確認中の表示: リスト画面の更新アイコンを回す (終わったら描き直す)。 */
    private fun showCollecting(on: Boolean) {
        val changed = collectingNow != on
        collecting = on
        if (!on) collectProgress = ""
        if (changed) webView.evaluateJavascript("window.nhCollecting && nhCollecting($on)", null)
    }

    /** 確認の結果を出す: 「N 作品の更新をリストに追加しました」「更新作品はありませんでした」と、確認できなかったサイト。 */
    private fun onCollected(log: String?, auto: Boolean) {
        updateListCount()
        if (log == null) {
            if (!auto) showSnack("更新を確認できませんでした (ページが進みませんでした)", "もう一度" to { collectQuiet(auto = false) })
            return
        }
        // 文とボタンは CollectSummary が決める。開く先の URL はここで決める
        val sum = CollectSummary.of(BgCheck.parse(log), auto, onAppPage()) ?: return
        val action: Pair<String, () -> Unit>? = when (val act = sum.action) {
            is CollectSummary.Action.OpenSite -> "開く" to { webView.loadUrl(COLLECT_PAGES[act.site] ?: LIST_URL) }
            is CollectSummary.Action.Login -> "ログイン" to { webView.loadUrl(LOGIN_PAGES[act.site] ?: LIST_URL) }
            CollectSummary.Action.List -> "リスト" to { webView.loadUrl(LIST_URL) }
            null -> null
        }
        showSnack(sum.text, action)
    }

    // ---- JS の窓口 (AppBridge.kt の StoreBridge / OfflineBridge) から頼まれる操作 ----

    override fun collectNow() = collectQuiet(auto = false)

    override fun userAgent(): String = webView.settings.userAgentString

    /** 外部のリンクの作品を「後で読む」に追加したあと、読んでいた作品に戻る (openLink で選んだときだけ動く。1 回だけ使う)。 */
    override fun finishLaterBack(result: String) {
        val back = laterBackUrl ?: return
        laterBackUrl = null
        val msg = when (result) { "added" -> "「後で読む」に追加しました"; "exists" -> "すでにリストにあります"; else -> "作品のページを開けなかったので、追加していません" }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        // 1 つ前のページがその作品なら戻る (スクロール位置もそのまま)。違えば (起動したばかり・ランキングにいた等) 開く
        val list = webView.copyBackForwardList()
        val prevUrl = if (list.currentIndex > 0) list.getItemAtIndex(list.currentIndex - 1)?.url else null
        if (prevUrl == back) webView.goBack() else webView.loadUrl(back)
    }

    override fun requestNotifyPermission() {
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    override fun clearWebCache() = webView.clearCache(true)

    /** 保存データ (リスト・設定・履歴・読書位置など) とオフライン保存をすべて消し、初回の画面に戻る。ログイン情報は消さない。 */
    override fun wipeAll() {
        NHData.set(this, "")
        SettingsBackup.clear(this) // 設定の写しも消す (残すと、消したあとの再起動や入れ直しで設定が戻る)
        dropOfflineAll()
        prefs.edit().putBoolean(KEY_NEEDS_WELCOME, true).remove(KEY_LAST_EXTERNAL_LINK).apply()
        onDataChanged()
        webView.loadUrl(WELCOME_URL)
    }

    /** 読書画面で、上部バーを隠す / 出す (「スクロールで隠す」がオフなら隠さない) */
    override fun readerBar(hide: Boolean) {
        if (!hide) setBarHidden(false) else if (onReaderPage && settingsObj().optBoolean("hideBarOnScroll", true)) setBarHidden(true)
    }

    /**
     * アプリ内ページの JS を実行する。画面が終わっている・アプリ内ページでなくなっている (サイトのページへ移った) ときは何もしない
     * (アプリ内ページ向けの結果を、サイトのページの同名の関数に渡さないため)。どのスレッドからでも呼べる。
     */
    override fun evalJs(script: String) {
        runOnUiThread {
            if (isDestroyed || !appPageNow) return@runOnUiThread
            webView.evaluateJavascript(script, null)
        }
    }

    /** 開いている画面の window.<fn>(JSON の文字列) を呼ぶ (無ければ何もしない)。どのスレッドからでも呼べる。 */
    override fun emitJs(fn: String, json: JSONObject) {
        evalJs("window.$fn&&$fn(${JSONObject.quote(json.toString())})")
    }

    /** ダウンロードした APK をパッケージインストーラーで開く (確認の画面は Android が出す)。 */
    override fun launchInstaller(apk: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 旧版が控えたアプリ内ページの URL (旧ホスト名) を、今のホスト名に直す。旧版が無い版 (LEGACY_APP_HOST が空) では何もしない */
    private fun fixLegacyHost(url: String): String =
        if (Edition.LEGACY_APP_HOST.isEmpty()) url else url.replace("//" + Edition.LEGACY_APP_HOST + "/", "//" + APP_HOST + "/")

    /** 最後に開いていたサイトのページを控える (起動ページ「最後に開いていたページ」用)。年齢確認の画面や巡回用の印の付いた URL は除く。 */
    private fun recordLastUrl(url: String?) {
        val u = Uri.parse(url.orEmpty())
        if (!isSupportedHost(u.host.orEmpty()) || "#nh-" in url.orEmpty() || "ageauth" in u.path.orEmpty()) return
        prefs.edit().putString(KEY_LAST_URL, url).apply()
    }

    /** 対応サイト (サブドメインも含む) のホストか。 */
    private fun isSupportedHost(host: String) = SiteUrls.isSupportedHost(host)

    /** いま WebView に出ているのがアプリ内ページ (yomikura.local) か (UI スレッドから)。サイトのページから端末側の操作をさせないため。 */
    override fun onAppPage(): Boolean = TrustGate.classify(webView.url).app

    /** 設定画面のボタンから、関係する Android の設定画面へ移る。開けなければアプリ情報へ。 */
    override fun openSystemSettings(kind: String) {
        val pkg = Uri.parse("package:$packageName")
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        val intent = when (kind) {
            // 「デフォルトで開く」(Android 12+)。ここで対応リンクを追加すると、ほかのアプリのリンクがこのアプリで開く
            "links" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg) else appInfo
            else -> appInfo
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(appInfo)
        }
    }

    /** 保存データが変わったとき: バッジ、上部バー (表示サイト)、テーマを反映する。 */
    override fun onDataChanged() {
        updateListCount()
        if (barHidden && !settingsObj().optBoolean("hideBarOnScroll", true)) showBarNow() // 「スクロールで隠す」をオフにしたら、すぐ出す
        val bj = bgSetting()
        if (bj != bgJson) {
            bgJson = bj
            BgCheck.reschedule(this)
            webView.evaluateJavascript("window.nhPlanChanged && nhPlanChanged()", null)
        }
        val sj = sitesSetting()
        if (sj != sitesJson) {
            sitesJson = sj
            fillBar()
        }
        val theme = settingsObj().optString("theme", "auto")
        if (theme != currentTheme) {
            currentTheme = theme
            applyTheme(theme)
            // 開いているページ (主に設定画面) にもすぐ反映する
            webView.postDelayed({ webView.reload() }, 300)
        }
    }

    private inner class NovelClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host
            // 起動して最初のページは、onPageStarted (画面のスレッド) より先にページの JS が動くことがある (インストール直後など、画面のスレッドが混んでいるとき)。
            // そのままだと窓口が「信頼しないページ」として空を返し、リストが空で描かれる。まだ前のページが無いうちだけ、最初のページの URL で先に決める
            if (!pageStarted && request.isForMainFrame) gate = TrustGate.classify(request.url.toString())
            // アプリ内ページ (https://yomikura.local/xxx) は assets/web から、/js/xxx は assets/js から返す
            if (host == APP_HOST) {
                val path = request.url.path.orEmpty()
                // 保存した挿絵 (読む画面が /offline-img/<サイト>/<ID>/<名前> で指す)。アプリ内ページからだけ
                // (サイトのページが <img> で保存した作品の有無を探れないようにする)
                if (path.startsWith("/offline-img/")) {
                    return if (appPageNow) AppAssets.offlineImage(offline, path.removePrefix("/offline-img/")) else AppAssets.forbidden()
                }
                return AppAssets.appAsset(this@MainActivity, path.trimStart('/').ifEmpty { "list.html" })
            }
            // NSFW モード: R18 のサイトへは一切アクセスしない (ページも画像も通信しない)
            if (nsfwOn() && SiteUrls.isR18Url(request.url.toString())) {
                Log.d(TAG, "nsfw blocked $host")
                return if (request.isForMainFrame) AppAssets.nsfwBlockedPage() else AppAssets.forbidden()
            }
            if (hostFilter.shouldBlock(host)) {
                Log.d(TAG, "blocked $host")
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        // intent:// などの外部スキームは無視する
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme
            if (scheme != "http" && scheme != "https") return true
            // NSFW モード: R18 へのリンクは遷移せず、今のページに留まって下に小さく知らせる (専用の画面は出さない)
            if (nsfwOn() && SiteUrls.isR18Url(request.url.toString())) {
                runOnUiThread { showSnack(Edition.R18_OFF_WHY + "開きません") }
                return true
            }
            return false
        }

        // 読み込みに失敗したとき (電波が無い・タイムアウトなど): 英語のエラー画面の代わりに、アプリの画面を出す
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || request.url.host == APP_HOST) return
            val why = error.description?.toString().orEmpty()
            if ("ERR_ABORTED" in why) return // 読み込み中に別のページへ移ったときなど (失敗ではない)
            view.loadUrl("https://$APP_HOST/error.html?u=${Uri.encode(request.url.toString())}&m=${Uri.encode(why)}")
        }

        // WebView の表示用プロセスが落ちた (メモリ不足など): アプリごと落ちないよう、画面を作り直して同じページを開き直す。
        // 続けて落ちるとき (15 秒以内) は、同じページを開き直さず、起動ページから始める
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Diag.w(TAG, "render process gone (crashed=${detail.didCrash()})")
            val now = System.currentTimeMillis()
            val repeated = now - prefs.getLong(KEY_CRASH_AT, 0L) < 15_000L
            val ed = prefs.edit().putLong(KEY_CRASH_AT, now)
            if (repeated) ed.remove(KEY_CRASH_URL) else ed.putString(KEY_CRASH_URL, view.url)
            ed.apply()
            runOnUiThread {
                Toast.makeText(this@MainActivity, "表示が止まったので、開き直します", Toast.LENGTH_LONG).show()
                finish()
                startActivity(Intent(this@MainActivity, MainActivity::class.java))
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            Log.d(TAG, "page $url")
            if (findBar.isShowing) findBar.hide()
            aids.stopReading() // ページが変わったら、読み上げは止める
            gate = TrustGate.classify(url)
            pageStarted = true
            refreshSaveStrip()
            updateReloadButton()
            updateMenuButton()
            if (barHidden) showBarNow() // 新しいページは、バーを出した状態で始める
        }

        /** 読み込み直さない移動 (カクヨムの話から話へ) では onPageStarted が呼ばれないので、パスが変わったらここで読み上げを止める */
        private var historyPath: String? = null
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            val path = url?.let { Uri.parse(it).let { u -> "${u.host}${u.path}" } }
            if (path != historyPath) {
                historyPath = path
                aids.stopReading()
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // 合言葉と画面の部品は、対応サイトの https のページにだけ入れる (アプリ内ページは core.js / app.js を自分で読み込むので入れない)。
            // サーバー側の転送で別のサイトに着いていることもあるので、終わったときの URL でもう一度確かめる
            val page = TrustGate.classify(url)
            if (page != gate) {
                gate = page
                refreshSaveStrip()
                updateReloadButton()
                updateMenuButton()
            }
            if (page.injectable) view.evaluateJavascript(injectedJs, null)
            recordLastUrl(url)
            LoginCookies.sync(this@MainActivity) // サイトでログイン・ログアウトしたあとの状態を写す
        }
    }

    companion object {
        private const val APP_HOST = TrustGate.APP_HOST
        private const val KEY_OPENED_LINK = "nh:openedLink"
        /** 最後に開いた外部のリンク (履歴からの作り直しで、同じリンクを開き直さないため) */
        private const val KEY_LAST_EXTERNAL_LINK = "lastExternalLink"
        private const val KEY_NEEDS_WELCOME = "needsWelcome"
        /** 書き出し待ちの中身を控えるキャッシュのファイル名と、savedInstanceState の印 */
        private const val SAVE_WAIT_COLOR = 0xFFE0A030.toInt()  // 保存の帯: 待ち・一時停止
        private const val SAVE_ALERT_COLOR = 0xFFD04A3A.toInt() // 保存の帯: 確認が必要
        private const val TAG = "Yomikura"
        private const val REQ_NOTIFY = 1
        private const val AUTO_DEFAULT_HOURS = 3 // 自動チェックの最短間隔 (アクセスしすぎを避ける)
        private const val DOUBLE_TAP_MS = 320L
        private const val KEY_LAST_URL = "lastUrl"
        private const val KEY_CRASH_URL = "crashUrl"
        private const val KEY_CRASH_AT = "crashAt"

        // 上部バー: 暖かみのある黒 + 緑のアクセント。サイトは色の点で見分ける
        private val LIGHT_BAR = Palette(0xFFF9F9F9.toInt(), 0xFFC6C6C8.toInt(), 0xFF1C1C1E.toInt(), 0x993C3C43.toInt(), 0x1F000000)
        private val DARK_BAR = Palette(0xFF1C1C1E.toInt(), 0xFF38383A.toInt(), 0xFFF2F2F7.toInt(), 0x99EBEBF5.toInt(), 0x33FFFFFF)
        private const val ACCENT_COLOR = 0xFF2F8F5B.toInt()
        private const val NAROU_COLOR = 0xFF4A8FE0.toInt()
        private const val R18_COLOR = 0xFFD9529A.toInt()
        private const val HAMELN_COLOR = 0xFFE07B3C.toInt()
        private const val KAKUYOMU_COLOR = 0xFF22B3A4.toInt()
        /** タブの背景をサイトの色で塗るときの濃さ (アルファ) */
        private const val TINT_ALPHA = 0x2E000000
    }
}
