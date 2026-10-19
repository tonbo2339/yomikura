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
    private var total = 0                      // その作品で保存する話の数 (通知の分母)
    private var title = ""                     // その作品で保存中の話の題名 (通知に出す)
    @Volatile private var userPaused = false   // 利用者が一時停止した (JS の窓口のスレッドからも読む)
    @Volatile private var cancelRequested = false // 利用者が中止した (同上)
    @Volatile private var removedRequested = false // 保存中の作品がキューから外された (同上)。「元に戻す」で戻されたら、中止にせず順番待ちに戻す
    private var destroyed = false              // onDestroy / onTimeout のあと (遅れて届いた知らせで、次の作品を始めない)
    private val notifier by lazy { OfflineNotifier(this) }
    private val net by lazy { NetworkWatch(this, main, netListener) } // 回線につながっているか (切れている間は、失敗にせず待つ) / 従量課金か
    private var foreground = false             // 前景サービスに入ったあと (入る前に pump すると、startForeground の前に閉じてしまう)

    // 一定時間、進みも待ちもない (JS が止まった) ときは、その作品を打ち切る
    private val stall = Runnable { finishJob(JSONObject().put("error", "応答がないので止めました").toString()) }

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        queue.recoverAfterRestart() // 前回の実行が途中で止まっていたら、その作品は順番待ちに戻す
        net.start() // 最初のページを読む前に、いまの回線を調べておく
        OfflineStatus.publish(null, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { userPaused = true; syncStatus() }
            ACTION_RESUME -> { userPaused = false; syncStatus() }
            ACTION_CANCEL -> cancel()
        }
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
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
        if (job != null) { cancelRequested = true; return }
        // 保存中の作品が無く、待っているだけ (Wi-Fi・回線・一時停止) なら、待っている作品を止める (通知には待っている作品の数が出ているため)
        queue.all().filter { it.status == SaveQueue.Status.WAIT_WIFI || it.status == SaveQueue.Status.WAITING }
            .forEach { j -> queue.update(j.key) { it.copy(status = SaveQueue.Status.STOPPED, note = "中止しました。保存した話はそのまま残ります") } }
    }

    /** Android 15 以降の時間の上限に当たったとき。いま保存中の作品は順番待ちに戻し、サービスを閉じる (アプリを開けば続きから) */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Diag.w(TAG, "timeout")
        // 一時停止中なら一時停止のまま残す (次に開いたとき、勝手に保存を再開しない)
        job?.let { queue.update(it.key) { j -> j.copy(status = if (userPaused) SaveQueue.Status.PAUSED else SaveQueue.Status.WAITING, note = "") } }
        shutdown()
        notifier.done("保存を一時的に止めました", "Android の制限です。アプリを開くと続きから保存します", null) // 続きは resumeIfPending
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        net.stop()
        shutdown()
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
        if (web != null) { refreshNotice(); return }
        val blocked = wifiOnlySetting() && net.metered
        // NSFW モード中は R18 の作品を保存しない (R18 のサイトへアクセスしないため)。止めた作品は、モードを切ってから「もう一度」で保存できる
        val nsfw = NHData.nsfwOn(NHData.settings(this))
        if (nsfw) queue.all().filter { it.site == "narou18" && (it.status == SaveQueue.Status.WAITING || it.status == SaveQueue.Status.WAIT_WIFI) }
            .forEach { j -> queue.update(j.key) { it.copy(status = SaveQueue.Status.STOPPED, note = Edition.R18_OFF_WHY + "保存しません") } }
        queue.all().forEach { j ->
            val wait = blocked && !j.mobileOk
            if (j.status == SaveQueue.Status.WAITING && wait) queue.update(j.key) { it.copy(status = SaveQueue.Status.WAIT_WIFI) }
            if (j.status == SaveQueue.Status.WAIT_WIFI && !wait) queue.update(j.key) { it.copy(status = SaveQueue.Status.WAITING) }
        }
        val next = queue.next()
        if (next != null) {
            // 回線が無い間は始めない (作品のページを読めずに 3 回失敗して、順番待ちが次々に「止めました」になる)。つながったら onLinkChanged が呼び直す
            if (!net.online) { refreshNotice(); return }
            startJob(next)
            return
        }
        if (queue.all().any { it.status == SaveQueue.Status.WAIT_WIFI }) { refreshNotice(); return } // Wi-Fi になるのを待つ
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        queue.update(j.key) { it.copy(status = SaveQueue.Status.RUNNING, note = "") }
        syncStatus()
        web = try {
            makeRunner(j)
        } catch (e: Exception) {
            // WebView を作れない (更新の途中・無効にされているなど)。落ちないよう、順番待ちに戻して止める (あとで「再開」すれば続きから)
            Diag.e(TAG, "webview unavailable", e)
            queue.update(j.key) { it.copy(status = SaveQueue.Status.WAITING, note = "ブラウザの部品 (WebView) を使えなかったので止めました") }
            job = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
            webViewClient = object : WebViewClient() {
                private var injected = false
                private var failed = false // 読み込みに失敗して、やり直しを待っている
                private var tries = 0

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    // 対応サイト以外のページは開かせない (保存の窓口とトークンを、保存する作品の外へ渡さないため)
                    if (request.isForMainFrame && !SiteUrls.isSupportedHost(request.url.host.orEmpty())) {
                        // WebView の別のスレッドから呼ばれるので、作品の後始末は画面のスレッドで行う
                        main.post { if (web === view) finishJob(JSONObject().put("error", "対応していない URL です").toString()) }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return if (blocker.shouldBlock(request.url.host)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
                }

                // 作品のページの中から別のページへは行かせない (保存の窓口を、保存する作品の外へ広げないため)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

                override fun onPageFinished(view: WebView, url: String?) {
                    if (injected || failed) return
                    // 対応サイトの https のページにだけ入れる (別のページへ飛ばされていたら、合言葉を渡さず打ち切る)
                    val u = Uri.parse(url.orEmpty())
                    if (u.scheme != "https" || !SiteUrls.isSupportedHost(u.host.orEmpty())) {
                        injected = true
                        main.post { if (web === view) finishJob(JSONObject().put("error", "対応していない URL です").toString()) }
                        return
                    }
                    injected = true
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
            // 対応サイトの URL だけ開く (javascript: などは shouldInterceptRequest を通らないので、ここで落とす)
            val url = SiteUrls.safeNavUrl(j.url)?.takeIf { SiteUrls.isSupportedHost(SiteUrls.hostOf(it)) }
            if (url == null) {
                main.post { if (web === this) finishJob(JSONObject().put("error", "対応していない URL です").toString()) }
                return@apply
            }
            loadUrl(url)
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
        val done = status == SaveQueue.Status.DONE
        if (!again) notifier.done(
            if (done) "保存しました: ${cur.title}" else "保存を止めました: ${cur.title}",
            "$ok 話を保存しました。$note".trimEnd(),
            cur.title,
        )
        pump()
    }

    /** 作品の JS を止めて、WebView を片付ける。止めるだけで、キューの状態は変えない */
    private fun endRunner() {
        main.removeCallbacks(stall)
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
    private fun waitingWifi(): Boolean = wifiOnlySetting() && net.metered && job?.mobileOk != true

    private fun wifiOnlySetting(): Boolean =
        NHData.settings(this).optBoolean("offlineWifiOnly", false)

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
        if (destroyed) return // 閉じたあとに届いた回線の知らせで、保存中の通知を出し直さない
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

        @JavascriptInterface
        fun putEpisode(site: String, id: String, ep: String, json: String, t: String?): Boolean =
            authed(t) && mine(site, id) && store.putEpisode(site, id, ep, json)

        @JavascriptInterface
        fun putMeta(site: String, id: String, json: String, t: String?): Boolean =
            authed(t) && mine(site, id) && store.putMeta(site, id, json)

        @JavascriptInterface
        fun meta(site: String, id: String, t: String?): String = if (authed(t)) store.meta(site, id) else ""

        /** 挿絵を取って保存する (ImageSaver を参照)。作品の保存の WebView から */
        @JavascriptInterface
        fun saveImage(site: String, id: String, url: String, referer: String, t: String?): String =
            if (authed(t) && mine(site, id)) ImageSaver.save(store, site, id, url, referer) { blocker.shouldBlock(it) } else "{}"

        @JavascriptInterface
        fun onUnmetered(): Boolean = Connectivity.unmetered(this@OfflineService)

        @JavascriptInterface
        fun stopRequested(t: String?): Boolean = if (authed(t)) cancelRequested || removedRequested else true

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
        private const val REST_NOTE = "このサイトにアクセスを止められたばかりなので、止めました。サイトを開いて確認画面を通し、少し待ってから、もう一度保存してください"
        private const val LOAD_TRIES = 3
        private const val LOAD_RETRY_MS = 4_000L

        const val ACTION_PAUSE = "com.tonbo2339.yomikura.offline.PAUSE"
        const val ACTION_RESUME = "com.tonbo2339.yomikura.offline.RESUME"
        const val ACTION_CANCEL = "com.tonbo2339.yomikura.offline.CANCEL"

        /** 通知・帯から開く先 (MainActivity が保存キューの画面を開く) */
        const val ACTION_OPEN_QUEUE = "com.tonbo2339.yomikura.OPEN_QUEUE"

        /** 動いているサービスに、一時停止・再開・中止を送る (止まっているときは何もしない) */
        fun send(ctx: Context, action: String) {
            if (action != ACTION_PAUSE && action != ACTION_RESUME && action != ACTION_CANCEL) return
            if (!OfflineStatus.running) return
            try { ctx.startService(Intent(ctx, OfflineService::class.java).setAction(action)) } catch (e: Exception) { Diag.w(TAG, "send", e) }
        }

        /** キューの保存を進める (作品のページ・リストから呼ぶ。前景の画面からだけ呼ぶこと) */
        fun start(ctx: Context) {
            // 始められなかった (バックグラウンド制限など) ときは、キューは順番待ちのまま残る。次にアプリを開いたときに続きから (resumeIfPending)
            try { ctx.startForegroundService(Intent(ctx, OfflineService::class.java)) } catch (e: Exception) { Diag.w(TAG, "start", e) }
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
