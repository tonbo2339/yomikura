package com.tonbo2339.yomikura

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tonbo2339.yomikura.SiteConfig.LIST_URL
import com.tonbo2339.yomikura.SiteConfig.WELCOME_URL
import org.json.JSONObject

/**
 * アプリの唯一の画面。WebView (サイトのページ・アプリ内ページ) と上部バーを載せ、部品をつなぐ役目だけを持つ。
 * 部品ごとの仕事は次のとおり (ここに処理を足さず、該当する部品へ):
 *  - 保存データの読み取り: AppSettings / 起動ページ: StartPage / 外から来たリンク: LinkOpener
 *  - 上部バー: TopBar (タブ SiteTabs・メニュー SiteMenus / PageMenu・保存の帯 SaveStrip・スクロール連動 HeaderScroll) / 読み込みの線: LoadBar / お知らせ: Snack
 *  - WebView の通信と窓口の信頼: NovelClient / PageGate / TrustGate / JS の窓口: StoreBridge / OfflineBridge
 *  - 更新の確認: CollectController / 読書の補助: ReadingAids / 落ちたあとの開き直し: RenderCrash
 */
class MainActivity : AppCompatActivity(), BridgeHost {

    private lateinit var webView: WebView
    private lateinit var root: LinearLayout
    private lateinit var topBar: TopBar
    /** 上部バー (または左右のバー) 以外: ヘッダーの保存の帯 + ページの本体。バーの位置で、root の中の並びが変わる */
    private lateinit var column: LinearLayout
    /** ページ (WebView) とその上の部品の箱。左右のバーを重ねて出すときは、この中に入れる */
    private lateinit var contentFrame: FrameLayout
    /** 左右のバーを重ねて出すときの、ページの箱とバーを入れる重ね箱 */
    private val host by lazy { FrameLayout(this) }
    private lateinit var edgeReveal: EdgeReveal
    /** 全画面のとき、隠した状態バーの代わりに、切り欠きの余白へ出す時計 (settings.fullscreenClock) */
    private var fullscreenKey = ""
    private lateinit var clock: android.widget.TextClock
    /** 時計の後ろに敷く、上から下へ透けていくグラデーション (文字が時計の下を通るとき、読めなくならないように) */
    private lateinit var clockScrim: View
    /** 「端からスワイプで出す」(settings.barEdge)。触りのたびに読まないよう、控えを持つ */
    private var edgeOn = false
    private var lastExclusion: List<Rect> = emptyList()
    private var exclusionRegistered = false
    /** バーを隠している間、バーのある端に出す細い目印 (「ここからスワイプ」) */
    private lateinit var handle: BarHandle
    private lateinit var findBar: FindBar
    private lateinit var hostFilter: HostFilter
    private lateinit var injectedJs: String
    /** 版によってだけある連携 (アプリを開いたときに、時期なら自動で送る) */
    private lateinit var extras: ExtraHooks

    private val prefs by lazy { getSharedPreferences(Edition.PREFS_NAME, Context.MODE_PRIVATE) }
    private val cfg by lazy { AppSettings(this) }
    private val theme by lazy { BarTheme(this).also { t -> t.bgOverride = { cfg.barBg() } } }
    private val gate = PageGate()
    private val snack by lazy { Snack(this, theme.dp) }
    private val loadBar by lazy { LoadBar(this) }
    private val offline by lazy { OfflineStore(java.io.File(filesDir, "offline")) }
    private val saveQueue by lazy { SaveQueue(java.io.File(filesDir, SaveQueue.FILE_NAME)) }
    private val startPage by lazy { StartPage(cfg, prefs, this) }
    private val renderCrash by lazy { RenderCrash(prefs) }
    private val menus by lazy { SiteMenus(theme, cfg, ::navigate) }
    private val ranking by lazy { RankingTarget(this, cfg, { webView }, ::onDataChanged) }
    private val pageActions by lazy { PageActions(this, { webView }, theme.dp) }
    /** 読書の補助 (読み上げ・自動スクロール・音量キー)。読み上げは、画面を離れる・別のページへ移るときに止める */
    private val aids by lazy { ReadingAids(this, { webView }, cfg::settings, { !gate.app && SiteUrls.isWorkUrl(webView.url) }) }
    private val pageMenu by lazy { PageMenu(this, theme, cfg, { webView }, aids, pageActions, ranking, { findBar.show() }, ::navigate) }
    private val links by lazy { LinkOpener(this, theme, cfg, prefs, { webView }, startPage::url, ::navigate, { topBar.updateListCount() }) }
    private val collect by lazy { CollectController(this, cfg, hostFilter, collectUi) }
    /** バックアップのファイルへの書き出し・読み込み (設定の「データの管理」)。registerForActivityResult のため、onCreate より前に作る */
    private val backupFiles = BackupFiles(this, ::evalJs, ::emitJs)
    /** 自分で入れたフォント (設定の「本文の見た目」)。ファイルを選ぶ画面も registerForActivityResult なので、ここで作る */
    private val fonts by lazy { CustomFonts(java.io.File(filesDir, "fonts")) }
    private val fontFiles = FontFiles(this, { fonts }, ::emitJs)

    override val updater: Updater by lazy { Updater(this, Edition.UPDATE_REPO, Edition.UPDATE_NEEDS_LOGIN) }
    override val updateNotice: UpdateNotice by lazy { UpdateNotice(getSharedPreferences(Edition.PREFS_NAME, MODE_PRIVATE)) }
    override val launcherIcon: LauncherIcon by lazy { LauncherIcon(this) }
    override val activity: AppCompatActivity get() = this
    override val appPageNow: Boolean get() = gate.app
    override val trustedPageNow: Boolean get() = gate.trusted
    override val pageUrlNow: String get() = gate.url
    override val collectingNow: Boolean get() = collect.running
    override val collectProgressText: String get() = collect.progress

    private var currentTheme = "auto"
    /** 上部バーを作ったときの「表示するサイト」などの設定。変わったらバーを作り直す。 */
    private var barSignature = ""
    /** 裏での確認の設定 (変わったらアラームを掛け直す) */
    private var bgSignature = ""

    /** 画面の外 (裏の確認・保存のサービス) で保存データが変わった: バッジなどを直し、開いているリストにも描き直してもらう。BgCheck.onDataChanged に登録する (自分のものだけ外すため保持する) */
    private val bgListener: () -> Unit = {
        onDataChanged()
        if (onAppPage()) webView.evaluateJavascript("window.nhDataChanged && nhDataChanged()", null)
    }
    /** 裏での確認で確認画面が出たときの知らせ先 (onDestroy で、自分が登録したものだけ外す) */
    private val bgChallengeListener: (String) -> Unit = { collect.offerOpenSite(it) }

    private val collectUi = object : CollectController.Ui {
        override fun snack(text: String, action: Pair<String, () -> Unit>?) = Messages.show(this@MainActivity, text, action)
        override fun navigate(url: String) = this@MainActivity.navigate(url)
        override fun onAppPage() = this@MainActivity.onAppPage()
        override fun eval(script: String) = webView.evaluateJavascript(script, null)
        override fun listCountChanged() = topBar.updateListCount()
    }

