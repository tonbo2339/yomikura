package com.tonbo2339.yomikura

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
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
    private lateinit var findBar: FindBar
    private lateinit var hostFilter: HostFilter
    private lateinit var injectedJs: String
    /** 版によってだけある連携 (アプリを開いたときに、時期なら自動で送る) */
    private lateinit var extras: ExtraHooks

    private val prefs by lazy { getSharedPreferences(Edition.PREFS_NAME, Context.MODE_PRIVATE) }
    private val cfg by lazy { AppSettings(this) }
    private val theme by lazy { BarTheme(this) }
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

    override val updater: Updater by lazy { Updater(this, Edition.UPDATE_REPO, Edition.UPDATE_NEEDS_LOGIN) }
    override val launcherIcon: LauncherIcon by lazy { LauncherIcon(this) }
    override val activity: AppCompatActivity get() = this
    override val appPageNow: Boolean get() = gate.app
    override val trustedPageNow: Boolean get() = gate.trusted
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
            refreshPageChrome()
            if (topBar.scroll.hidden) topBar.scroll.showNow() // 新しいページは、バーを出した状態で始める
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
            LoginCookies.sync(this@MainActivity) // サイトでログイン・ログアウトしたあとの状態を写す
        }
    }

    // ---- 起動・終了 ----

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Diag.init(this) // エラーの記録 (診断情報に載せる)
        // 入れ直した直後 (保存データが空) なら、Android のバックアップから戻った設定の写しを使う (SettingsBackup)
        SettingsBackup.restoreIfEmpty(this)
        // 保存済みのテーマは、画面を作る前に適用する (起動時の作り直しを避ける)
        currentTheme = cfg.settings().optString("theme", "auto")
        applyTheme(currentTheme)
        super.onCreate(savedInstanceState)
        backupFiles.restore(savedInstanceState)
        launcherIcon.applyPending() // 「後で」で覚えたランチャーアイコンを、この起動で適用する
        // ランチャーの長押しのショートカット (続きから読む・リストを開く)。アイコンを切り替えたあとに出す
        // (ショートカットは、その時に有効な別名に付き、別名を無効にすると消えるため)
        runBackground("Yomikura-shortcuts") { Shortcuts.publish(this) }

        hostFilter = Edition.hostFilter(this)
        injectedJs = NHData.tokenJs + AppAssets.siteScripts(this)

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        webView = createWebView()
        barSignature = cfg.barSignature()
        bgSignature = cfg.bgSignature()
        setContentView(buildLayout())
        // 裏での確認: 書き換えを画面 (バッジ・上部バー) に反映し、アラームを掛け直す (アプリの更新後など)
        BgCheck.onDataChanged = bgListener
        BgCheck.onChallenge = bgChallengeListener
        BgCheck.reschedule(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (findBar.isShowing) findBar.hide() else if (webView.canGoBack()) webView.goBack() else finish()
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
            addJavascriptInterface(OfflineBridge(this@MainActivity, offline, saveQueue, hostFilter), "NHOffline")
            // 版によってだけある連携 (Edition.installExtras)。公開版は何もしない
            extras = Edition.installExtras(this@MainActivity, this, offline, ::emitJs) { gate.app }
            webViewClient = NovelClient(this@MainActivity, gate, cfg, hostFilter, offline, injectedJs, clientHost)
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
        // ヘッダー / フッターを隠す CSS は、ちらつかないようページ描画前に入れる
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origins = setOf("https://*.syosetu.com", "https://syosetu.com", "https://syosetu.org", "https://kakuyomu.jp")
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
            if (cfg.autoCheckDue()) collect.start(auto = true)
            // アプリを更新したあとの最初の起動なら、この版の変更点を 1 回だけ出す (リンクから開いた起動などでは出さず、次のふつうの起動に回す)
            ReleaseNotes.takeOnce(this, prefs)?.let { ReleaseNotesDialog.show(this, theme.dp, it) }
        }
    }

    override fun onResume() {
        super.onResume()
        BgCheck.foreground = true
        OfflineStatus.onChange = { runOnUiThread { refreshSaveStrip() } }
        OfflineService.resumeIfPending(this, saveQueue) // 途中で止まった保存を続きから
        refreshSaveStrip()
        topBar.updateListCount() // 裏での確認で増えた分をバッジに出す
        extras.autoIfDue() // 連携先へ送る: 前回から設定の間隔がたっていれば送る
    }

    override fun onPause() {
        super.onPause()
        SettingsBackup.save(this) // Android のバックアップの対象は設定の写しだけ。裏に回るときに写す
        BgCheck.foreground = false
        OfflineStatus.onChange = null
    }

    override fun onStop() {
        super.onStop()
        aids.stopReading() // 画面を離れたら、読み上げは止める
    }

    override fun onDestroy() {
        Messages.detach(snack)
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
        if (::topBar.isInitialized) {
            topBar.applyColors(root, window, gate.queue)
            topBar.fill()
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
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        topBar = TopBar(this, theme, cfg, saveQueue, menus, ::navigate, { webView.reload() }, pageMenu::show, { gate.app }, { gate.settings })
        topBar.fill()
        root.addView(topBar.header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        // WebView の上に、ページ内検索・読み込みの線・下から出るお知らせのバーを重ねる
        val dp = theme.dp
        val content = FrameLayout(this)
        content.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        findBar = FindBar(this, webView, theme.pal.bg, theme.pal.text, theme.pal.subtext, dp)
        content.addView(findBar.view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        content.addView(loadBar.view, FrameLayout.LayoutParams(MATCH_PARENT, maxOf(2, (2 * dp).toInt()), Gravity.TOP))
        Messages.attach(snack)
        content.addView(snack.view, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply {
            val m = (12 * dp).toInt(); setMargins(m, 0, m, m)
        })
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        topBar.applyColors(root, window, gate.queue)
        return root
    }

    /** 上部の保存の帯を、キューとサービスの様子に合わせて直す。 */
    private fun refreshSaveStrip() {
        if (::topBar.isInitialized) topBar.saveStrip.refresh(gate.queue)
    }

    /** ページの種類が変わった: 保存の帯と、上部バーの左右のボタンを今の画面に合わせる。 */
    private fun refreshPageChrome() {
        refreshSaveStrip()
        topBar.updateButtons()
    }

    private fun onWebScroll(scrollY: Int, oldY: Int) {
        if (gate.reader) return // 読書画面の出し入れは reader.js (readerBar) が決める
        topBar.scroll.onScroll(scrollY, oldY, locked = gate.app || !cfg.hideBarOnScroll())
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
            requestNotifyPermission()
        }
        try {
            OfflineService.start(this)
        } catch (e: Exception) {
            Diag.w(TAG, "startForegroundService failed", e)
            Messages.show(this, "保存を始められませんでした。アプリを開いた状態で、保存キューからもう一度始めてください")
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

    /** 保存データ (リスト・設定・履歴・読書位置など) とオフライン保存をすべて消し、初回の画面に戻る。ログイン情報は消さない。 */
    override fun wipeAll() {
        NHData.set(this, "")
        SettingsBackup.clear(this) // 設定の写しも消す (残すと、消したあとの再起動や入れ直しで設定が戻る)
        // オフライン保存も消す。ファイルが多いと時間がかかるので、ファイルを消すのは裏のスレッドで (画面を止めない)
        saveQueue.clearAll()
        stopService(Intent(this, OfflineService::class.java))
        queueChanged()
        // 保存のサービスは止まるまで少しかかり、その間に話を書くことがあるので、少し待ってもう一度消す (書き残しを残さない)
        runBackground("Yomikura-main-bg") {
            runCatching { offline.deleteAll() }
            runCatching { Thread.sleep(2000); offline.deleteAll() }
        }
        startPage.markNeedsWelcome()
        links.forgetExternal()
        onDataChanged()
        navigate(WELCOME_URL)
    }

    /** 読書画面で、上部バーを隠す / 出す (「スクロールで隠す」がオフなら隠さない) */
    override fun readerBar(hide: Boolean) {
        if (!hide) topBar.scroll.setHidden(false) else if (gate.reader && cfg.hideBarOnScroll()) topBar.scroll.setHidden(true)
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
        topBar.updateListCount()
        if (topBar.scroll.hidden && !cfg.hideBarOnScroll()) topBar.scroll.showNow() // 「スクロールで隠す」をオフにしたら、すぐ出す
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
    }
}
