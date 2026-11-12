package com.tonbo2339.yomikura

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * オフライン保存キュー (SaveQueue) を、前景サービスで 1 作品ずつ進める。
 * 画面を消しても、別のアプリに移っても続く。進みは通知に出す (保存中の 1 本と、終わったときのお知らせ)。
 *
 * 1 作品の保存は、画面に出さない WebView で行う (Collector と同じ作り)。JS は offline.js / offline-bg.js にあり、
 * ここは端末への書き込み・止める・待つ・進みの受け取りだけを受け持つ (窓口は [Bridge])。
 * 通信・Wi-Fi の判定はここで行い、JS は [Bridge.waiting] を見て待つ。
 */
class OfflineService : Service() {

    private val main = MainThread.handler
    private val queue by lazy { SaveQueue(File(filesDir, SaveQueue.FILE_NAME)) }
    private val store by lazy { OfflineStore(File(filesDir, "offline")) }
    private val blocker by lazy { Edition.hostFilter(this) }

    private var web: WebView? = null
    @Volatile private var job: SaveQueue.Job? = null     // いま保存している作品
    private var ok = 0                         // その作品で保存できた話の数
    private var total = 0                      // その作品で今回保存する話の数 (通知の分母。始めはキューの話数、JS の知らせで直す)
    private var title = ""                     // その作品で保存中の話の題名 (通知に出す)
    @Volatile private var userPaused = false   // 利用者が一時停止した (JS の窓口のスレッドからも読む)
    @Volatile private var cancelRequested = false // 利用者が中止した (同上)
    @Volatile private var jsStarted = false         // いまの作品のページに、保存の JS を入れたか (入れる前の「中止」は、すぐ終える)
    @Volatile private var removedRequested = false // 保存中の作品がキューから外された (同上)。「元に戻す」で戻されたら、中止にせず順番待ちに戻す
    private var destroyed = false              // onDestroy / onTimeout のあと (遅れて届いた知らせで、次の作品を始めない)
    private var stopping = false               // stopForeground のあと、onDestroy までの間 (進みの通知を出し直さない)
    private var lastStartId = 0                // 最後に来た開始の番号 (stopSelfResult に使う。新しい開始が来ていたら閉じない)
    private val notifier by lazy { OfflineNotifier(this) }
    private val net by lazy { NetworkWatch(this, main, netListener) } // 回線につながっているか (切れている間は、失敗にせず待つ) / 従量課金か
    private var foreground = false             // 前景サービスに入ったあと (入る前に pump すると、startForeground の前に閉じてしまう)

    // 一定時間、進みも待ちもない (JS が止まった) ときは、その作品を打ち切る
    private var ready: PageReady? = null // 保存中の作品のページの、読み込みの見張り
    private val stall = Runnable { finishJob(JSONObject().put("error", "応答がないので止めました").toString()) }

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        queue.recoverAfterRestart() // 前回の実行が途中で止まっていたら、その作品は順番待ちに戻す
        net.start() // 最初のページを読む前に、いまの回線を調べておく
        OfflineStatus.publish(null, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_PAUSE -> { userPaused = true; syncStatus() }
            ACTION_RESUME -> { userPaused = false; syncStatus() }
            ACTION_CANCEL -> cancel()
            // キューを空にした (全削除): 作品の JS を止め、あとは pump が、キューが空なので閉じる (新しい開始が来ていれば閉じない)
            ACTION_STOP -> { endRunner(); job = null }
        }
        if (!goForeground()) { stopSelf(startId); return START_NOT_STICKY }
        foreground = true
        pump()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 通知の「中止」。保存中の作品があれば、その作品を止める (JS が stopRequested を見て終わる)。
     * 保存中の作品が無く Wi-Fi を待っているだけのときは、待っている作品を止める (通知には待っている作品の数が出ているため)
     */
    private fun cancel() {
        if (job != null) {
            cancelRequested = true
            // 保存の JS を入れる前 (ページを読み込み中・読み込めない) なら、JS が止まりを見てくれないので、すぐ終える
            if (!jsStarted) finishJob(JSONObject().put("stopped", true).toString())
            return
        }
        // 保存中の作品が無く、待っているだけ (Wi-Fi・回線・一時停止) なら、待っている作品を止める (通知には待っている作品の数が出ているため)
        queue.all().filter { it.status == SaveQueue.Status.WAIT_WIFI || it.status == SaveQueue.Status.WAITING }
            .forEach { j -> queue.update(j.key) { it.copy(status = SaveQueue.Status.STOPPED, note = "中止しました。保存した話はそのまま残ります") } }
    }