    /** WebView の動き (NovelClient) から、画面の部品を動かす。 */
    private val clientHost = object : NovelClient.Host {
        override fun onR18LinkBlocked() = Messages.show(this@MainActivity, Edition.R18_OFF_WHY + "開きません")
        override fun onPageStarted() {
            if (findBar.isShowing) findBar.hide()
            aids.stopReading() // ページが変わったら、読み上げは止める
            siteBarHidden = false
            siteVerticalPage = false
            val url = webView.url
            expectVertical = expectsSiteVertical(url)
            refreshPageChrome()
            applyFullscreen() // 縦書きの読書ページでは、切り欠きを避ける
            applyBarOverlay() // 縦書きの読書ページでは、上のバーを本文に重ねる
            // 新しいページは、バーを出した状態で始める。ただし縦書きで読むページ (サイトの話ページ・保存した作品。ページ移動・直接開いたときも) は、隠した状態で始める
            // (出すのは、本文のタップと、戻す向きのスクロールだけ)。保存した作品は、「スクロールで隠す」のときだけ (「いつも表示」なら、バーは出したまま)。「端からスワイプで出す」のときは、いつも隠した状態で始める
            val hideAtStart = topBar.onTop && !edgeOn && cfg.hideBarOnScroll() && (expectVertical || offlineVerticalReader())
            if (hideAtStart) { topBar.hideNow(); updateHandle(); pushTopInset() }
            else if (!edgeOn && (topBar.hidden || topBar.partlyHidden)) topBar.showNow().also { updateHandle() } // 途中で止まったバーも出し直す
            if (expectVertical) webView.postDelayed({ giveUpExpectVertical(url) }, EXPECT_VERTICAL_MS)
        }
        override fun onPageKindChanged() = refreshPageChrome()
        override fun onPathChanged() = aids.stopReading()
        override fun onRenderProcessGone(lastUrl: String?) {
            // 続けて落ちるとき (15 秒以内) は、同じページを開き直さず、起動ページから始める (RenderCrash)
            renderCrash.record(lastUrl)
            runOnUiThread {
                Messages.show(this@MainActivity, "表示が止まったので、開き直します", outlives = true)
                finish()
                startActivity(Intent(this@MainActivity, MainActivity::class.java))
            }
        }
        override fun onPageFinished(url: String?) {
            startPage.recordLast(url)
            aids.onPageFinished() // 次の話へ自動で進んだあとなら、続けて読み上げる (設定 autoNextEpisode)
            applyFullscreen()
            LoginCookies.sync(this@MainActivity) // サイトでログイン・ログアウトしたあとの状態を写す
        }
    }

    // ---- 起動・終了 ----

