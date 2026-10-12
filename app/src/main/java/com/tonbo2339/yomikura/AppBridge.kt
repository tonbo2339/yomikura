package com.tonbo2339.yomikura

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JS の窓口 (StoreBridge = NHStore、OfflineBridge = NHOffline) が画面 (MainActivity) に頼む操作。
 * 窓口は JS のスレッドから呼ばれるので、画面に触るものは窓口の側で runOnUiThread してから呼ぶ (「UI」と書いたもの)。
 */
interface BridgeHost {
    val activity: AppCompatActivity
    /** いま出ているのがアプリ内ページか (どのスレッドからでも読める) */
    val appPageNow: Boolean
    /** いま出ているのが対応サイトの https かアプリ内ページか (TrustGate.Page.trusted) */
    val trustedPageNow: Boolean
    val collectingNow: Boolean
    val collectProgressText: String
    val updater: Updater
    val launcherIcon: LauncherIcon

    /** UI: WebView の今の URL がアプリ内ページか */
    fun onAppPage(): Boolean
    /** UI: WebView の User-Agent */
    fun userAgent(): String
    /** どのスレッドからでも: アプリ内ページの JS を実行する (画面が無い・アプリ内ページでなければ何もしない) */
    fun evalJs(script: String)
    /** どのスレッドからでも: window.<fn>(JSON の文字列) を呼ぶ */
    fun emitJs(fn: String, json: JSONObject)
    fun nsfwOn(): Boolean
    /** UI: 更新の確認を始める (手動) */
    fun collectNow()
    fun startUrl(): String
    /** UI */
    fun finishLaterBack(result: String)
    /** UI */
    fun openSystemSettings(kind: String)
    /** UI: 通知の許可を求める */
    fun requestNotifyPermission()
    /** UI */
    fun clearWebCache()
    /** UI: 保存データとオフライン保存を消して初回の画面へ */
    fun wipeAll()
    /** UI: 書き出し先の画面を出す (mode = one / many) */
    fun startExport(mode: String, files: List<Pair<String, String>>)
    /** UI */
    fun startImport()
    /** UI */
    fun launchInstaller(apk: java.io.File)
    /** UI */
    fun onDataChanged()
    fun queueChanged()
    /** どのスレッドからでも: 保存のサービスを始める */
    fun startSaving()
    fun dropOfflineAll(): Boolean
    /** UI: 読書画面の上部バーの出し入れ */
    fun readerBar(hide: Boolean)
}

/**
 * JS から使う窓口 (NHStore)。保存データはサイト (origin) をまたいで共有するので、JSON 文字列をそのまま SharedPreferences に置く。
 * 端末の操作や更新など、サイトのページから使わせたくないものはアプリ内ページからだけ受け付ける。
 * JS からは名前と引数の数で呼ばれるので、メソッドの名前と形は変えない。
 */
class StoreBridge(private val host: BridgeHost) {
    private val ctx get() = host.activity
    /** アプリの更新のダウンロード・インストールを同時に 2 つ走らせない (ボタンの連打対策) */
    private val installing = AtomicBoolean(false)

    /** 更新の確認を始める (リスト画面の更新ボタン)。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun collectNow() {
        ctx.runOnUiThread { if (host.onAppPage()) host.collectNow() }
    }

    /** 手動の「更新を確認」を押せるまで、あと何ミリ秒か (0 = 押せる。CollectCooldown)。リストのボタンの表示に使う。アプリ内ページだけ */
    @JavascriptInterface
    fun collectWaitMs(): Long =
        if (host.appPageNow) CollectCooldown.remainingMs(NHData.obj(ctx).optLong("lastCollect", 0L), System.currentTimeMillis()) else 0L