    /** Android 15 以降の時間の上限に当たったとき。いま保存中の作品は順番待ちに戻し、サービスを閉じる (アプリを開けば続きから) */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Diag.w(TAG, "timeout")
        // 一時停止中なら一時停止のまま残す (次に開いたとき、勝手に保存を再開しない)
        job?.let { queue.update(it.key) { j -> j.copy(status = if (userPaused) SaveQueue.Status.PAUSED else SaveQueue.Status.WAITING, note = "") } }
        val paused = userPaused
        shutdown()
        stopping = true
        notifier.done(
            "保存を一時的に止めました",
            if (paused) "Android の制限です。保存キューの画面で「再開」すると続きから保存します" else "Android の制限です。アプリを開くと続きから保存します", // 続きは resumeIfPending
            null,
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        net.stop()
        shutdown()
        Notices.cancel(this, Notices.Id.OFFLINE_PROGRESS) // 閉じる間際に出し直された進みの通知を残さない
        OfflineStatus.publish(null, false)
        super.onDestroy()
    }

    /** サービスを閉じる: 作品の JS を止め、保存中の作品を手放す (このあと届いた進み・終わりの知らせは無視する) */
    private fun shutdown() {
        destroyed = true
        endRunner()
        job = null
    }

    /**
     * 次の作品を始める。「Wi-Fi のときだけ」で従量課金の回線のときは、「今回だけモバイル」を選んだ作品だけ始める
     * (ほかは WAIT_WIFI のまま、作品のページも読まずに待つ)。保存する作品も待つ作品もなくなったら、サービスを閉じる。
     */
    private fun pump() {
        if (destroyed) return
        val wifiOnly = wifiOnlySetting()
        if (web != null) {
            // Wi-Fi 待ちで WebView を開いたままの作品があるとき、モバイルで保存してよい作品 (「今回だけモバイル」) が入ったら、待つ作品を順番待ちに戻して先へ進む
            val cur = job
            val mobileReady = queue.all().any { it.status == SaveQueue.Status.WAITING && !it.needsUnmetered(wifiOnly) }
            if (cur == null || !waitingWifi() || !mobileReady) { refreshNotice(); return }
            endRunner()
            job = null
            queue.update(cur.key) { it.copy(status = SaveQueue.Status.WAIT_WIFI, note = "") }
        }
        // NSFW モード中は R18 の作品を保存しない (R18 のサイトへアクセスしないため)。止めた作品は、モードを切ってから「もう一度」で保存できる
        val nsfw = NHData.nsfwOn(NHData.settings(this))
        if (nsfw) queue.all().filter { (it.site == "narou18" || SiteUrls.isR18Url(it.url)) && (it.status == SaveQueue.Status.WAITING || it.status == SaveQueue.Status.WAIT_WIFI) }
            .forEach { j -> queue.update(j.key) { it.copy(status = SaveQueue.Status.STOPPED, note = Edition.R18_OFF_WHY + "保存しません") } }
        queue.all().forEach { j ->
            val wait = net.metered && j.needsUnmetered(wifiOnly)
            if (j.status == SaveQueue.Status.WAITING && wait) queue.update(j.key) { it.copy(status = SaveQueue.Status.WAIT_WIFI) }
            if (j.status == SaveQueue.Status.WAIT_WIFI && !wait) queue.update(j.key) { it.copy(status = SaveQueue.Status.WAITING) }
        }
        // 端末の空きが少なすぎるときは始めない (保存の途中で書けなくなり、同じ作品を何度も取り直すのを防ぐ)。順番待ちは「止めました」にして、空きを作ってから「もう一度」で保存できる
        if (!StorageSpace.canStart(StorageSpace.freeBytes(this))) {
            queue.all().filter { it.status == SaveQueue.Status.WAITING || it.status == SaveQueue.Status.WAIT_WIFI }
                .forEach { j -> queue.update(j.key) { it.copy(status = SaveQueue.Status.STOPPED, note = LOW_SPACE_NOTE) } }
            DevLog.eventOf(DevLog.Category.OFFLINE, "queue", "stopped" to "low space", "freeMB" to StorageSpace.freeBytes(this) / 1_000_000)
            refreshNotice()
            stopNow()
            return
        }
        val next = queue.next()
        if (next != null) {
            // 回線が無い間は始めない (作品のページを読めずに 3 回失敗して、順番待ちが次々に「止めました」になる)。つながったら onLinkChanged が呼び直す
            if (!net.online) { refreshNotice(); return }
            startJob(next)
            return
        }
        if (queue.all().any { it.status == SaveQueue.Status.WAIT_WIFI }) { refreshNotice(); return } // Wi-Fi になるのを待つ
        stopNow()
    }

