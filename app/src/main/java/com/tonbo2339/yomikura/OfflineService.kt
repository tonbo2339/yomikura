package com.tonbo2339.yomikura

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.net.Uri
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
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

    private val main = Handler(Looper.getMainLooper())
    private val queue by lazy { SaveQueue(File(filesDir, SaveQueue.FILE_NAME)) }
    private val store by lazy { OfflineStore(File(filesDir, "offline")) }
    private val blocker by lazy { Edition.hostFilter(this) }

    private var web: WebView? = null
    @Volatile private var job: SaveQueue.Job? = null     // いま保存している作品
    private var ok = 0                         // その作品で保存できた話の数
    private var total = 0                      // その作品で保存する話の数 (通知の分母)
    private var title = ""                     // その作品で保存中の話の題名 (通知に出す)
    private var userPaused = false             // 利用者が一時停止した
    private var cancelRequested = false        // 利用者が中止した
    private var metered = false                // いまの回線が従量課金か
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var foreground = false             // 前景サービスに入ったあと (入る前に pump すると、startForeground の前に閉じてしまう)
    private var doneCount = 0                  // 終わりのお知らせの番号 (同時に複数出ても消えないように)

    // 一定時間、進みも待ちもない (JS が止まった) ときは、その作品を打ち切る
    private val stall = Runnable { finishJob(JSONObject().put("error", "応答がないので止めました").toString()) }

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        queue.recoverAfterRestart() // 前回の実行が途中で止まっていたら、その作品は順番待ちに戻す
        // 最初のページを読む前に、いまの回線を調べておく (回線の知らせが届くまで metered = false のままだと、Wi-Fi のときだけの設定でも最初のページをモバイルで読んでしまう)
        metered = runCatching { !unmetered(this) }.getOrDefault(false)
        OfflineStatus.publish(null, true)
        createChannels()
        watchNetwork()
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
        queue.all().filter { it.status == SaveQueue.Status.WAIT_WIFI }
            .forEach { j -> queue.update(j.key) { it.copy(status = SaveQueue.Status.STOPPED, note = "中止しました。保存した話はそのまま残ります") } }
    }

    /** Android 15 以降の時間の上限に当たったとき。いま保存中の作品は順番待ちに戻し、サービスを閉じる (アプリを開けば続きから) */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Diag.w(TAG, "timeout")
        job?.let { queue.update(it.key) { j -> j.copy(status = SaveQueue.Status.WAITING, note = "") } }
        endRunner()
        notifyDone("保存を一時的に止めました", "Android の制限です。アプリを開くと続きから保存します", null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        netCallback = null
        endRunner()
        OfflineStatus.publish(null, false)
        super.onDestroy()
    }

    /**
     * 次の作品を始める。「Wi-Fi のときだけ」で従量課金の回線のときは、「今回だけモバイル」を選んだ作品だけ始める
     * (ほかは WAIT_WIFI のまま、作品のページも読まずに待つ)。保存する作品も待つ作品もなくなったら、サービスを閉じる。
     */
    private fun pump() {
        if (web != null) { refreshNotice(); return }
        val blocked = wifiOnlySetting() && metered
        // NSFW モード中は R18 の作品を保存しない (R18 のサイトへアクセスしないため)。止めた作品は、モードを切ってから「もう一度」で保存できる
        val nsfw = NHData.nsfwOn(NHData.obj(this).optJSONObject("settings"))
        if (nsfw) queue.all().filter { it.site == "narou18" && (it.status == SaveQueue.Status.WAITING || it.status == SaveQueue.Status.WAIT_WIFI) }
            .forEach { j -> queue.update(j.key) { it.copy(status = SaveQueue.Status.STOPPED, note = Edition.R18_OFF_WHY + "保存しません") } }
        queue.all().forEach { j ->
            val wait = blocked && !j.mobileOk
            if (j.status == SaveQueue.Status.WAITING && wait) queue.update(j.key) { it.copy(status = SaveQueue.Status.WAIT_WIFI) }
            if (j.status == SaveQueue.Status.WAIT_WIFI && !wait) queue.update(j.key) { it.copy(status = SaveQueue.Status.WAITING) }
        }
        val next = queue.next()
        if (next != null) { startJob(next); return }
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
        queue.update(j.key) { it.copy(status = SaveQueue.Status.RUNNING, note = "") }
        syncStatus()
        web = makeRunner(j)
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
            addJavascriptInterface(Bridge(), "NHOffline")
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
                    // 回線を切り替えた直後などは失敗しやすいので、少し待ってやり直す
                    failed = true
                    if (++tries < LOAD_TRIES) main.postDelayed({ if (web === view) { failed = false; view.loadUrl(j.url) } }, LOAD_RETRY_MS)
                    else if (web === view) finishJob(JSONObject().put("error", "ページを開けませんでした").toString())
                }
            }
            loadUrl(j.url)
        }
    }

    /** 今の作品を終える。JS の結果 (JSON) から、キューの状態とお知らせを決める */
    private fun finishJob(json: String) {
        val cur = job ?: return
        endRunner()
        val r = try { JSONObject(json) } catch (e: Exception) { JSONObject().put("error", "結果を読めませんでした") }
        val status: SaveQueue.Status
        val note: String
        when {
            r.has("error") -> { status = SaveQueue.Status.STOPPED; note = r.optString("error") }
            cancelRequested -> { status = SaveQueue.Status.STOPPED; note = "中止しました。保存した話はそのまま残ります" }
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
        val latest = queue.all().firstOrNull { it.key == cur.key }
        val again = status == SaveQueue.Status.DONE && latest != null && latest.eps.size > cur.eps.size
        queue.update(cur.key) { it.copy(status = if (again) SaveQueue.Status.WAITING else status, note = if (again) "" else note) }
        // アクセスを止められたら、同じサイトの順番待ちの作品も止める (止められた直後に、続けて取りに行かない)
        if (status == SaveQueue.Status.NEEDS_CHECK) {
            queue.all().filter { it.site == cur.site && it.key != cur.key && (it.status == SaveQueue.Status.WAITING || it.status == SaveQueue.Status.WAIT_WIFI) }
                .forEach { j -> queue.update(j.key) { it.copy(status = SaveQueue.Status.NEEDS_CHECK, note = REST_NOTE) } }
        }
        job = null
        val done = status == SaveQueue.Status.DONE
        if (!again) notifyDone(
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
    private fun waitingWifi(): Boolean = wifiOnlySetting() && metered && job?.mobileOk != true

    private fun wifiOnlySetting(): Boolean =
        NHData.obj(this).optJSONObject("settings")?.optBoolean("offlineWifiOnly", false) ?: false

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                main.post {
                    metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    syncStatus()
                    if (foreground) pump()
                }
            }
        }
        netCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    /** 前景サービスとして始める。始められなかったら false */
    private fun goForeground(): Boolean = try {
        val n = buildNotice()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTICE_ID, n)
        true
    } catch (e: Exception) {
        Diag.e(TAG, "foreground", e)
        false
    }

    private fun refreshNotice() {
        getSystemService(NotificationManager::class.java).notify(NOTICE_ID, buildNotice())
        val cur = job
        OfflineStatus.publish(cur?.let { OfflineStatus.Live(it.key, it.title, ok, total, userPaused, waitingWifi()) }, true)
    }

    /** 保存中の 1 本の通知。進み・Wi-Fi 待ち・一時停止・残りの数を出す */
    private fun buildNotice(): Notification {
        val cur = job
        val waiting = queue.all().count { it.status == SaveQueue.Status.WAITING }
        val text = when {
            cur == null && waiting == 0 && queue.all().any { it.status == SaveQueue.Status.WAIT_WIFI } ->
                "Wi-Fi を待っています ・ ${queue.all().count { it.status == SaveQueue.Status.WAIT_WIFI }} 作品"
            cur == null -> "保存キューは空です"
            userPaused -> "一時停止中 ・ $ok / $total 話"
            waitingWifi() -> "Wi-Fi を待っています ・ $ok / $total 話"
            else -> "$ok / $total 話" + if (title.isNotEmpty() && title != cur.title) " ・ $title" else ""
        }
        val b = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(if (cur != null) "保存中: ${cur.title}" else "オフライン保存")
            .setContentText(text)
            .setSubText(if (waiting > 0) "あと $waiting 作品" else null)
            .setContentIntent(openAppIntent(REQ_OPEN))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), ok, cur == null)
        b.addAction(0, if (userPaused) "再開" else "一時停止", if (userPaused) actionIntent(ACTION_RESUME, REQ_RESUME) else actionIntent(ACTION_PAUSE, REQ_PAUSE))
        b.addAction(0, "中止", actionIntent(ACTION_CANCEL, REQ_CANCEL))
        return b.build()
    }

    private fun notifyDone(head: String, text: String, sub: String?) {
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(head)
            .setContentText(text)
            .setSubText(sub)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(REQ_OPEN))
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID_BASE + (++doneCount % 50), n)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "オフライン保存の進み", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "オフライン保存のお知らせ", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openAppIntent(req: Int): PendingIntent =
        PendingIntent.getActivity(
            this, req, Intent(this, MainActivity::class.java).setAction(ACTION_OPEN_QUEUE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun actionIntent(action: String, req: Int): PendingIntent =
        PendingIntent.getService(
            this, req, Intent(this, OfflineService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 作品の JS から見える窓口 (NHOffline)。書き込みは OfflineStore、止める・待つ・進みは、この中の状態で答える。
     * 引数の数は core.js の NH.off と合わせてある (合わないと JS 側で「Method not found」になる)。
     * 合言葉 (t) は、保存のページ本体 (メインフレーム) にだけ渡す。広告の iframe などから呼ばれても、合言葉が無いので何もしない。
     */
    private inner class Bridge {
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
        fun onUnmetered(): Boolean = unmetered(this@OfflineService)

        @JavascriptInterface
        fun stopRequested(t: String?): Boolean = if (authed(t)) cancelRequested else true

        /** 一時停止・Wi-Fi 待ちのあいだ true。待っている間は打ち切らないように、止まっていない印を付ける */
        @JavascriptInterface
        fun waiting(t: String?): Boolean {
            if (!authed(t)) return false
            main.post { main.removeCallbacks(stall); main.postDelayed(stall, STALL_MS) }
            return userPaused || waitingWifi()
        }

        /** 1 話ごと。json = {ok, total, title} */
        @JavascriptInterface
        fun progress(json: String, t: String?) {
            if (!authed(t)) return
            main.post {
                val r = try { JSONObject(json) } catch (e: Exception) { return@post }
                ok = r.optInt("ok", ok)
                title = r.optString("title", title)
                // 保存中の作品がキューから外された (作品の保存を消した・キューを空にした) ら、続きを保存しない
                job?.let { cur -> if (!queue.contains(cur.key)) cancelRequested = true }
                main.removeCallbacks(stall)
                main.postDelayed(stall, STALL_MS)
                refreshNotice()
            }
        }

        /** 作品の終わり。json は JS の結果 (終わり方と件数) */
        @JavascriptInterface
        fun finished(json: String, t: String?) {
            if (!authed(t)) return
            main.post { finishJob(json) }
        }
    }

    companion object {
        private const val TAG = "Yomikura.offline"
        private const val CH_PROGRESS = "offline_progress"
        private const val CH_DONE = "offline_done"
        private const val NOTICE_ID = 4101
        private const val DONE_ID_BASE = 4200
        private const val STALL_MS = 10 * 60_000L
        private const val REST_NOTE = "このサイトにアクセスを止められたばかりなので、止めました。サイトを開いて確認画面を通し、少し待ってから、もう一度保存してください"
        private const val LOAD_TRIES = 3
        private const val LOAD_RETRY_MS = 4_000L
        private const val REQ_OPEN = 0
        private const val REQ_PAUSE = 1
        private const val REQ_RESUME = 2
        private const val REQ_CANCEL = 3

        const val ACTION_PAUSE = "com.tonbo2339.yomikura.offline.PAUSE"
        const val ACTION_RESUME = "com.tonbo2339.yomikura.offline.RESUME"
        const val ACTION_CANCEL = "com.tonbo2339.yomikura.offline.CANCEL"

        /** 通知・帯から開く先 (MainActivity が保存キューの画面を開く) */
        const val ACTION_OPEN_QUEUE = "com.tonbo2339.yomikura.OPEN_QUEUE"

        /** 従量課金でない回線 (Wi-Fi など) につながっているか。「Wi-Fi のときだけ保存する」の判定 (画面とサービスで共通) */
        fun unmetered(ctx: Context): Boolean {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

        /** 動いているサービスに、一時停止・再開・中止を送る (止まっているときは何もしない) */
        fun send(ctx: Context, action: String) {
            if (action != ACTION_PAUSE && action != ACTION_RESUME && action != ACTION_CANCEL) return
            if (!OfflineStatus.running) return
            try { ctx.startService(Intent(ctx, OfflineService::class.java).setAction(action)) } catch (e: Exception) { Diag.w(TAG, "send", e) }
        }

        /** キューの保存を進める (作品のページ・リストから呼ぶ。前景の画面からだけ呼ぶこと) */
        fun start(ctx: Context) {
            // 始められなかった (バックグラウンド制限など) ときは、キューは順番待ちのまま残る。次にアプリを開いたときに続きから
            try { ctx.startForegroundService(Intent(ctx, OfflineService::class.java)) } catch (e: Exception) { Diag.w(TAG, "start", e) }
        }
    }
}