    // 保存済みのテーマは、画面の部品 (Resources) を作る前に適用する。マニフェストで uiMode の変化を自分で受けている (configChanges) ので、
    // onCreate で決めても起動時の画面には効かない (システムがダークで、設定がライトのとき暗いまま出ていた)
    override fun attachBaseContext(newBase: Context) {
        // 入れ直した直後 (保存データが空) なら、Android のバックアップから戻った設定の写しを使う (SettingsBackup)
        SettingsBackup.restoreIfEmpty(newBase)
        currentTheme = AppSettings(newBase).settings().optString("theme", "auto")
        applyTheme(currentTheme)
        super.attachBaseContext(newBase)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Diag.init(this) // エラーの記録 (診断情報に載せる)
        super.onCreate(savedInstanceState)
        MainMenu.resetOpened()
        backupFiles.restore(savedInstanceState)
        launcherIcon.applyPending() // 「後で」で覚えたランチャーアイコンを、この起動で適用する
        // ランチャーの長押しのショートカット (続きから読む・リストを開く)。アイコンを切り替えたあとに出す
        // (ショートカットは、その時に有効な別名に付き、別名を無効にすると消えるため)
        runBackground("Yomikura-shortcuts") { Shortcuts.publish(this) }

        FontSelection.repair(this, fonts) // 設定にあるフォントが端末に無ければ (入れ直したあと) サイト標準に戻す。ページを開く前に
        hostFilter = Edition.hostFilter(this)
        injectedJs = NHData.tokenJs + AppAssets.siteScripts(this)

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView = createWebView()
        barSignature = cfg.barSignature()
        edgeOn = cfg.barEdge()
        applyFullscreen()
        updateHandle()
        bgSignature = cfg.bgSignature()
        setContentView(FrameLayout(this).also { w ->
            w.addView(buildLayout(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            clockScrim = View(this).apply { visibility = View.GONE; isClickable = false }
            w.addView(clockScrim, FrameLayout.LayoutParams(MATCH_PARENT, (56 * theme.dp).toInt(), Gravity.TOP))
            clock = android.widget.TextClock(this).apply { format24Hour = "H:mm"; format12Hour = "H:mm"; textSize = 13f; setTextColor(theme.pal.subtext); visibility = View.GONE }
            w.addView(clock, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { topMargin = (6 * theme.dp).toInt(); marginEnd = (20 * theme.dp).toInt() })
        })
        // 裏での確認: 書き換えを画面 (バッジ・上部バー) に反映し、アラームを掛け直す (アプリの更新後など)
        BgCheck.onDataChanged = bgListener
        BgCheck.onChallenge = bgChallengeListener
        BgCheck.reschedule(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findBar.isShowing) findBar.hide() else goBackInWeb()
            }
        })
        openFirstPage(savedInstanceState)
    }

    /** WebView と窓口 (NHStore・NHDiag・NHOffline)、描画前に入れる JS を用意する。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(StoreBridge(this@MainActivity), "NHStore")
            addJavascriptInterface(DiagBridge(this@MainActivity), "NHDiag")
            addJavascriptInterface(TotpBridge(this@MainActivity), "NHTotp")
            addJavascriptInterface(FontBridge(this@MainActivity, fonts, fontFiles), "NHFonts")
            addJavascriptInterface(OfflineBridge(this@MainActivity, offline, saveQueue), "NHOffline")
            // 版によってだけある連携 (Edition.installExtras)。公開版は何もしない
            extras = Edition.installExtras(this@MainActivity, this, offline, ::emitJs) { gate.app }
            webViewClient = NovelClient(this@MainActivity, gate, cfg, hostFilter, offline, fonts, injectedJs, clientHost)
            // ダウンロードになるリンク (アプリでは保存しない): ページは移らないので、窓口を今のページの扱いに戻す (NovelClient.cancelled と同じ)
            setDownloadListener { _, _, _, _, _ -> gate.set(url) }
            // WebChromeClient が無いと、サイトの確認ダイアログ (「本当に削除しますか？」など) が出ない。進み具合も受け取る
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) =
                    loadBar.update(newProgress, show = !gate.app && cfg.showLoadBar()) // アプリ内の画面 (リスト・設定など) は一瞬で開くので、サイトのページだけ
            }
            setOnScrollChangeListener { _, _, scrollY, _, oldY -> onWebScroll(scrollY, oldY) }
        }
        // ダークテーマのときは、ダーク未対応のサイトも自動で暗くする
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        }
        applyTextZoom(web)
        // ヘッダー / フッターを隠す CSS は、ちらつかないようページ描画前に入れる
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origins = SiteUrls.INJECT_ORIGINS
            WebViewCompat.addDocumentStartJavaScript(
                web, AppAssets.earlyScripts(this), origins
            )
            // ページの読み込み完了 (onPageFinished) が遅れることがあり、そのままだと画面の部品の注入も遅れる。
            // そこで DOM が読めた時点でも注入する (onPageFinished の注入と二重になっても、注入 JS 側の仕組みで二重にならない)
            if (Edition.INJECT_AT_DOM_READY) {
                WebViewCompat.addDocumentStartJavaScript(
                    web,
                    "(function(){var run=function(){" + injectedJs + "\n};" +
                        "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run);else run();})();",
                    origins
                )
            }
        }
        return web
    }

    /**
     * 最初に開くページを決める。優先順: ほかのアプリから渡された URL > 落ちる前のページ > 前回の画面の復元 > 初回の画面 > 起動ページ。
     * (リンクの扱いの細かい決まりは LinkOpener.atStart)
     */
    private fun openFirstPage(saved: Bundle?) {
        val linked = links.atStart(intent, saved)
        val welcome = startPage.needsWelcome()
        // 初期値が違う旧版から入れ替えたときは、NSFW モードをオフのまま引き継ぐ (一度だけ)。
        // 必ず初回判定 (needsWelcome。保存データが空かを見る) のあと: 先に印を書くと、新規インストールで初回画面が出なくなる
        if (Edition.KEEP_R18_ON_UPGRADE && NHData.obj(this).optInt("nsfwMigrated") != 1) NHData.update(this) { NHData.migrateNsfwDefault(it) }
        // WebView のプロセスが落ちて作り直したとき (NovelClient.onRenderProcessGone): 落ちる前に開いていたページを開き直す
        val crashedAt = renderCrash.take()
        if (linked != null) {
            links.open(linked.url, coldStart = true, direct = linked.direct)
        } else if (crashedAt != null) {
            navigate(crashedAt)
        } else if (saved != null && webView.restoreState(saved) != null) {
            // 前の画面を戻した (戻せる履歴が無かったとき = まだ何も開いていなかったときは、下の起動ページへ。白い画面のままにしない)
        } else if (welcome) {
            navigate(WELCOME_URL) // 初めての起動: 使うサイトを選んでもらう (選び終わると起動ページへ進む)
            ReleaseNotes.markSeen(prefs) // 使い始めたばかりなので、変更点は出さない (次の起動でも出さない)
        } else {
            navigate(startPage.url())
            // 起動時の自動チェック: 起動ページを開いたまま、画面に出さずに確認する。ログインしているサイトだけが対象なので、
            // 先にログインの控え (logins) を最新にする (無いと、ログインしていない初回の起動でも確認が始まってしまう)
            LoginCookies.sync(this)
            val ac = cfg.autoCheck()
            DevLog.eventOf(DevLog.Category.CHECK, "launch auto-check", "due" to ac.due, "enabled" to ac.enabled, "sites" to ac.anySite, "sinceMin" to ac.sinceMin, "hours" to ac.hours)
            if (ac.due) collect.start(auto = true)
            // アプリを更新したあとの最初の起動なら、この版の変更点を 1 回だけ出す (リンクから開いた起動などでは出さず、次のふつうの起動に回す)
            ReleaseNotes.takeOnce(this, prefs)?.let { ReleaseNotesDialog.show(this, theme.dp, it) }
        }
    }

    /**
     * 画面を消す・別のアプリから戻ったとき、WebView が背景の色だけの一色のままになることがある (触ると直る)。カクヨムの話ページで起き、なろう・ハーメルンでは出ない。
     * 原因 (Pixel 9 の実機で確かめた。DEV-NOTES の「一色 (真っ黒) になる件」): カクヨムのページは終わらない CSS アニメーション (見えないスピナー) を持つ。
     * 画面を消すと Android が描画の入れ物 (GPU) を捨てて、復帰で作り直すが、Chromium の描画役 (cc) は「見た目が変わっていない」と見て、作り直した入れ物に新しい画面を送らない。
     * 直し方: ページ全体の見た目を一瞬だけ変えて、全面の再描画を強いる。ページの長さに関係なく、スクロールの通知も出さない (20 回中 0 回。何もしないと 9 回)。
     * 効かなかった方法 (同じ実機): View の invalidate / requestLayout・レイヤーの種類の切り替え・WebView.onPause/onResume (どれも Android の側だけの操作で、Chromium に届かない)。
     * 元に戻すのを同じ処理の中で行うと、描画から見て何も変わらず効かないので、数コマあとにする。
     * 根の側の対処は、見えないスピナーの回転を止めること (site/sites.js の kakuyomu.still を early.js が差し込む。ゆすりを切っても 0 回)。
     * これは、ほかのサイトの終わらないアニメーションにも効く保険として残す。
     */
    private fun nudgeRepaint() {
        webView.post {
            webView.evaluateJavascript(
                "(function(){var d=document.documentElement;if(!d)return;var p=d.style.opacity;d.style.opacity='0.999';setTimeout(function(){d.style.opacity=p},$NUDGE_RETURN_MS)})()",
                null,
            )
        }
    }

    /** 文字の大きさ: 設定でオフなら、Android の「文字サイズ」の設定にかかわらず標準の大きさ (100%) にする */
    private fun applyTextZoom(web: WebView) {
        val scale = resources.configuration.fontScale
        // WebView の既定の倍率は、端末の文字サイズそのもの (0.85 倍なら 85)。合わせないときだけ 100 に固定する
        web.settings.textZoom = if (cfg.followSystemTextSize() && scale > 0f) Math.round(scale * 100f) else 100
    }

    /** 最後に画面を離れた時刻 (開発用ログの「復帰」に、離れていた長さを添える) */
    private var pausedAt = 0L

    /** 全画面 (settings.fullscreen): 状態バーとナビゲーションバーを隠し、端からスワイプで一時的に出す。ほかの設定の変わり・復帰・ウィンドウの焦点が戻ったときに呼ぶ */
    private fun applyFullscreen(force: Boolean = false) {
        // 設定を 1 つ変えるたびに呼ばれるので、全画面に関わる設定が変わったときだけ、ウィンドウの属性と余白をやり直す
        // (毎回やり直すと、画面が何度もレイアウトし直されて、ちらつく)。force = システムバーが戻ったときの、隠し直しだけ
        val key = "${cfg.fullscreen()}|${cutoutIgnored()}|${cfg.fullscreenClock()}|${statusOverlayOn()}"
        val changed = key != fullscreenKey
        if (!changed && !force) return
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (cfg.fullscreen()) {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else if (changed) {
            c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        if (!changed) return
        fullscreenKey = key
        // 全画面で「切り欠きを無視」のときは、切り欠きの余白にも内容を広げる。それ以外は、切り欠きを避ける
        val edgeToEdge = cutoutIgnored()
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = when {
                !edgeToEdge -> android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                Build.VERSION.SDK_INT >= 30 -> android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else -> android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.attributes = lp
        }
        if (::root.isInitialized) ViewCompat.requestApplyInsets(root) // 時計の分の余白を取り直す。取り直しの中で updateClock が呼ばれる
        updateClock()
    }

    /** いま縦書きで読んでいるか: 保存した作品 (offline.html の読書ページで、設定 vertical がオン) か、サイトの話ページの縦書き */
    private fun verticalReader(): Boolean = siteVerticalPage || expectVertical || offlineVerticalReader()

    /** 保存した作品の読書ページ (offline.html の話。目次は ep が無い。TrustGate の reader と同じ) を、縦書きで開いているか */
    private fun offlineVerticalReader(): Boolean {
        val u = webView.url ?: return false
        return cfg.settings().optBoolean("vertical") && u.contains("/offline.html") && u.contains("ep=")
    }

    /**
     * サイトの話ページを、縦書きで開く見込みか (設定 siteVertical と、URL が話ページ)。ページが縦書きにして知らせてくる (siteVerticalPage) のは、読み込んでしばらくあとなので、
     * それまでの間も縦書きの扱い (上のバーを出さない・重ねる・切り欠きを避ける) にして、ページが変わるたびにバーが一瞬出るのを防ぐ
     */
    private fun expectsSiteVertical(url: String?) = !gate.app && cfg.settings().optBoolean("siteVertical") && SiteUrls.isEpisodeUrl(url)

    /** 縦書きの見込みで始めたページ。縦書きにならなかったとき (確認画面・読み込みの失敗など) は、一定時間のあと [giveUpExpectVertical] で戻す */
    private var expectVertical = false

    private fun giveUpExpectVertical(url: String?) {
        if (!expectVertical || siteVerticalPage || webView.url != url) return
        expectVertical = false
        applyFullscreen()
        applyBarOverlay()
        if (!edgeOn && topBar.hidden) topBar.showNow().also { updateHandle() } // 縦書きにならないページで、バーが出せなくならないように
        pushTopInset()
    }

    /**
     * 全画面にせず、状態バー (時計・電池・通知) を残したまま、ページをその下まで広げるか (設定 statusOverlay)。サイトのページだけ
     * (アプリの画面は、見出しが状態バーに被るので広げない)。縦書きで読むページも、設定に関わらず広げない
     * (縦書きは 1 行目が画面の上端から始まるので、状態バー (カメラの有無に関わらず) の下に置かないと、文字が隠れて読めない)。広げるときは、状態バーの後ろに、ぼかしの帯 (clockScrim) を敷く
     */
    private fun statusOverlayOn() = cfg.statusOverlay() && !gate.app && !verticalReader()

    /**
     * 全画面で、切り欠き (カメラ) の余白にも内容を広げるか。設定は「無視」でも、次のページでは避ける:
     * 縦書きの読書ページ (縦書きは右上から読み始めるので、カメラや時計に一行目が重なる)、アプリ内のページ (設定・リストなど。文字や見出しがカメラに被る。上のバーが無い左右のバーのときも)
     */
    private fun cutoutIgnored() = cfg.fullscreen() && cfg.fullscreenCutout() == "ignore" && !verticalReader() && !gate.app

    /** 時計の後ろのぼかしを敷くか。カメラが上にあるとき (insets の切り欠きの上端が 0 より大きい) だけ。上にないなら、時計の分の空きを取る */
    private var clockOverPage = false

    /** 状態バーを残して広げるときの、状態バーの高さ (px。root の insets から)。ぼかしの帯の高さの元 */
    private var statusScrimPx = 0

    /** 時計は、全画面で、時計を出す設定のときだけ。余白が無い端末は、時計の分の細い余白を取る (root の insets) */
    private fun updateClock() {
        if (::clockScrim.isInitialized) {
            val bg = theme.pal.bg
            val rgb = bg and 0xFFFFFF
            val over = statusOverlayOn() && statusScrimPx > 0
            // 状態バーを残すときは、状態バーの高さ + 少しのぼかし。時計のときは、これまでどおりの高さ
            val h = if (over) statusScrimPx + (STATUS_FADE_DP * theme.dp).toInt() else (56 * theme.dp).toInt()
            clockScrim.background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(rgb or 0xE6000000.toInt(), rgb or 0x80000000.toInt(), rgb),
            )
            clockScrim.visibility = if ((cfg.fullscreenClock() && clockOverPage) || over) View.VISIBLE else View.GONE
            (clockScrim.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> if (lp.height != h) { lp.height = h; clockScrim.layoutParams = lp } }
        }
        if (!::clock.isInitialized || !::root.isInitialized) return
        clock.setTextColor(theme.pal.subtext)
        (clock.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val start = cfg.fullscreenClockSide() == "start"
            val g = Gravity.TOP or (if (start) Gravity.START else Gravity.END)
            // 画面の角が丸い (または欠けている) 端末では、角の丸みの内側へ寄せる (API 31 以上で、角の半径が分かる)
            val dp = theme.dp
            var radius = 0
            if (Build.VERSION.SDK_INT >= 31) {
                val ins = window.decorView.rootWindowInsets
                val corner = ins?.getRoundedCorner(if (start) android.view.RoundedCorner.POSITION_TOP_LEFT else android.view.RoundedCorner.POSITION_TOP_RIGHT)
                radius = corner?.radius ?: 0
            }
            val side = maxOf((20 * dp).toInt(), (radius * 0.9).toInt())
            val top = maxOf((6 * dp).toInt(), (radius * 0.28).toInt())
            if (lp.gravity != g || lp.topMargin != top || (if (start) lp.marginStart else lp.marginEnd) != side) {
                lp.gravity = g; lp.topMargin = top
                lp.marginStart = if (start) side else 0; lp.marginEnd = if (start) 0 else side
                clock.layoutParams = lp
            }
        }
        clock.post { clock.visibility = if (cfg.fullscreen() && cfg.fullscreenClock()) View.VISIBLE else View.GONE }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen(force = true) // 通知やメニューを出したあとなど、システムバーが戻ってしまったとき、また隠す
    }

    override fun onResume() {
        super.onResume()
        applyTextZoom(webView)
        val repaint = cfg.repaintOnResume()
        DevLog.eventOf(DevLog.Category.SCREEN, "resume", "pausedSec" to if (pausedAt > 0L) (System.currentTimeMillis() - pausedAt) / 1000L else null, "repaint" to repaint, "host" to webView.url?.let { android.net.Uri.parse(it).host })
        if (repaint) nudgeRepaint()
        RenderProbe.check(this, webView, "resume+1.5s", 1_500L) // 表示が一色のまま残っていないか (真っ黒の件)。開発用ログがオンのときだけ
        RenderProbe.check(this, webView, "resume+6s", 6_000L)
        BgCheck.foreground = true
        OfflineStatus.onChange = { runOnUiThread { refreshSaveStrip() } }
        OfflineService.resumeIfPending(this, saveQueue) // 途中で止まった保存を続きから
        refreshSaveStrip()
        topBar.updateListCount() // 裏での確認で増えた分をバッジに出す
        topBar.updateButtons() // 設定でカードを見た・印をオフにした直後の、新しい版の点
        checkUpdateNotice()
        extras.autoIfDue() // 連携先へ送る: 前回から設定の間隔がたっていれば送る
    }

    override fun onPause() {
        super.onPause()
        SettingsBackup.save(this) // Android のバックアップの対象は設定の写しだけ。裏に回るときに写す
        pausedAt = System.currentTimeMillis()
        DevLog.eventOf(DevLog.Category.SCREEN, "pause")
        BgCheck.foreground = false
        OfflineStatus.onChange = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DevLog.eventOf(DevLog.Category.SCREEN, "trimMemory", "level" to level)
    }

    override fun onStop() {
        super.onStop()
        DevLog.eventOf(DevLog.Category.SCREEN, "stop")
        // 画面を離れても読み上げは続ける (前景サービスの通知・ロック画面から止められる)。通知を出せないとき (許可が無い) は、止められなくなるので止める
        if (!Notices.allowed(this)) aids.stopReading()
    }

    override fun onDestroy() {
        Messages.detach(snack)
        MainMenu.dismissOpen() // 開いたままのメニューの窓を残さない (static に持つので、Activity ごと残ってしまう)
        MainMenu.onOpenChanged = null
        collect.stop()
        topBar.scroll.cancel()
        links.dismissSheet()
        aids.shutdown()
        if (BgCheck.onDataChanged === bgListener) BgCheck.onDataChanged = null
        if (BgCheck.onChallenge === bgChallengeListener) BgCheck.onChallenge = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /** 起動中にほかのアプリから URL を渡された (singleTask)。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        links.onNewIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        backupFiles.save(outState)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = aids.onKey(event) || super.dispatchKeyEvent(event) // 音量キーでページを送る (設定 volumeKeys)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) webView.evaluateJavascript("window.nhPlanChanged && nhPlanChanged()", null)
    }

    // テーマ (ライト / ダーク) が変わった: manifest の configChanges に uiMode があるので、作り直さずここで塗り直す
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        MainMenu.dismissOpen()
        if (::topBar.isInitialized) {
            topBar.scroll.onResized() // 回転・窓の大きさの変更 (orientation|screenSize もここへ来る)
            topBar.applyColors(root, window, gate.queue)
            topBar.fill()
            updateHandle() // 画面の高さが変わると、小さなボタンの縦の位置 (px) も置き直す
            updateClock() // 時計と、その後ろのグラデーションの色も、テーマに合わせる
        }
        if (::findBar.isInitialized) findBar.applyColors(theme.pal.bg, theme.pal.text, theme.pal.subtext) // ページ内検索のバーも塗り直す
    }

    // ---- 画面 ----

    /**
     * 上部バー + WebView。カメラや各バーに被らないよう inset を適用する。
     * キーボード (ime) の分も足す: Android 15 以降 (targetSdk 35 以上) は画面の端まで描く作りが強制され、adjustResize ではもう縮まないので、
     * 入れないと、設定の入力欄 (検索など) などがキーボードの下に隠れる。キーボードが出ているあいだは、下の余白がキーボードの高さになる
     */
    private fun buildLayout(): LinearLayout {
        root = LinearLayout(this)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // 全画面で「切り欠きを無視」のときは、切り欠きの余白にも内容を広げる。時計を出すときは、時計の分の細い余白だけ取る。「避ける」と全画面でないときは、切り欠きを避ける
            val edgeToEdge = cutoutIgnored()
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or (if (edgeToEdge) 0 else WindowInsetsCompat.Type.displayCutout()))
            val cutoutTop = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            // 時計を出すとき、時計の分の細い余白を上に取る (ページに被らないように) のは、切り欠きを避けるときと、カメラが上にないとき (設定に関わらず)。
            // カメラが上にあって切り欠きを無視するときは、ページを時計の下まで広げ、時計の後ろに、ぼかしのグラデーション (clockScrim) を敷く
            val clockOn = cfg.fullscreen() && cfg.fullscreenClock()
            val coverPage = edgeToEdge && cutoutTop > 0
            val clockRoom = if (clockOn && !coverPage) (CLOCK_ROOM_DP * theme.dp).toInt() else 0
            // 状態バーを残して広げるとき: ページは画面の一番上から始め、バーだけを状態バーの下へ下げる (状態バーの高さ = 状態バーと切り欠きの大きいほう)
            val statusOverlay = statusOverlayOn()
            val statusTop = if (statusOverlay) insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top else 0
            statusScrimPx = statusTop
            v.setPadding(b.left, if (statusOverlay) 0 else maxOf(b.top, clockRoom), b.right, b.bottom)
            clockOverPage = clockOn && coverPage
            // ページが画面の一番上まで広がるとき (全画面で切り欠きを無視) は、バーだけをカメラ (切り欠き) と時計の下へ下げる
            val clockTop = if (clockOverPage) (CLOCK_ROOM_DP * theme.dp).toInt() else 0
            if (::topBar.isInitialized) topBar.setTopInset(when { statusOverlay -> statusTop; coverPage -> maxOf(cutoutTop, clockTop); else -> 0 })
            updateClock()
            insets
        }
        topBar = TopBar(this, theme, cfg, saveQueue, menus, ::navigate, { webView.reload() }, pageMenu::show, { gate.app }, { gate.settings }, { updateNotice.dotVisible(cfg.updateNotice(), BuildConfig.VERSION_NAME) })
        topBar.fill()
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(topBar.header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // WebView の上に、ページ内検索・読み込みの線・下から出るお知らせのバーを重ねる
        val dp = theme.dp
        val content = object : FrameLayout(this) {
            // 指の動きは、WebView に渡す前に、上のバーへ先に使わせる (HeaderScroll.onTouch)
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (::topBar.isInitialized) topBar.scroll.onTouch(ev, barFollowsScroll())
                return super.dispatchTouchEvent(ev)
            }
        }
        contentFrame = content
        content.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        findBar = FindBar(this, webView, theme.pal.bg, theme.pal.text, theme.pal.subtext, dp)
        content.addView(findBar.view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        findBar.onToggle = ::layoutFindArea
        topBar.header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutFindArea() } // 上のバーの出し入れで、検索バーの位置も合わせる
        content.addView(loadBar.view, FrameLayout.LayoutParams(MATCH_PARENT, maxOf(2, (2 * dp).toInt()), Gravity.TOP))
        Messages.attach(snack)
        content.addView(snack.view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            val m = (12 * dp).toInt(); setMargins(m, 0, m, m)
        })
        handle = BarHandle(this, theme, cfg, content, { topBar.hidden && (edgeOn || !topBar.onTop) }) { setBar(true) }
        content.addView(handle.view, FrameLayout.LayoutParams(1, 1))
        column.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        edgeReveal = EdgeReveal(dp) {
            if (edgeOn || !topBar.onTop) setBar(true) // 戻すのは、ページを触ったとき・下へスクロールしたとき (時間では戻さない)
        }
        arrangeBar()
        applyEdgeMode()
        topBar.applyColors(root, window, gate.queue)
        return root
    }

    /** バーの位置 (settings.barPos: 上・左・右) に合わせて、root の中身を並べる。上なら縦に [column] だけ、左右なら横に縦のバーと [column] を並べる。 */
    private fun arrangeBar() {
        root.removeAllViews()
        host.removeAllViews()
        // 並べ替えるたびに、前の置き場所から外す
        for (v in listOf<View>(column, topBar.railBox)) (v.parent as? ViewGroup)?.removeView(v)
        val pos = cfg.barPos()
        val overlay = pos != "top" && cfg.barEdge() // 左右のバーを隠しておく設定のときは、ページの上に半透明で重ねる (出し入れでページの幅が変わらない)
        root.orientation = if (pos == "top" || overlay) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val side = if (pos == "left") Gravity.START else Gravity.END
        if (!overlay) topBar.railCompact = false
        when {
            pos == "top" -> root.addView(column, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            overlay -> {
                // ページの箱と、その上に重ねるバーを、1 つの重ね箱 (host) に入れる (バーはページの幅を変えない)
                host.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                // 縦の長さ: スマホはボタンの分だけ (縦の中ほどに置く)。タブレットなどは画面いっぱい (settings.railHeight)
                val compact = cfg.railCompact(resources.configuration.smallestScreenWidthDp)
                topBar.railCompact = compact
                // ボタンの分だけのときは、ページの上に浮かぶ小さなカードにして、目印のボタンの高さに合わせて置く ([placeRail])
                val lp = FrameLayout.LayoutParams(WRAP_CONTENT, if (compact) WRAP_CONTENT else MATCH_PARENT, if (compact) side or Gravity.TOP else side)
                if (compact) { val m = (8 * theme.dp).toInt(); lp.marginStart = m; lp.marginEnd = m; lp.topMargin = m }
                host.addView(topBar.railBox, lp)
                if (!railPlaceHooked) {
                    railPlaceHooked = true
                    host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeRail() }
                    topBar.railBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeRail() }
                }
                topBar.railBox.post { placeRail() }
                root.addView(host, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            pos == "left" -> { root.addView(topBar.railBox, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT)); root.addView(column, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)) }
            else -> { root.addView(column, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)); root.addView(topBar.railBox, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT)) }
        }
        if (exclusionRegistered) return
        exclusionRegistered = true
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGestureExclusion() }
    }

    /**
     * 左右のバーの側の画面の端は、OS の「戻る」ジェスチャーと重なるので、目印 (棒・小さなボタン) の高さを中心に、OS が認める 200dp までをジェスチャーから外し、
     * 端からのスワイプを受け取れるようにする。目印の縦の位置 (settings.barButtonPos) が変わったときも呼ぶ (onDataChanged)。計算は HandleLayout。
     */
    private fun updateGestureExclusion() {
        if (!::root.isInitialized || !::contentFrame.isInitialized || root.height == 0) return
        val length = (HandleLayout.lengthDp(cfg.barHidden() == "button") * theme.dp).toInt()
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val contentLoc = IntArray(2).also { contentFrame.getLocationInWindow(it) }
        val centerY = (contentLoc[1] - rootLoc[1]) + HandleLayout.top(cfg.barButtonPos(), contentFrame.height, length) + length / 2
        val zone = HandleLayout.exclusion(cfg.barPos(), root.width, root.height, centerY, theme.dp)
        val rects = listOfNotNull(zone?.let { Rect(it.left, it.top, it.right, it.bottom) })
        if (rects != lastExclusion) { lastExclusion = rects; ViewCompat.setSystemGestureExclusionRects(root, rects) }
    }

    private var railPlaceHooked = false

    /**
     * 縦書きで読んでいる間 (上のバーで、端から出す設定でないとき) は、上のバーを本文に重ねて出し入れする。
     * バーが出入りしても本文 (WebView) の大きさが変わらないので、縦書きの文字の長さが変わらない。ページの種類・設定が変わるたびに呼ぶ
     */
    private fun applyBarOverlay() {
        if (!::topBar.isInitialized || !::contentFrame.isInitialized || !::column.isInitialized) return
        // 「いつも表示」のバーは出したまま (動かないので、重ねる必要がない。本文はバーの下から始まる)。「スクロールで隠す」のときだけ重ねる
        val on = verticalReader() && topBar.onTop && !edgeOn && cfg.hideBarOnScroll()
        // 横書きでは、バーの高さを毎フレーム縮めると WebView も毎フレーム変わって、ガクガクする。本文の大きさを変えずにずらす (HeaderScroll.setPush)
        val push = !on && topBar.onTop && !edgeOn && cfg.hideBarOnScroll() && !gate.app
        // ずらす方式を先に切り替える (setPush は本文の箱の大きさを元に戻すので、重ねる方式を先に作ると、作った形を壊してしまう)
        topBar.scroll.setPush(if (push) contentFrame else null, if (push) column else null)
        topBar.scroll.setOverlay(if (on) contentFrame else null, if (on) column else null, theme.pal.bg)
        pushTopInset()
    }

    /** 本文に重ねている上のバーの高さ (px。重ねていない・隠れているときは 0)。検索バーと本文の位置の元 */
    private var barOverPx = 0

    /**
     * ページ内検索のバーは、本文の箱の上端にあるので、重ねた上のバーの下へ下げる (そのままだと、バーに隠れる)。
     * 検索バーが出ている間は、本文 (WebView) も検索バーの下へ下げる (重ねたままだと、縦書きの 1 行目の頭や、一致した文字が、検索バーに隠れる)
     */
    private fun layoutFindArea() {
        if (!::findBar.isInitialized || !::webView.isInitialized) return
        // 重ねない上のバーが縮んで隠れると、本文の箱の上端が画面の一番上になる。状態バーを残す設定では、ページが状態バーの下まで広がるので、
        // 検索バーが状態バーに潜らないよう (薄く見えて、押せない)、足りない分だけ下げる (バーの高さが変わるたびに呼ぶ)
        val headerH = if (topBar.header.visibility == View.GONE) 0 else topBar.header.height
        val top = if (topBar.scroll.overlaying) barOverPx else maxOf(barOverPx, statusScrimPx - headerH)
        (findBar.view.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.topMargin != top) { lp.topMargin = top; findBar.view.layoutParams = lp }
        }
        val want = if (findBar.isShowing) top + (FindBar.HEIGHT_DP * theme.dp).toInt() else 0
        (webView.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.topMargin != want) { lp.topMargin = want; webView.layoutParams = lp }
        }
    }

    /**
     * 重ねている上のバーの高さを、ページの CSS 変数 --nh-top-inset へ知らせる。ページ自身の上の列 (カクヨムの目次・ハンバーガー、保存した作品の上の帯) を、
     * バーの下へ下げるため (バーが出ている間だけ。隠れている間・重ねていないときは 0)。ページが変わると変数は消えるので、バーを出し入れするたびに送る
     */
    private fun pushTopInset() {
        if (!::webView.isInitialized || !::topBar.isInitialized) return
        val px = if (topBar.scroll.overlaying && !topBar.hidden) topBar.header.height else 0
        barOverPx = px
        layoutFindArea()
        val css = if (px > 0) "${Math.round(px / theme.dp)}px" else "0px"
        webView.evaluateJavascript("document.documentElement&&document.documentElement.style.setProperty('--nh-top-inset','$css')", null)
    }

    /** サイトの話ページ (縦書き) で、上部バーを隠している間 (ui/ui-vertical.js が頼む)。ページが変わったら戻す */
    private var siteBarHidden = false
    private var siteVerticalPage = false // サイトの話ページを縦書きで表示している (JS の siteBar の呼び出しで知る。ページが変わるまで)

    /** 通知の許可の説明ダイアログを出している間 (続けて保存が始まっても、二重に出さない) */
    private var notifyDialogShowing = false

    /** ボタンの分だけの左右のバー (浮かぶカード) の縦の位置を、目印 (棒・小さなボタン) の高さに合わせる。画面の外へはみ出さない */
    private fun placeRail() {
        val rail = topBar.railBox
        val lp = rail.layoutParams as? FrameLayout.LayoutParams ?: return
        if (!topBar.railCompact || !topBar.overlayRail) { if (rail.translationY != 0f) rail.translationY = 0f; return } // 画面いっぱい・ふつうに並べるときは、前の縦のずれを戻す
        val total = host.height
        val h = rail.height
        val m = lp.topMargin // 上の余白は固定 (8dp)。縦の位置は translationY で動かす (余白で動かすと、画面の高さが変わったとき、置く所が無くなって高さ 0 に測られ、戻れなくなる)
        if (total == 0 || h == 0) return
        val size = (HandleLayout.lengthDp(cfg.barHidden() == "button") * theme.dp).toInt() // 目印 (小さなボタン 44dp・棒 64dp) の長さ
        // 目印は、ページの箱 (contentFrame。上の保存の帯の下) の高さで置かれる。重ね箱 (host) との上端のずれ分を足して、同じ高さに合わせる
        val hostLoc = IntArray(2).also { host.getLocationInWindow(it) }
        val contentLoc = IntArray(2).also { contentFrame.getLocationInWindow(it) }
        val center = (contentLoc[1] - hostLoc[1]) + HandleLayout.top(cfg.barButtonPos(), contentFrame.height, size) + size / 2
        // 全画面で切り欠きを無視するときは、ページが画面の上端まで広がるので、カメラ・時計の下より上へは置かない
        val minTop = m + topBar.topInsetPx
        val top = (center - h / 2).coerceIn(minTop, maxOf(minTop, total - h - m))
        val ty = (top - m).toFloat()
        if (rail.translationY != ty) rail.translationY = ty
    }

    /**
     * 「端からスワイプで出す」(settings.barEdge) の状態を、いまの画面に合わせる。オンなら、サイトのページもアプリ内のページ (リスト・設定) も隠しておく
     * (目印の棒かボタン・端のスワイプで出せる)。ページを開いた・バーの設定が変わったときに呼ぶ (高さが決まってから隠せるよう post する)。
     */
    private fun applyEdgeMode() {
        root.post { if (edgeOn) topBar.hideNow() else if (topBar.hidden) topBar.showNow(); updateHandle() }
    }


    /** バーを出す / 隠す (アニメーションつき) と、端の目印の更新。 */
    private fun setBar(shown: Boolean) {
        if (!shown && MainMenu.opened > 0) return // メニューを開いている間は、バーを隠さない
        if (shown) placeRail() // ボタンをドラッグして動かした位置は、保存データの変更の知らせを通らないので、出すたびに、ボタンの高さへ置き直す
        topBar.setShown(shown)
        updateHandle()
        pushTopInset()
    }

    /** 端の目印 (BarHandle) を、いまの設定とバーの状態に合わせる。左右のバーは、スクロールで隠れたときも目印を出す */
    private fun updateHandle() {
        if (::handle.isInitialized) handle.update()
        hintHiddenBar()
    }

    /**
     * 「普段は隠しておく」で、サイトのページでバーを隠したとき、初回だけ、出し方を知らせる (隠れていて、気づきにくいため)。
     * 小さなボタンのときは「押す・なぞって動かせる」、棒のときは「端からスワイプ」。一度出したら、もう出さない
     */
    private fun hintHiddenBar() {
        // onCreate の途中 (topBar・handle がまだ無い) でも呼ばれるので、まず作られているかを見る
        if (!edgeOn || !::topBar.isInitialized || !::handle.isInitialized || !topBar.hidden || gate.app || handle.view.visibility != View.VISIBLE) return
        val prefs = getSharedPreferences("hints", MODE_PRIVATE)
        if (prefs.getBoolean("hiddenBar", false)) return
        prefs.edit().putBoolean("hiddenBar", true).apply()
        val button = cfg.barHidden() == "button"
        Messages.show(this, if (button) "小さなボタンを押すと、タブバーが出ます。ボタンは、上下になぞって動かせます" else "端から内側へスワイプすると、タブバーが出ます")
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::edgeReveal.isInitialized) {
            val pullable = (edgeOn || !topBar.onTop) && cfg.barSwipe()
            if (topBar.hidden) {
                if (pullable) {
                    val top = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0
                    edgeReveal.onTouch(ev, cfg.barPos(), window.decorView.width, top, true)
                }
            } else if (edgeOn && ev.actionMasked == MotionEvent.ACTION_DOWN && topBar.overlayRail) {
                // 時間では引っ込めない。バーの外 (ページ) を触ったときと、下へスクロールしたときに引っ込める (ページへの操作はそのまま通す)
                val r = Rect()
                val inBar = topBar.railBox.getGlobalVisibleRect(r) && r.contains(ev.x.toInt(), ev.y.toInt())
                if (!inBar) setBar(false)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 上部の保存の帯を、キューとサービスの様子に合わせて直す。 */
    private fun refreshSaveStrip() {
        if (::topBar.isInitialized) topBar.saveStrip.refresh(gate.queue)
    }

    /** ページの種類が変わった: 保存の帯と、上部バーの左右のボタンを今の画面に合わせる。 */
    private fun refreshPageChrome() {
        refreshSaveStrip()
        topBar.updateButtons()
        if (edgeOn) applyEdgeMode()
    }

    /** アプリを開いたとき (前回の確認から半日以上たっていたら)、裏で新しい版を確認し、あれば ⋮ / 歯車に点を出す (UpdateNotice)。debug 版は更新できないので確認しない */
    private fun checkUpdateNotice() {
        if (BuildConfig.DEBUG) return
        val now = System.currentTimeMillis()
        if (!updateNotice.dueToCheck(cfg.updateNotice(), now)) return
        val ua = userAgent()
        runBackground("Yomikura-update-notice") {
            val r = try { updater.check(ua, BuildConfig.VERSION_NAME) } catch (e: Exception) { return@runBackground } // 通信の失敗は、次に開いたときにやり直す
            updateNotice.record(r.state, r.latest, now)
            runOnUiThread { if (::topBar.isInitialized) topBar.updateButtons() }
        }
    }

    /** いま、スクロールに合わせて上のバーを動かす画面か (onWebScroll が HeaderScroll に渡す条件と同じ) */
    private fun barFollowsScroll() =
        BarScrollRules.follows(gate.reader, siteBarHidden, expectVertical, edgeOn, topBar.onTop, barScrollLocked())

    private fun barScrollLocked() = BarScrollRules.locked(gate.app, cfg.hideBarOnScroll(), aids.speaking)

    private fun onWebScroll(scrollY: Int, oldY: Int) {
        if (gate.reader) return // 読書画面の出し入れは reader.js (readerBar) が決める
        // 縦書きの話ページでバーを隠している間 (ui-vertical.js が決める): 本文は横にスクロールし、WebView の縦のスクロール量は 0 のまま。
        // そのまま HeaderScroll に渡すと「縦が 0 以下 = 先頭 = バーを出す」となり、どちらへ動かしても出てきてしまう
        if (siteBarHidden || expectVertical) return
        if (edgeOn) { // 端から出したバーは、ページを下へ動かし始めたら隠す
            if (!topBar.hidden && !aids.speaking && scrollY - oldY > 16 * theme.dp) setBar(false) // 読み上げ中は段落に合わせてページが動くので、隠さない (⋮ の「読み上げを止める」を押せるように)
            return
        }
        if (!topBar.onTop) { // 左右のバー (端から出す設定でないとき): いつも出す (左右では「スクロールで隠す」を選べない。隠したいときは「普段は隠しておく」)
            if (topBar.hidden) setBar(true)
            return
        }
        // 読み上げ中は段落に合わせてページが動き続けるので、バーを隠さない (⋮ の「読み上げを止める」を押せるように)
        topBar.scroll.onScroll(scrollY, oldY, locked = barScrollLocked())
    }

    /**
     * 戻る。アプリのエラー画面 (error.html) からは、失敗したページ (履歴でエラー画面の 1 つ前) を飛ばして戻る
     * (失敗したページへ戻ると、もう一度失敗してエラー画面に戻り、いつまでも前のページへ戻れないため)。
     * 失敗したページが履歴の先頭 (ほかのアプリのリンクで開いた直後など) で、その前が無いときは、アプリを閉じる。
     */
    private fun goBackInWeb() {
        val onError = webView.url?.startsWith("https://${TrustGate.APP_HOST}/error.html") == true
        when {
            onError -> if (webView.canGoBackOrForward(-2)) webView.goBackOrForward(-2) else finish()
            webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    /**
     * WebView でページを開く。この画面から開くページは、すべてここを通す。
     * 保存データ・設定・ほかのアプリから来た URL も混ざるので、SiteUrls.safeNavUrl で https のものだけ開き、
     * `javascript:` などはアプリの画面の権限で動いてしまわないよう、開かずにリストへ行く。
     */
    private fun navigate(url: String) {
        val safe = SiteUrls.safeNavUrl(url)
        if (safe == null) {
            Diag.w(TAG, "blocked url: ${url.take(60)}")
            webView.loadUrl(LIST_URL)
            return
        }
        webView.loadUrl(safe)
    }

    /** テーマ (auto / light / dark) をアプリ全体に適用する。ダークのときはサイトも自動で暗くなる。 */
    private fun applyTheme(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // ---- JS の窓口 (StoreBridge / OfflineBridge) から頼まれる操作 ----

    override fun nsfwOn(): Boolean = cfg.nsfwOn()
    override fun startUrl(): String = startPage.url()
    override fun collectNow() = collect.start(auto = false)
    override fun userAgent(): String = webView.settings.userAgentString
    override fun finishLaterBack(result: String) = links.finishLaterBack(result)
    override fun clearWebCache() = webView.clearCache(true)
    override fun launchInstaller(apk: java.io.File) = SystemActions.launchInstaller(this, apk)
    override fun openSystemSettings(kind: String) = SystemActions.openSettings(this, kind)
    override fun startExport(mode: String, files: List<Pair<String, String>>) = backupFiles.startExport(mode, files)
    override fun startImport() = backupFiles.startImport()

    /** いま WebView に出ているのがアプリ内ページ (yomikura.local) か (UI スレッドから)。サイトのページから端末側の操作をさせないため。 */
    override fun onAppPage(): Boolean = TrustGate.classify(webView.url).app

    override fun requestNotifyPermission() {
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
    }

    /**
     * 通知の許可 (Android 13 以降) を求めてから、保存のサービスを始める。どのスレッドから呼んでもよい。
     * 裏に回っていて前景サービスを始められないとき (Android 12 以降) は、キューは順番待ちのまま残して、お知らせだけ出す。
     */
    override fun startSaving() = runOnUiThread {
        if (Build.VERSION.SDK_INT >= 33 && !Notices.allowed(this)) {
            // 初回だけ、OS の許可の画面の前に、何のための許可かを説明する (保存はそのまま始める)
            val prefs = getSharedPreferences("notify_ask", MODE_PRIVATE)
            if (notifyDialogShowing) {
                // 説明を出している間に、続けて保存が始まっても、OS の許可の画面を重ねない
            } else if (!prefs.getBoolean("explained", false)) {
                notifyDialogShowing = true
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("通知の許可")
                    .setMessage("保存の進み具合 (何話まで保存したか) を、通知で表示するために、通知の許可をお願いします。許可しなくても保存はできますが、アプリを閉じている間の進み具合は見えません。")
                    .setPositiveButton("許可する") { _, _ -> requestNotifyPermission() }
                    .setNegativeButton("今はしない", null)
                    .setOnDismissListener { notifyDialogShowing = false; prefs.edit().putBoolean("explained", true).apply() } // 答えたとき (外を押して閉じたときも) 済みにする
                    .show()
            } else requestNotifyPermission()
        }
        if (!OfflineService.start(this)) Messages.show(this, "保存を始められませんでした。アプリを開いた状態で、保存キューからもう一度始めてください")
    }

    /** キューが書き換わった (JS の窓口から)。上部の保存の帯を直す */
    override fun queueChanged() = runOnUiThread { refreshSaveStrip() }

    /** オフライン保存をすべて消す。保存待ち・保存中のキューも空にして、サービスを止める */
    override fun dropOfflineAll(): Boolean {
        saveQueue.clearAll()
        OfflineService.send(this, OfflineService.ACTION_STOP)
        queueChanged()
        return offline.deleteAll()
    }

    /** 保存データ (リスト・設定・履歴・読書位置など) とオフライン保存をすべて消し、初回の画面に戻る。ログイン情報は消さない。 */
    override fun wipeAll() {
        NHData.set(this, "")
        SettingsBackup.clear(this) // 設定の写しも消す (残すと、消したあとの再起動や入れ直しで設定が戻る)
        runCatching { TotpVault(this).clear() } // なろうの 2 段階認証の鍵も消す (保存データとは別の保管庫なので、ここで消さないと残る)
        runCatching { fonts.clear() } // 入れたフォントも消す (設定の font は保存データと一緒に消えるので、ファイルだけ残さない)
        // オフライン保存も消す。ファイルが多いと時間がかかるので、ファイルを消すのは裏のスレッドで (画面を止めない)
        saveQueue.clearAll()
        OfflineService.send(this, OfflineService.ACTION_STOP)
        queueChanged()
        // 保存のサービスが止まるまでの書き込みは、キューを空にしてあるので断られる (OfflineStore.guarded)。消すだけでよい
        runBackground("Yomikura-main-bg") { runCatching { offline.deleteAll() } }
        startPage.markNeedsWelcome()
        links.forgetExternal()
        onDataChanged()
        navigate(WELCOME_URL)
    }

    /** サイトの話ページ (縦書き) で、上部バーを隠す / 出す。保存した作品の読書画面と同じ動き (「スクロールで隠す」のときだけ隠す。「いつも表示」なら出したまま)。左右のバー・端から出す設定のときは、何もしない */
    override fun siteReaderBar(hide: Boolean) {
        if (!siteVerticalPage) { siteVerticalPage = true; expectVertical = false; applyFullscreen(); applyBarOverlay() } // 見込み (expectVertical) は、ページが縦書きにしたと知らせてきた時点で、確定 (siteVerticalPage) に替わる // 縦書きの話ページ。保存した作品の縦書きと同じく、切り欠きを避け、上のバーを本文に重ねる
        if (edgeOn || !topBar.onTop) return
        val real = hide && cfg.hideBarOnScroll() // 「いつも表示」なら、ページが隠すと頼んでも、隠さない
        siteBarHidden = real // 縦書きで隠している間は、保存データが変わっても (読書位置の保存など)、出し直さない (onDataChanged)
        setBar(!real)
    }

    /** 読書画面で、上部バーを隠す / 出す (「スクロールで隠す」がオフなら隠さない) */
    override fun readerBar(hide: Boolean) {
        if (edgeOn) return // 隠すか出すかは、端からのスワイプが決める
        if (!hide && !topBar.onTop) return // 左右のバーは、上へスクロールしても勝手に出さない (出すのは、端のスワイプか目印)
        if (!hide) setBar(true) else if (gate.reader && cfg.hideBarOnScroll() && topBar.onTop) setBar(false) // 「いつも表示」なら、縦書きでも隠さない (本文のバーだけを重ねて出し入れする。画面の高さが変わらず、縦書きが組み直されない) // 左右のバー (端から出さないとき) は、いつも出す
    }

    /**
     * アプリ内ページの JS を実行する。画面が終わっている・アプリ内ページでなくなっている (サイトのページへ移った) ときは何もしない
     * (アプリ内ページ向けの結果を、サイトのページの同名の関数に渡さないため)。どのスレッドからでも呼べる。
     */
    override fun evalJs(script: String) {
        runOnUiThread {
            if (isDestroyed || !gate.app) return@runOnUiThread
            webView.evaluateJavascript(script, null)
        }
    }

    /** 開いている画面の window.<fn>(JSON の文字列) を呼ぶ (無ければ何もしない)。どのスレッドからでも呼べる。 */
    override fun emitJs(fn: String, json: JSONObject) {
        evalJs("window.$fn&&$fn(${JSONObject.quote(json.toString())})")
    }

    /** 保存データが変わったとき: バッジ、上部バー (表示サイト)、テーマを反映する。 */
    override fun onDataChanged() {
        applyTextZoom(webView) // 設定の「端末の文字サイズに合わせる」を、すぐ効かせる
        topBar.updateListCount()
        edgeOn = cfg.barEdge()
        applyFullscreen()
        updateClock() // 時計を置く側 (左右) を変えたときも反映する (applyFullscreen は、全画面・切り欠き・時計の有無が変わったときしか、やり直さない)
        applyBarOverlay()
        updateHandle()
        placeRail() // ボタンを動かしたあとは、バーもそのボタンの高さへ
        updateGestureExclusion() // 目印の高さを動かしたら、戻るジェスチャーから外す範囲も、その高さへ
        if (topBar.hidden && (!cfg.hideBarOnScroll() || !topBar.onTop) && !edgeOn && !gate.reader && !siteBarHidden) topBar.showNow().also { updateHandle() } // 「スクロールで隠す」をオフにしたら、すぐ出す。読書画面では、本文のバーに合わせて隠しているので、読書位置の保存のたびに出し直さない
        val bg = cfg.bgSignature()
        if (bg != bgSignature) {
            bgSignature = bg
            BgCheck.reschedule(this)
            webView.evaluateJavascript("window.nhPlanChanged && nhPlanChanged()", null)
        }
        val bar = cfg.barSignature()
        if (bar != barSignature) {
            barSignature = bar
            topBar.fill()
            arrangeBar()
            topBar.applyColors(root, window, gate.queue) // 左右のバーの境目の線の色も、作り直したあとに塗る
            if (::findBar.isInitialized) findBar.applyColors(theme.pal.bg, theme.pal.text, theme.pal.subtext) // バーの背景色を変えたとき
            updateClock()
            applyEdgeMode()
        }
        val mode = cfg.settings().optString("theme", "auto")
        if (mode != currentTheme) {
            currentTheme = mode
            applyTheme(mode)
            // 開いているページ (主に設定画面) にもすぐ反映する
            webView.postDelayed({ webView.reload() }, 300)
        }
    }

    private companion object {
        const val TAG = "Yomikura"
        const val REQ_NOTIFY = 1

        /** 全画面で時計を出すときに、上に最低限取る余白 (dp)。切り欠きの余白が小さい・無い端末用 */
        const val CLOCK_ROOM_DP = 26
        const val EXPECT_VERTICAL_MS = 8000L // 縦書きの見込みで開いたページが、縦書きにならないままこの時間たったら、ふつうの扱いに戻す
        const val STATUS_FADE_DP = 20 // 状態バーを残すとき、状態バーの下で文字が薄く消えていく幅

        /** 復帰時にページの見た目を一瞬変えて戻すまでの時間 (ミリ秒。数コマ分。短いと、変えたことが描画に出ない) */
        const val NUDGE_RETURN_MS = 80
    }
}