    /**
     * ハーメルンの作品が R-18 かを、小説情報のページを読んで判別する (HamelnRating)。分かったら作品の r18 を書き、リスト画面を描き直す。
     * NSFW モード中は R18 のドメインへ行くおそれがあるので、通信しない。アプリ内ページからだけ。
     */
    @JavascriptInterface
    fun fillHamelnR18(idsJson: String) {
        ctx.runOnUiThread {
            if (!host.onAppPage() || host.nsfwOn()) return@runOnUiThread
            val ids = parseIds(idsJson) ?: return@runOnUiThread
            HamelnRating.fill(ids, host.userAgent(), { id, r18 ->
                NHData.update(ctx) { data ->
                    val a = data.optJSONArray("readLater") ?: return@update
                    for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("key") == "hameln:$id" }?.put("r18", r18)
                }
            }, {
                host.evalJs("window.nhSubsFilled && nhSubsFilled()")
            }, { SiteRest.isResting(ctx, "hameln", auto = true) }, { SiteRest.mark(ctx, "hameln") })
        }
    }

    /**
     * サイトが分からない R18 の作品 (ids: JSON の配列) を、作品ページを読んで判別する (R18Subs)。
     * 分かったらリストと閲覧履歴の同じ作品に sub を書き、終わったらリスト画面に知らせる (nhSubsFilled)。アプリ内ページからだけ。
     */
    @JavascriptInterface
    fun fillR18Subs(idsJson: String) {
        ctx.runOnUiThread {
            if (!host.onAppPage() || host.nsfwOn()) return@runOnUiThread // NSFW モード中は R18 の作品ページを読みに行かない
            val ids = parseIds(idsJson) ?: return@runOnUiThread
            var found = 0
            R18Subs.fill(ids, host.userAgent(), { id, sub ->
                val key = "narou18:$id"
                NHData.update(ctx) { data ->
                    for (name in listOf("readLater", "history")) {
                        val a = data.optJSONArray(name) ?: continue
                        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("key") == key }?.put("sub", sub)
                    }
                }
                found++
            }, {
                if (found > 0) host.evalJs("window.nhSubsFilled && nhSubsFilled()")
            }, { SiteRest.isResting(ctx, "narou18", auto = true) }, { SiteRest.mark(ctx, "narou18") })
        }
    }

    /** JS から渡された作品 ID の配列 (JSON)。読めなければ null */
    private fun parseIds(json: String): List<String>? =
        try { JSONArray(json).let { arr -> List(arr.length()) { arr.optString(it) } } } catch (e: org.json.JSONException) { null }

    /** 更新を確認中か (リスト画面を開いたときに、更新ボタンを回すか決める)。アプリ内ページだけ */
    @JavascriptInterface
    fun collecting(): Boolean = host.appPageNow && host.collectingNow

    /** 確認の進み具合 (「2 / 4」)。確認していなければ空。アプリ内ページだけ */
    @JavascriptInterface
    fun progressNow(): String = if (host.appPageNow) host.collectProgressText else ""

    /** 起動時の自動チェックが終わったあとに開くページ。アプリ内ページだけ */
    @JavascriptInterface
    fun startUrl(): String = if (host.appPageNow) host.startUrl() else ""

    /** 外部のリンクの作品を「後で読む」に追加したあと、読んでいた作品に戻る (openLink で選んだときだけ動く)。対応サイトのページから。 */
    @JavascriptInterface
    fun laterBack(result: String) {
        if (!host.trustedPageNow) return
        ctx.runOnUiThread { host.finishLaterBack(result) }
    }

    /** Android の設定画面を開く (links = 対応リンク)。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun openSystem(kind: String) {
        ctx.runOnUiThread { if (host.onAppPage()) host.openSystemSettings(kind) }
    }

    /** 裏での確認の予定 (BgCheck.plan)。設定画面に出す。アプリ内ページだけ */
    @JavascriptInterface
    fun bgPlan(): String = if (host.appPageNow) BgCheck.plan(ctx) else ""

    /** 裏での確認を今すぐ動かす (設定の「今すぐ試す」)。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun bgRunNow() {
        ctx.runOnUiThread { if (host.onAppPage()) BgCheck.startNow(ctx) }
    }

    /** 通知の許可を求める (Android 13 以降)。結果は画面の nhPlanChanged() で知らせる。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun requestNotify() {
        ctx.runOnUiThread {
            if (host.onAppPage() && Build.VERSION.SDK_INT >= 33 && !BgCheck.notifyAllowed(ctx)) host.requestNotifyPermission()
        }
    }

    /**
     * アプリ内の WebView で GitHub にログインしているか。ログイン済みならユーザー名 (分からなければ "yes")、未ログインなら ""。
     * サイトのページから読まれないよう、アプリ内ページからだけ答える。
     */
    @JavascriptInterface
    fun githubUser(): String = if (host.appPageNow) LoginCookies.githubUser() else ""

    /**
     * 各サイトにログインしているか (Cookie の有無で見る。ログイン切れまでは分からない)。{"narou":true,"hameln":false,"kakuyomu":false}。
     * アプリ内ページからだけ答える。
     */
    @JavascriptInterface
    fun siteLogins(): String {
        if (!host.appPageNow) return JSONObject().toString()
        val r = LoginCookies.state()
        LoginCookies.sync(ctx, r) // 設定が表示するのと同じ状態を、巡回も使う
        return r.toString()
    }

    /** そのサイト (narou は R18 も共通) のログイン用 Cookie を消す。アプリ内ページからだけ。 */
    @JavascriptInterface
    fun logoutSite(site: String): Boolean {
        if (!host.appPageNow) return false
        return LoginCookies.expire(site)
    }

    /**
     * サイトの公式のログアウトを裏で行い、そのあとアプリの Cookie も消す。
     * 結果は nhLogoutResult({site, ok, message, page}) で画面へ。page = 失敗したときに画面で開く公式のページ (押すのは利用者)。
     * 公式の手順が無いサイトは、アプリの Cookie だけ消して成功を返す。
     */
    @JavascriptInterface
    fun logoutOfficial(site: String) {
        if (!host.appPageNow || site !in LoginCookies.SITES) return
        val page = OfficialLogout.pageOf(site)
        if (page == null) {
            LoginCookies.expire(site)
            return host.emitJs("nhLogoutResult", JSONObject().put("site", site).put("ok", true).put("message", ""))
        }
        OfficialLogout.run(ctx, site) { ok, msg ->
            // 公式の結果にかかわらず、アプリの Cookie は消す (待っている間にほかの画面へ移っていても消す)
            LoginCookies.expire(site)
            host.emitJs("nhLogoutResult", JSONObject().put("site", site).put("ok", ok).put("message", msg).put("page", page))
        }
    }

    /** ログイン情報 (Cookie) をすべて消す。Cloudflare の確認を通した印 (cf_clearance) は残す。GitHub のログインも消える。 */
    @JavascriptInterface
    fun clearLogins(): Boolean {
        if (!host.appPageNow) return false
        LoginCookies.clearAll()
        return true
    }

    /** ページの一時ファイル (キャッシュ) を消す。ログインや保存データは消えない。 */
    @JavascriptInterface
    fun clearWebCache(): Boolean {
        if (!host.appPageNow) return false
        ctx.runOnUiThread { host.clearWebCache() }
        return true
    }

    /**
     * 保存データ (リスト・設定・履歴・読書位置など) とオフライン保存をすべて消し、初回の画面に戻る。ログイン情報は消さない。
     * アプリ内ページからだけ。
     */
    @JavascriptInterface
    fun wipeAll(): Boolean {
        if (!host.appPageNow) return false
        ctx.runOnUiThread { host.wipeAll() }
        return true
    }

    /**
     * バックアップを書き出す。mode = "one" (1 ファイル。files の先頭) / "many" (フォルダを選んで全部)。
     * files = [{name, text}]。保存先の画面を出し、結果は nhBackupResult(JSON) で返す。アプリ内ページからだけ。
     */
    @JavascriptInterface
    fun exportFiles(mode: String, filesJson: String) {
        ctx.runOnUiThread {
            if (!host.onAppPage()) return@runOnUiThread
            val files = try {
                val arr = JSONArray(filesJson)
                List(arr.length()) { arr.getJSONObject(it).let { o -> o.getString("name") to o.getString("text") } }
            } catch (e: org.json.JSONException) {
                return@runOnUiThread
            }
            if (files.isEmpty()) return@runOnUiThread
            host.startExport(mode, files)
        }
    }

    /** バックアップのファイルを選んで読み込む (複数可)。中身は画面の nhImport(JSON の配列) に渡す。アプリ内ページからだけ。 */
    @JavascriptInterface
    fun importFiles() {
        ctx.runOnUiThread { if (host.onAppPage()) host.startImport() }
    }

    /** アプリのバージョン (設定画面の一番下に出す)。アプリ内ページだけ */
    @JavascriptInterface
    fun appVersion(): String = if (host.appPageNow) BuildConfig.VERSION_NAME else ""


    /** アプリの更新に GitHub のログインが要るか (自分用の版は非公開のリポジトリなので要る)。アプリ内ページだけ */
    @JavascriptInterface
    fun updateNeedsLogin(): Boolean = host.appPageNow && Edition.UPDATE_NEEDS_LOGIN

    /** ⋮ メニューの項目と、出すかどうか (MenuItems.toJson)。設定画面がスイッチを並べるのに使う。アプリ内ページだけ */
    @JavascriptInterface
    fun menuItems(): String =
        if (host.appPageNow) MenuItems.toJson(NHData.obj(ctx).optJSONObject("settings") ?: JSONObject()).toString() else "[]"

    /** アプリの更新を確認する。結果は画面の nhUpdate(JSON) へ返す。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun checkUpdate() {
        ctx.runOnUiThread {
            if (!host.onAppPage()) return@runOnUiThread
            if (BuildConfig.DEBUG) {
                emitUpdate(JSONObject().put("state", "error").put("message", "debug 版はアプリ内の更新を使えません (リリース版とは別のアプリです)"))
                return@runOnUiThread
            }
            val ua = host.userAgent()
            Thread {
                val r = try {
                    val c = host.updater.check(ua, BuildConfig.VERSION_NAME)
                    JSONObject().put("state", c.state).put("latest", c.latest).put("message", c.message)
                } catch (e: Exception) {
                    JSONObject().put("state", "error").put("message", "確認できませんでした (${e.message ?: e.javaClass.simpleName})")
                }
                emitUpdate(r)
            }.start()
        }
    }

    /** 最後に確認で見つけた新しい版をダウンロードして、インストールを始める。連打しても同時に 2 つは走らせない。 */
    @JavascriptInterface
    fun installUpdate() {
        ctx.runOnUiThread {
            if (!host.onAppPage()) return@runOnUiThread
            // 「このアプリからのインストール」を許可していなければ、先にその設定画面へ (許可したらもう一度押してもらう)
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                emitUpdate(JSONObject().put("state", "permission"))
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                return@runOnUiThread
            }
            if (!installing.compareAndSet(false, true)) return@runOnUiThread
            val ua = host.userAgent()
            Thread {
                try {
                    emitUpdate(JSONObject().put("state", "progress").put("pct", 0))
                    // 画面が覚えていた結果から入れるとき (アプリを開き直したあと) は、APK の場所を探し直す
                    if (!host.updater.hasApk) host.updater.check(ua, BuildConfig.VERSION_NAME)
                    val apk = host.updater.download(ua) { pct -> emitUpdate(JSONObject().put("state", "progress").put("pct", pct)) }
                    val problem = host.updater.verify(apk)
                    if (problem != null) {
                        emitUpdate(JSONObject().put("state", "error").put("message", problem))
                    } else {
                        ctx.runOnUiThread { host.launchInstaller(apk) }
                        emitUpdate(JSONObject().put("state", "installing"))
                    }
                } catch (e: Exception) {
                    emitUpdate(JSONObject().put("state", "error").put("message", e.message ?: "ダウンロードできませんでした"))
                } finally {
                    installing.set(false)
                }
            }.start()
        }
    }

    /** 更新の結果や進み具合を、開いている画面 (設定) の nhUpdate(JSON) へ渡す。どのスレッドからでも呼べる。 */
    private fun emitUpdate(json: JSONObject) = host.emitJs("nhUpdate", json)

    /** 今のランチャーアイコン (LauncherIcon のキー)。アプリ内ページだけ */
    @JavascriptInterface
    fun launcherIcon(): String = if (host.appPageNow) host.launcherIcon.current() else ""

    /** ランチャーアイコンを切り替える。now = true なら今すぐ (アプリを再起動)、false なら次に起動したとき。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun setLauncherIcon(key: String, now: Boolean) {
        ctx.runOnUiThread {
            if (!host.onAppPage()) return@runOnUiThread
            if (!now) host.launcherIcon.queue(key)
            // 今すぐ: 成功するとアプリが終わる。戻ってきたときは失敗なので、黙らずに知らせる
            else if (!host.launcherIcon.set(key, restartNow = true)) Toast.makeText(ctx, "アイコンを変えられませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    // 保存データ: 合言葉 (NHData.token) を持つページ本体からだけ。対応サイトかアプリ内ページのときだけ (trustedPageNow)
    private fun ok(t: String?) = host.trustedPageNow && t == NHData.token

    @JavascriptInterface
    fun get(t: String?): String = if (ok(t)) NHData.get(ctx) else ""

    @JavascriptInterface
    fun version(): Long = if (host.trustedPageNow) NHData.version else 0L

    @JavascriptInterface
    fun set(json: String, t: String?) {
        if (!ok(t)) return
        NHData.set(ctx, json)
        ctx.runOnUiThread { host.onDataChanged() }
    }

    /** 版が v のままなら書いて true (NH.update が使う)。ほかが先に書いていたら false (読み直してやり直す)。 */
    @JavascriptInterface
    fun setIf(json: String, v: Long, t: String?): Boolean {
        if (!ok(t)) return false
        val done = NHData.setIf(ctx, json, v)
        if (done) ctx.runOnUiThread { host.onDataChanged() }
        return done
    }
}