    /**
     * 閉じる。閉じる間際に新しい開始 (startForegroundService) が来ていたら、閉じない (その開始は startForeground を約束しているので、
     * 無条件の stopSelf() で閉じると、約束を果たせず落ちる)。閉じるときは、進みの通知を出し直さないよう印を付ける
     */
    private fun stopNow() {
        if (!stopSelfResult(lastStartId)) return
        stopping = true
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startJob(j: SaveQueue.Job) {
        // 更新の確認など、ほかでアクセスを止められたばかりのサイトには、続けて取りに行かない (「もう一度」で、少し待ってから保存できる)
        if (SiteRest.isResting(this, j.site, auto = false)) {
            queue.update(j.key) { it.copy(status = SaveQueue.Status.NEEDS_CHECK, note = REST_NOTE) }
            pump()
            return
        }
        job = j
        ok = 0
        total = j.eps.size
        title = j.title
        cancelRequested = false
        removedRequested = false
        jsStarted = false
        queue.update(j.key) { it.copy(status = SaveQueue.Status.RUNNING, note = "") }
        syncStatus()
        web = try {
            makeRunner(j)
        } catch (e: Exception) {
            // WebView を作れない (更新の途中・無効にされているなど)。落ちないよう、順番待ちに戻して止める (あとで「再開」すれば続きから)
            Diag.e(TAG, "webview unavailable", e)
            queue.update(j.key) { it.copy(status = SaveQueue.Status.WAITING, note = "ブラウザの部品 (WebView) を使えなかったので止めました") }
            job = null
            stopNow()
            return
        }
        main.postDelayed(stall, STALL_MS)
    }

    /** 作品の JS を、画面に出さない WebView で動かす。読み込みが済んだら JS を入れる (Collector と同じ)。 */
    private fun makeRunner(j: SaveQueue.Job): WebView {
        val jobJson = JSONArray(SaveQueue.encode(listOf(j))).getJSONObject(0).toString()
        // 窓口の合言葉は、ページ本体 (メインフレーム) にだけ入れる。offline-bg.js が読んですぐ消す (iframe には渡らない)
        val js = NHData.tokenJs + "window.__nhBgT=" + JSONObject.quote(NHData.token) + ";\nwindow.NH_BG_JOB=" + jobJson + ";\n" + AppAssets.siteScripts(this)
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            addJavascriptInterface(BgStore(this@OfflineService) { main.post { BgCheck.onDataChanged?.invoke() } }, "NHStore")
            addJavascriptInterface(Bridge(j), "NHOffline")
            val client = object : WebViewClient() {
                private var injected = false
                var failed = false // 読み込みに失敗して、やり直しを待っている
                private var tries = 0

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) =
                    NetLog.webError("offline", request, response.statusCode, response.responseHeaders)

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    NetLog.page("offline", request)
                    // 対応サイト以外のページは開かせない (保存の窓口とトークンを、保存する作品の外へ渡さないため)
                    if (request.isForMainFrame && !SiteUrls.isSupportedHost(request.url.host.orEmpty())) {
                        Diag.w(TAG, "off-site request: ${request.url.scheme}://${request.url.host}") // どの URL で止まったかを残す (「対応していない URL です」の原因の切り分け用)
                        // WebView の別のスレッドから呼ばれるので、作品の後始末は画面のスレッドで行う
                        main.post { if (web === view) finishJob(JSONObject().put("error", "対応していない URL です").toString()) }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return if (blocker.shouldBlock(request.url.host)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
                }

                // 作品のページの中から別のページへは行かせない (保存の窓口を、保存する作品の外へ広げないため)
                // 作品のページの中から、対応サイト以外へは行かせない。対応サイト内の https への移動 (リダイレクトなど) は通す
                // (一律に止めると、作品のページが移動するときに読み込みが止まり、10 分後に「応答がない」で終わっていた)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    !(request.url.scheme == "https" && SiteUrls.isSupportedHost(request.url.host.orEmpty()))

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { ready?.started(url) }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (ready?.finished(url) == true) return // 見張り (PageReady) がもう入れた
                    inject(view, url)
                }

                /** 作品のページに保存の JS を入れる (onPageFinished か、完了の来ないときの見張りから) */
                fun inject(view: WebView, url: String?) {
                    if (injected || failed) return
                    // 対応サイトの https のページにだけ入れる (別のページへ飛ばされていたら、合言葉を渡さず打ち切る)
                    val u = Uri.parse(url.orEmpty())
                    // 読み込みの途中 (最初のページが切られて about:blank になったなど) は、まだ入れず、本当のページが終わるのを待つ。
                    // これまでは、ここで「対応していない URL」として作品ごと止めていた (保存中に別のページを開いたときなどに起きた)
                    if (url.isNullOrEmpty() || url == "about:blank") return
                    if (u.scheme != "https" || !SiteUrls.isSupportedHost(u.host.orEmpty())) {
                        Diag.w(TAG, "off-site page: $url")
                        injected = true
                        main.post { if (web === view) finishJob(JSONObject().put("error", "対応していない URL です").toString()) }
                        return
                    }
                    injected = true
                    jsStarted = true
                    try { view.evaluateJavascript(js, null) } catch (e: Exception) { Diag.e(TAG, "inject", e); finishJob(JSONObject().put("error", "保存の準備ができませんでした").toString()) }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Diag.w(TAG, "render process gone")
                    main.post { if (web === view) finishJob(JSONObject().put("error", "ブラウザの処理が止まりました").toString()) }
                    return true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    // 回線を切り替えた直後などは失敗しやすいので、少し待ってやり直す。回線が無い間の失敗は数えない (つながるまで待つ)
                    failed = true
                    if (!net.online) { main.removeCallbacks(stall); main.postDelayed(stall, STALL_MS) } else tries++
                    if (tries < LOAD_TRIES) main.postDelayed({
                        if (web !== view) return@postDelayed
                        // 待っている間に中止・キューから外された: JS はまだ入っていないので、ここで終える (回線が戻るまで読み直し続けない)
                        if (!queue.contains(j.key)) removedRequested = true
                        if (cancelRequested || removedRequested) { finishJob(JSONObject().put("stopped", true).toString()); return@postDelayed }
                        failed = false
                        view.loadUrl(j.url)
                    }, LOAD_RETRY_MS)
                    else if (web === view) finishJob(JSONObject().put("error", "ページを開けませんでした").toString())
                }
            }
            webViewClient = client
            // 対応サイトの URL だけ開く (javascript: などは shouldInterceptRequest を通らないので、ここで落とす)
            val url = SiteUrls.safeNavUrl(j.url)?.takeIf { SiteUrls.isSupportedHost(SiteUrls.hostOf(it)) }
            if (url == null) {
                main.post { if (web === this) finishJob(JSONObject().put("error", "対応していない URL です").toString()) }
                return@apply
            }
            loadUrl(url)
            // 公開版は広告を止めないので、広告の枠が終わらず onPageFinished が来ないことがある。本文が読めたら入れる
            ready = PageReady(main, { v -> if (web === v && !client.failed && !cancelRequested && !removedRequested) v.loadUrl(url) }) { v, u -> if (web === v) client.inject(v, u) }.also { it.watch(this) }
        }
    }

    /** 今の作品を終える。JS の結果 (JSON) から、キューの状態とお知らせを決める */
    private fun finishJob(json: String) {
        val cur = job ?: return
        endRunner()
        val r = json.toJsonOrNull() ?: JSONObject().put("error", "結果を読めませんでした")
        val latest = queue.all().firstOrNull { it.key == cur.key }
        // 保存中にキューから外したあと、「元に戻す」で戻された: 中止にせず順番待ちに戻す (保存済みの話は飛ばして、続きから)
        if (removedRequested && !cancelRequested && latest != null && !r.has("error")) {
            queue.update(cur.key) { it.copy(status = SaveQueue.Status.WAITING, note = "") }
            job = null
            pump()
            return
        }
        val status: SaveQueue.Status
        val note: String
        when {
            r.has("error") -> { status = SaveQueue.Status.STOPPED; note = r.optString("error") }
            cancelRequested || removedRequested -> { status = SaveQueue.Status.STOPPED; note = "中止しました。保存した話はそのまま残ります" }
            r.optBoolean("blocked") -> {
                status = SaveQueue.Status.NEEDS_CHECK
                note = "確認画面が出たので止めました。アプリでサイトを開いて通してから、もう一度保存してください"
            }
            r.optInt("fails") >= 3 -> {
                status = SaveQueue.Status.STOPPED
                note = "続けて取り込めなかったので止めました (${r.optString("lastErr")})"
            }
            else -> {
                status = SaveQueue.Status.DONE
                val failed = r.optInt("failed")
                note = if (failed > 0) "$failed 話は取り込めませんでした。もう一度保存すると、取り込めなかった話だけ続けて保存します" else ""
            }
        }
        // 保存の途中で話が足された (同じ作品をもう一度キューに入れた) ときは、終わらせず順番待ちに戻す
        val again = status == SaveQueue.Status.DONE && latest != null && latest.eps.size > cur.eps.size
        queue.update(cur.key) { it.copy(status = if (again) SaveQueue.Status.WAITING else status, note = if (again) "" else note) }
        // アクセスを止められたら、同じサイトの順番待ちの作品も止める (止められた直後に、続けて取りに行かない)
        if (status == SaveQueue.Status.NEEDS_CHECK) {
            queue.all().filter { it.site == cur.site && it.key != cur.key && (it.status == SaveQueue.Status.WAITING || it.status == SaveQueue.Status.WAIT_WIFI) }
                .forEach { j -> queue.update(j.key) { it.copy(status = SaveQueue.Status.NEEDS_CHECK, note = REST_NOTE) } }
        }
        job = null
        // 保存中に作品を消した (キューから外した): 消したあとに書かれた挿絵などのフォルダを残さない
        if (removedRequested && latest == null) store.dropIfNoMeta(cur.site, cur.id)
        val done = status == SaveQueue.Status.DONE
        // 自動更新で新しい話が無かった (または 1 話も保存しなかった) ときは、通知しない
        val quiet = cur.refresh && ok == 0 && done
        if (!again && !quiet) notifier.done(
            if (done) "保存しました: ${cur.title}" else "保存を止めました: ${cur.title}",
            "$ok 話を保存しました。$note".trimEnd(),
            cur.title,
        )
        pump()
    }

    /** 作品の JS を止めて、WebView を片付ける。止めるだけで、キューの状態は変えない */
    private fun endRunner() {
        main.removeCallbacks(stall)
        ready?.stop()
        ready = null
        web?.let { it.stopLoading(); it.destroy() }
        web = null
    }

    /** 利用者の一時停止・Wi-Fi の状態に合わせて、保存中の作品の状態 (RUNNING / PAUSED / WAIT_WIFI) を直す */
    private fun syncStatus() {
        val cur = job ?: return refreshNotice()
        val s = when {
            userPaused -> SaveQueue.Status.PAUSED
            waitingWifi() -> SaveQueue.Status.WAIT_WIFI
            else -> SaveQueue.Status.RUNNING
        }
        queue.update(cur.key) { if (it.status == s) it else it.copy(status = s) }
        refreshNotice()
    }

    /** 「Wi-Fi のときだけ保存する」の設定がオンで、いまの回線が従量課金か */
    private fun waitingWifi(): Boolean = net.metered && job?.needsUnmetered(wifiOnlySetting()) == true

    // 設定を読むには保存データ全体 (リスト・履歴を含む) を読み込むので、進みの知らせのたびには読まず、数秒は前の値を使う (設定の変更は数秒で効く)
    @Volatile private var wifiOnlyAt = 0L
    @Volatile private var wifiOnlyValue = false

    private fun wifiOnlySetting(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - wifiOnlyAt > WIFI_ONLY_CACHE_MS) {
            wifiOnlyValue = NHData.settings(this).optBoolean("offlineWifiOnly", false)
            wifiOnlyAt = now
        }
        return wifiOnlyValue
    }

    /** 回線の変化 (NetworkWatch から。画面のスレッドで呼ばれる) */
    private val netListener = object : NetworkWatch.Listener {
        override fun onLinkChanged(available: Boolean) {
            refreshNotice()
            if (available && foreground) pump()
        }
        override fun onMeteredChanged() {
            syncStatus()
            if (foreground) pump()
        }
    }

    /** 前景サービスとして始める。始められなかったら false */
    private fun goForeground(): Boolean = try {
        val n = notifier.progress(progressNow())
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notices.Id.OFFLINE_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Notices.Id.OFFLINE_PROGRESS, n)
        true
    } catch (e: Exception) {
        Diag.e(TAG, "foreground", e)
        false
    }

    private fun refreshNotice() {
        if (destroyed || stopping) return // 閉じたあとに届いた回線の知らせで、保存中の通知を出し直さない
        notifier.refresh(progressNow())
        val cur = job
        OfflineStatus.publish(cur?.let { OfflineStatus.Live(it.key, it.title, ok, total, userPaused, waitingWifi()) }, true)
    }

    /** 通知に出す、いまの保存の様子 */
    private fun progressNow(): OfflineNotifier.Progress {
        val all = queue.all()
        return OfflineNotifier.Progress(
            job, ok, total, title, userPaused, waitingWifi(),
            waiting = all.count { it.status == SaveQueue.Status.WAITING },
            wifiWaiting = all.count { it.status == SaveQueue.Status.WAIT_WIFI },
            offline = !net.online,
        )
    }

    /**
     * 作品の JS から見える窓口 (NHOffline)。書き込みは OfflineStore、止める・待つ・進みは、この中の状態で答える。
     * 引数の数は core/offline-api.js の NH.off と合わせてある (合わないと JS 側で「Method not found」になる)。
     * 合言葉 (t) は、保存のページ本体 (メインフレーム) にだけ渡す。広告の iframe などから呼ばれても、合言葉が無いので何もしない。
     * forJob = この WebView が保存している作品。前の作品の遅れて届いた知らせ (進み・終わり) で、次の作品を動かさないため。
     */
    private inner class Bridge(private val forJob: SaveQueue.Job) {
        private fun authed(t: String?) = t != null && t == NHData.token

        /** いま保存している作品の話・目録だけ書ける (保存を消された作品を、書き戻さない) */
        private fun mine(site: String, id: String): Boolean {
            val cur = job ?: return false
            return cur.key == "$site:$id" && queue.contains(cur.key)
        }

        // 確認と書き込みは、削除と同じロックの中で一続きに行う (確認のあとに消された作品へ、書き戻さない)
        @JavascriptInterface
        fun putEpisode(site: String, id: String, ep: String, json: String, t: String?): Boolean =
            authed(t) && store.guarded({ mine(site, id) }) { store.putEpisode(site, id, ep, json) }

        @JavascriptInterface
        fun putMeta(site: String, id: String, json: String, t: String?): Boolean =
            authed(t) && store.guarded({ mine(site, id) }) { store.putMeta(site, id, json) }

        @JavascriptInterface
        fun meta(site: String, id: String, t: String?): String = if (authed(t)) store.meta(site, id) else ""

        /**
         * 挿絵を取って保存する (ImageSaver を参照)。作品の保存の WebView から。通信は裏のスレッドで行い、すぐ戻る
         * (JS の窓口のスレッドは WebView 全体で共通なので、通信で止めると、アプリ内ページの呼び出しまで待たされる)。
         * 結果は、req を添えて JS の NH.off.imageDone(req, 結果) へ返す
         */
        @JavascriptInterface
        fun saveImage(site: String, id: String, url: String, referer: String, req: Int, t: String?) {
            if (!authed(t) || !mine(site, id)) return replyImage(req, "{}")
            runBackground("Yomikura-offline-img") {
                replyImage(req, ImageSaver.save(store, site, id, url, referer, { blocker.shouldBlock(it) }, { mine(site, id) }))
            }
        }

        private fun replyImage(req: Int, result: String) {
            main.post {
                if (job !== forJob) return@post // 前の作品の遅れた返事 (その WebView はもう無い)
                main.removeCallbacks(stall); main.postDelayed(stall, STALL_MS) // 画像が多くて遅いときに、「応答がない」で止めないよう、止まっていない印を付ける
                web?.evaluateJavascript("NH.off.imageDone($req,${JSONObject.quote(result)})", null)
            }
        }

        /** ページの中で取った挿絵 (base64) を保存する (本文と同じサイトの挿絵用。ImageSaver.saveData を参照) */
        @JavascriptInterface
        fun saveImageData(site: String, id: String, url: String, base64: String, t: String?): String =
            if (authed(t) && mine(site, id)) ImageSaver.saveData(store, site, id, url, base64, { blocker.shouldBlock(it) }, { mine(site, id) }) else "{}"

        @JavascriptInterface
        fun stopRequested(t: String?): Boolean {
            if (!authed(t)) return true
            // 一時停止・Wi-Fi 待ちの間にキューから外された (✕・全削除) なら、止める (待ちのまま残さない)
            if (!removedRequested && job === forJob && !queue.contains(forJob.key)) removedRequested = true
            return cancelRequested || removedRequested
        }

        /** 一時停止・Wi-Fi 待ちのあいだ true。待っている間は打ち切らないように、止まっていない印を付ける */
        @JavascriptInterface
        fun waiting(t: String?): Boolean {
            if (!authed(t)) return false
            main.post { if (job === forJob) { main.removeCallbacks(stall); main.postDelayed(stall, STALL_MS) } }
            return userPaused || waitingWifi() || !net.online
        }

        /** 回線が切れているか。JS は、切れている間の失敗を数えず、つながってから同じ話を取り直す */
        @JavascriptInterface
        fun offline(t: String?): Boolean = authed(t) && !net.online

        /** 1 話ごと。json = {ok, total, title} */
        @JavascriptInterface
        fun progress(json: String, t: String?) {
            if (!authed(t)) return
            main.post {
                if (job !== forJob) return@post // 前の作品の知らせ (もう終わっている)
                val r = try { JSONObject(json) } catch (e: Exception) { return@post }
                ok = r.optInt("ok", ok)
                // 分母は、今回保存する話の数 (保存済みを飛ばした数。JS が目次を読んで決める)。キューに入れたときの話数のままだと「もう一度」で「3 / 50」で終わる
                r.optInt("total", 0).takeIf { it > 0 }?.let { total = it }
                title = r.optString("title", title)
                // 保存中の作品がキューから外された (作品の保存を消した・キューを空にした) ら、続きを保存しない
                job?.let { cur -> if (!queue.contains(cur.key)) removedRequested = true }
                main.removeCallbacks(stall)
                main.postDelayed(stall, STALL_MS)
                refreshNotice()
            }
        }

        /** 作品の終わり。json は JS の結果 (終わり方と件数) */
        @JavascriptInterface
        fun finished(json: String, t: String?) {
            if (!authed(t)) return
            main.post { if (job === forJob) finishJob(json) }
        }
    }

    companion object {
        private const val TAG = "Yomikura.offline"
        private const val STALL_MS = 10 * 60_000L
        private const val LOW_SPACE_NOTE = "端末の空き容量が足りないので止めました。空きを作ってから「もう一度」で保存してください"
        private const val REST_NOTE = "このサイトにアクセスを止められたばかりなので、止めました。サイトを開いて確認画面を通し、少し待ってから、もう一度保存してください"
        private const val WIFI_ONLY_CACHE_MS = 3_000L
        private const val LOAD_TRIES = 3
        private const val LOAD_RETRY_MS = 4_000L

        const val ACTION_PAUSE = "com.tonbo2339.yomikura.offline.PAUSE"
        const val ACTION_RESUME = "com.tonbo2339.yomikura.offline.RESUME"
        const val ACTION_CANCEL = "com.tonbo2339.yomikura.offline.CANCEL"
        /** キューを空にしたあとの停止 (stopService の代わり。新しい開始と行き違っても、約束した startForeground を守る) */
        const val ACTION_STOP = "com.tonbo2339.yomikura.offline.STOP"

        /** 通知・帯から開く先 (MainActivity が保存キューの画面を開く) */
        const val ACTION_OPEN_QUEUE = "com.tonbo2339.yomikura.OPEN_QUEUE"

        /** 動いているサービスに、一時停止・再開・中止・停止を送る (止まっているときは何もしない) */
        fun send(ctx: Context, action: String) {
            if (action != ACTION_PAUSE && action != ACTION_RESUME && action != ACTION_CANCEL && action != ACTION_STOP) return
            if (!OfflineStatus.running) return
            try { ctx.startService(Intent(ctx, OfflineService::class.java).setAction(action)) } catch (e: Exception) { Diag.w(TAG, "send", e) }
        }

        /**
         * キューの保存を進める (作品のページ・リストから呼ぶ。前景の画面からだけ呼ぶこと)。始められなかったら false。
         * 始められなかった (バックグラウンド制限など) ときは、キューは順番待ちのまま残る。次にアプリを開いたときに続きから (resumeIfPending)
         */
        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, OfflineService::class.java))
            true
        } catch (e: Exception) {
            Diag.w(TAG, "start", e)
            false
        }

        /**
         * アプリを開いたとき (画面が前に出たとき): 保存の途中でサービスが止まっていたら (アプリが落ちた・Android の時間の上限・始められなかった)、続きから進める。
         * まだ終わっていない作品 (順番待ち・保存中・Wi-Fi 待ち) があるのに、サービスが動いていないときだけ始める。
         * 一時停止したまま止まった作品があるときは始めない (利用者が止めた保存を、勝手に再開しない。保存キューの画面の「再開」で続ける)
         */
        fun resumeIfPending(ctx: Context, queue: SaveQueue) {
            val active = queue.all().filter { it.status.active }
            if (!OfflineStatus.running && active.isNotEmpty() && active.none { it.status == SaveQueue.Status.PAUSED }) start(ctx)
        }
    }
}
