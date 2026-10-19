package com.tonbo2339.yomikura

import android.webkit.JavascriptInterface

/**
 * オフライン保存の窓口 (NHOffline)。書き込み (put*) と「あるか」の確認は、保存を進めるサイトのページから呼ぶ。
 * 中身を読む / 一覧 / 削除は、アプリ内ページ (yomikura.local) からだけ受け付ける。
 * JS からは名前と引数の数で呼ばれるので、メソッドの名前と形は変えない。
 */
class OfflineBridge(
    host: BridgeHost,
    private val offline: OfflineStore,
    private val saveQueue: SaveQueue,
    private val hostFilter: HostFilter,
) : GuardedBridge(host) {

    // ---- 保存した作品の読み書き ----

    @JavascriptInterface
    fun putEpisode(site: String, id: String, ep: String, json: String, t: String?): Boolean = trusted(t) && offline.putEpisode(site, id, ep, json)

    @JavascriptInterface
    fun putMeta(site: String, id: String, json: String, t: String?): Boolean = trusted(t) && offline.putMeta(site, id, json)

    /** 挿絵を取って保存する (アプリの中から取る。結果の JSON は ImageSaver を参照)。保存中のサイトのページから */
    @JavascriptInterface
    fun saveImage(site: String, id: String, url: String, referer: String, t: String?): String =
        if (trusted(t)) ImageSaver.save(offline, site, id, url, referer) { hostFilter.shouldBlock(it) } else "{}"

    @JavascriptInterface
    fun meta(site: String, id: String, t: String?): String = if (trusted(t)) offline.meta(site, id) else ""

    @JavascriptInterface
    fun episode(site: String, id: String, ep: String): String = forApp("") { offline.episode(site, id, ep) }

    @JavascriptInterface
    fun list(): String = forApp("[]") { offline.list() }

    @JavascriptInterface
    fun delete(site: String, id: String): Boolean = forApp(false) {
        saveQueue.remove("$site:$id") // 保存待ちや保存中の作品なら、キューからも外す
        host.queueChanged()
        offline.delete(site, id)
    }

    @JavascriptInterface
    fun deleteAll(): Boolean = host.appPageNow && host.dropOfflineAll()

    /** 読書画面で、上部バーを隠す / 出す (「スクロールで隠す」がオフなら隠さない) */
    @JavascriptInterface
    fun readerBar(hide: Boolean) {
        if (!host.appPageNow) return
        onUi { host.readerBar(hide) }
    }

    // ---- 保存キュー ----

    /** 保存キューに作品を入れる (作品のページから。json は SaveQueue.Job の形)。入った話の数を返す */
    @JavascriptInterface
    fun queueAdd(json: String, t: String?): Int {
        if (!trusted(t)) return 0
        val job = try { SaveQueue.decode("[$json]").firstOrNull() } catch (e: org.json.JSONException) { null } ?: return 0
        // 保存の作業用 WebView はトークンと保存の窓口を持つので、対応サイト以外の URL (http / https 以外も) の作品は入れない
        val scheme = SiteUrls.schemeOf(job.url)
        if ((scheme != "https" && scheme != "http") || !SiteUrls.isSupportedHost(SiteUrls.hostOf(job.url))) return 0
        return saveQueue.add(job).also { host.queueChanged() }
    }

    /** 保存キューの中身 (順番どおり)。アプリ内ページだけ */
    @JavascriptInterface
    fun queueList(): String = forApp("[]") { SaveQueue.encode(saveQueue.all()) }

    /** 保存中の作品の進みと、サービスが動いているか ({running, key, title, ok, total, paused, waitingWifi})。アプリ内ページだけ */
    @JavascriptInterface
    fun queueLive(): String = forApp("{}") { OfflineStatus.toJson() }

    @JavascriptInterface
    fun queueMove(key: String, delta: Int): Boolean = changeQueue { saveQueue.move(key, delta, OfflineStatus.live?.key) }

    /** キューから外す。保存中の作品なら、サービスが次の進みで中止する */
    @JavascriptInterface
    fun queueRemove(key: String): Boolean = changeQueue { saveQueue.remove(key) }

    @JavascriptInterface
    fun queueClearFinished(): Boolean = changeQueue { saveQueue.clearFinished() }

    /** 止まった・確認が必要だった作品を、もう一度順番待ちに入れて始める (保存済みの話は飛ばす)。アプリ内ページだけ */
    @JavascriptInterface
    fun queueRetry(key: String): Boolean = forApp(false) {
        val retry = saveQueue.all().firstOrNull { it.key == key && !it.status.active } ?: return@forApp false
        saveQueue.remove(key)
        saveQueue.add(retry.copy(status = SaveQueue.Status.WAITING, note = ""))
        host.queueChanged()
        host.startSaving()
        true
    }

    /** サービスへの操作: "pause" / "resume" / "cancel" (いま保存中の作品を中止)。アプリ内ページだけ */
    @JavascriptInterface
    fun queueControl(action: String): Boolean {
        if (!host.appPageNow) return false
        val a = when (action) {
            "pause" -> OfflineService.ACTION_PAUSE
            "resume" -> OfflineService.ACTION_RESUME
            "cancel" -> OfflineService.ACTION_CANCEL
            else -> return false
        }
        onUi { OfflineService.send(ctx, a) }
        return true
    }

    /**
     * 保存キューを前景サービスで進める。作品のページから、キューに入れたあとに呼ぶ。
     * 通知の許可が無いと、保存の進みが通知に出ない (保存自体は続く) ので、先に許可を求める。
     */
    @JavascriptInterface
    fun queueStart(t: String?): Boolean {
        if (!trusted(t)) return false
        host.startSaving()
        return true
    }

    /** 従量課金でない回線 (Wi-Fi など) につながっているか。「Wi-Fi のときだけ保存する」設定の判定に使う。 */
    @JavascriptInterface
    fun onUnmetered(): Boolean = Connectivity.unmetered(ctx)

    /** アプリ内ページからだけ受け付けて、キューを書き換え、画面の保存の帯に知らせる。 */
    private fun changeQueue(change: () -> Unit): Boolean = forApp(false) {
        change()
        host.queueChanged()
        true
    }
}