/**
 * オフライン保存の窓口 (NHOffline)。書き込み (put*) と「あるか」の確認は、保存を進めるサイトのページから呼ぶ。
 * 中身を読む / 一覧 / 削除は、アプリ内ページ (yomikura.local) からだけ受け付ける。
 */
class OfflineBridge(
    private val host: BridgeHost,
    private val offline: OfflineStore,
    private val saveQueue: SaveQueue,
    private val hostFilter: HostFilter,
) {
    private val ctx get() = host.activity

    private fun ok(t: String?) = host.trustedPageNow && t == NHData.token

    @JavascriptInterface
    fun putEpisode(site: String, id: String, ep: String, json: String, t: String?): Boolean = ok(t) && offline.putEpisode(site, id, ep, json)

    @JavascriptInterface
    fun putMeta(site: String, id: String, json: String, t: String?): Boolean = ok(t) && offline.putMeta(site, id, json)

    /** 挿絵を取って保存する (アプリの中から取る。結果の JSON は ImageSaver を参照)。保存中のサイトのページから */
    @JavascriptInterface
    fun saveImage(site: String, id: String, url: String, referer: String, t: String?): String =
        if (ok(t)) ImageSaver.save(offline, site, id, url, referer) { hostFilter.shouldBlock(it) } else "{}"

    @JavascriptInterface
    fun meta(site: String, id: String, t: String?): String = if (ok(t)) offline.meta(site, id) else ""

    @JavascriptInterface
    fun episode(site: String, id: String, ep: String): String = if (host.appPageNow) offline.episode(site, id, ep) else ""

    @JavascriptInterface
    fun list(): String = if (host.appPageNow) offline.list() else "[]"

    /** 読書画面で、上部バーを隠す / 出す (「スクロールで隠す」がオフなら隠さない) */
    @JavascriptInterface
    fun readerBar(hide: Boolean) {
        if (!host.appPageNow) return
        ctx.runOnUiThread { host.readerBar(hide) }
    }

    @JavascriptInterface
    fun delete(site: String, id: String): Boolean {
        if (!host.appPageNow) return false
        saveQueue.remove("$site:$id") // 保存待ちや保存中の作品なら、キューからも外す
        host.queueChanged()
        return offline.delete(site, id)
    }

    @JavascriptInterface
    fun deleteAll(): Boolean = host.appPageNow && host.dropOfflineAll()

    /** 保存キューに作品を入れる (作品のページから。json は SaveQueue.Job の形)。入った話の数を返す */
    @JavascriptInterface
    fun queueAdd(json: String, t: String?): Int {
        if (!ok(t)) return 0
        val job = try { SaveQueue.decode("[$json]").firstOrNull() } catch (e: org.json.JSONException) { null } ?: return 0
        // 保存の作業用 WebView はトークンと保存の窓口を持つので、対応サイト以外の URL (http / https 以外も) の作品は入れない
        val scheme = SiteUrls.schemeOf(job.url)
        if ((scheme != "https" && scheme != "http") || !SiteUrls.isSupportedHost(SiteUrls.hostOf(job.url))) return 0
        return saveQueue.add(job).also { host.queueChanged() }
    }

    /** 保存キューの中身 (順番どおり)。アプリ内ページだけ */
    @JavascriptInterface
    fun queueList(): String = if (host.appPageNow) SaveQueue.encode(saveQueue.all()) else "[]"

    /** 保存中の作品の進みと、サービスが動いているか ({running, key, title, ok, total, paused, waitingWifi})。アプリ内ページだけ */
    @JavascriptInterface
    fun queueLive(): String = if (host.appPageNow) OfflineStatus.toJson() else "{}"

    @JavascriptInterface
    fun queueMove(key: String, delta: Int): Boolean {
        if (!host.appPageNow) return false
        saveQueue.move(key, delta, OfflineStatus.live?.key)
        host.queueChanged()
        return true
    }

    /** キューから外す。保存中の作品なら、サービスが次の進みで中止する */
    @JavascriptInterface
    fun queueRemove(key: String): Boolean {
        if (!host.appPageNow) return false
        saveQueue.remove(key)
        host.queueChanged()
        return true
    }

    @JavascriptInterface
    fun queueClearFinished(): Boolean {
        if (!host.appPageNow) return false
        saveQueue.clearFinished()
        host.queueChanged()
        return true
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
        ctx.runOnUiThread { OfflineService.send(ctx, a) }
        return true
    }

    /** 止まった・確認が必要だった作品を、もう一度順番待ちに入れて始める (保存済みの話は飛ばす)。アプリ内ページだけ */
    @JavascriptInterface
    fun queueRetry(key: String): Boolean {
        if (!host.appPageNow) return false
        val retry = saveQueue.all().firstOrNull { it.key == key && !it.status.active } ?: return false
        saveQueue.remove(key)
        saveQueue.add(retry.copy(status = SaveQueue.Status.WAITING, note = ""))
        host.queueChanged()
        host.startSaving()
        return true
    }

    /**
     * 保存キューを前景サービスで進める。作品のページから、キューに入れたあとに呼ぶ。
     * 通知の許可が無いと、保存の進みが通知に出ない (保存自体は続く) ので、先に許可を求める。
     */
    @JavascriptInterface
    fun queueStart(t: String?): Boolean {
        if (!ok(t)) return false
        host.startSaving()
        return true
    }

    /** 従量課金でない回線 (Wi-Fi など) につながっているか。「Wi-Fi のときだけ保存する」設定の判定に使う。 */
    @JavascriptInterface
    fun onUnmetered(): Boolean = OfflineService.unmetered(ctx)
}
