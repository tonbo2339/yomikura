package com.tonbo2339.yomikura

import android.os.Build
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/**
 * JS から使う窓口 (NHStore)。保存データはサイト (origin) をまたいで共有するので、JSON 文字列をそのまま SharedPreferences に置く。
 * 端末の操作や更新など、サイトのページから使わせたくないものはアプリ内ページからだけ受け付ける。
 * JS からは名前と引数の数で呼ばれるので、メソッドの名前と形は変えない。
 *
 * ここは「誰から受け付けるか」の確認と JS との受け渡しだけ。中身の処理は役割ごとのクラスにある:
 * 更新 = UpdateFlow / ログアウト = LogoutFlow / R18 の判別 = ListFill / 確認の進行 = host (MainActivity → CollectController)。
 */
class StoreBridge(host: BridgeHost) : GuardedBridge(host) {
    private val update = UpdateFlow(host)
    private val logout = LogoutFlow(host)
    private val listFill = ListFill(host)

    // ---- 保存データ: 合言葉 (NHData.token) を持つページ本体からだけ。対応サイトかアプリ内ページのときだけ (trustedPageNow) ----

    @JavascriptInterface
    fun get(t: String?): String = if (trusted(t)) NHData.get(ctx) else ""

    @JavascriptInterface
    fun version(): Long = if (host.trustedPageNow) NHData.version else 0L

    @JavascriptInterface
    fun set(json: String, t: String?) {
        if (!trusted(t)) return
        NHData.set(ctx, json)
        onUi { host.onDataChanged() }
    }

    /** 版が v のままなら書いて true (NH.update が使う)。ほかが先に書いていたら false (読み直してやり直す)。 */
    @JavascriptInterface
    fun setIf(json: String, v: Long, t: String?): Boolean {
        if (!trusted(t)) return false
        val done = NHData.setIf(ctx, json, v)
        if (done) onUi { host.onDataChanged() }
        return done
    }

    // ---- 更新の確認 ----

    /** 更新の確認を始める (リスト画面の更新ボタン)。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun collectNow() = onUiForApp { host.collectNow() }

    /** 手動の「更新を確認」を押せるまで、あと何ミリ秒か (0 = 押せる。CollectCooldown)。リストのボタンの表示に使う。アプリ内ページだけ */
    @JavascriptInterface
    fun collectWaitMs(): Long =
        forApp(0L) { CollectCooldown.remainingMs(CollectCooldown.lastOf(NHData.obj(ctx)), System.currentTimeMillis()) }

    /** 更新を確認中か (リスト画面を開いたときに、更新ボタンを回すか決める)。アプリ内ページだけ */
    @JavascriptInterface
    fun collecting(): Boolean = host.appPageNow && host.collectingNow

    /** 確認の進み具合 (「2 / 4」)。確認していなければ空。アプリ内ページだけ */
    @JavascriptInterface
    fun progressNow(): String = forApp("") { host.collectProgressText }

    /** 起動時の自動チェックが終わったあとに開くページ。アプリ内ページだけ */
    @JavascriptInterface
    fun startUrl(): String = forApp("") { host.startUrl() }

    /** 裏での確認の予定 (BgCheck.plan)。設定画面に出す。アプリ内ページだけ */
    @JavascriptInterface
    fun bgPlan(): String = forApp("") { BgCheck.plan(ctx) }

    /** 通知の許可を求める (Android 13 以降)。結果は画面の nhPlanChanged() で知らせる。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun requestNotify() = onUiForApp {
        if (Build.VERSION.SDK_INT >= 33 && !Notices.allowed(ctx)) host.requestNotifyPermission()
    }

    // ---- リストの作品の情報の補い (R18) ----

    /**
     * ハーメルンの作品が R-18 かを、小説情報のページを読んで判別する (HamelnRating)。分かったら作品の r18 を書き、リスト画面を描き直す。
     * NSFW モード中は R18 のドメインへ行くおそれがあるので、通信しない。アプリ内ページからだけ。
     */
    @JavascriptInterface
    fun fillHamelnR18(idsJson: String) = onUiForApp {
        if (host.nsfwOn()) return@onUiForApp
        ListFill.parseIds(idsJson)?.let { listFill.hamelnR18(it) }
    }

    /**
     * サイトが分からない R18 の作品 (ids: JSON の配列) を、作品ページを読んで判別する (R18Subs)。
     * 分かったらリストと閲覧履歴の同じ作品に sub を書き、終わったらリスト画面に知らせる (nhSubsFilled)。アプリ内ページからだけ。
     */
    @JavascriptInterface
    fun fillR18Subs(idsJson: String) = onUiForApp {
        if (host.nsfwOn()) return@onUiForApp // NSFW モード中は R18 の作品ページを読みに行かない
        ListFill.parseIds(idsJson)?.let { listFill.r18Subs(it) }
    }

    /** 外部のリンクの作品を「後で読む」に追加したあと、読んでいた作品に戻る (openLink で選んだときだけ動く)。対応サイトのページから。 */
    @JavascriptInterface
    fun laterBack(result: String) {
        if (!host.trustedPageNow) return
        onUi { host.finishLaterBack(result) }
    }

    // ---- ログイン・ログアウト (アプリ内ページだけ) ----

    /**
     * アプリ内の WebView で GitHub にログインしているか。ログイン済みならユーザー名 (分からなければ "yes")、未ログインなら ""。
     * サイトのページから読まれないよう、アプリ内ページからだけ答える。
     */
    @JavascriptInterface
    fun githubUser(): String = forApp("") { LoginCookies.githubUser() }

    /**
     * 各サイトにログインしているか (Cookie の有無で見る。ログイン切れまでは分からない)。{"narou":true,"hameln":false,"kakuyomu":false}。
     * アプリ内ページからだけ答える。
     */
    @JavascriptInterface
    fun siteLogins(): String = forApp(JSONObject().toString()) {
        val r = LoginCookies.state()
        LoginCookies.sync(ctx, r) // 設定が表示するのと同じ状態を、巡回も使う
        r.toString()
    }

    /** そのサイト (narou は R18 も共通) のログイン用 Cookie を消す。アプリ内ページからだけ。 */
    @JavascriptInterface
    fun logoutSite(site: String): Boolean = forApp(false) { LoginCookies.expire(site) }

    /** サイトの公式のログアウトを裏で行い、そのあとアプリの Cookie も消す (LogoutFlow)。結果は画面の nhLogoutResult へ。 */
    @JavascriptInterface
    fun logoutOfficial(site: String) {
        if (!host.appPageNow || site !in LoginCookies.SITES) return
        logout.logout(site)
    }

    /** 次のログアウトを受け付けるまでの秒数 (0 = すぐできる、-1 = いま動いている)。設定のログインの画面が、ボタンを押せるかを決める */
    @JavascriptInterface
    fun logoutWait(site: String): Int = forApp(0) { OfficialLogout.waitSeconds(site) }

    /** ログイン情報 (Cookie) をすべて消す。Cloudflare の確認を通した印 (cf_clearance) は残す。GitHub のログインも消える。 */
    @JavascriptInterface
    fun clearLogins(): Boolean = forApp(false) { LoginCookies.clearAll(); true }

    // ---- 端末・データの操作 (アプリ内ページだけ) ----

    /** Android の設定画面を開く (links = 対応リンク)。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun openSystem(kind: String) = onUiForApp { host.openSystemSettings(kind) }

    /** ページの一時ファイル (キャッシュ) を消す。ログインや保存データは消えない。 */
    @JavascriptInterface
    fun clearWebCache(): Boolean = forApp(false) { onUi { host.clearWebCache() }; true }

    /**
     * 保存データ (リスト・設定・履歴・読書位置など) とオフライン保存をすべて消し、初回の画面に戻る。ログイン情報は消さない。
     * アプリ内ページからだけ。
     */
    @JavascriptInterface
    fun wipeAll(): Boolean = forApp(false) { onUi { host.wipeAll() }; true }

    /**
     * バックアップを書き出す。mode = "one" (1 ファイル。files の先頭) / "many" (フォルダを選んで全部)。
     * files = [{name, text}]。保存先の画面を出し、結果は nhBackupResult(JSON) で返す。アプリ内ページからだけ。
     */
    @JavascriptInterface
    fun exportFiles(mode: String, filesJson: String) = onUiForApp {
        val files = try {
            val arr = JSONArray(filesJson)
            List(arr.length()) { arr.getJSONObject(it).let { o -> o.getString("name") to o.getString("text") } }
        } catch (e: org.json.JSONException) {
            return@onUiForApp
        }
        if (files.isNotEmpty()) host.startExport(mode, files)
    }

    /** バックアップのファイルを選んで読み込む (複数可)。中身は画面の nhImport(JSON の配列) に渡す。アプリ内ページからだけ。 */
    @JavascriptInterface
    fun importFiles() = onUiForApp { host.startImport() }

    // ---- アプリの情報・更新・アイコン (アプリ内ページだけ) ----

    /** アプリのバージョン (設定画面の一番下に出す)。 */
    @JavascriptInterface
    fun appVersion(): String = forApp("") { BuildConfig.VERSION_NAME }

    /** この版の変更点 (ReleaseNotes.toJson の形)。設定の「アプリの更新」に出す。アプリ内ページだけ */
    @JavascriptInterface
    fun releaseNotes(): String = forApp("[]") { ReleaseNotes.toJson(ReleaseNotes.read(ctx)) }


    /** アプリの更新に GitHub のログインが要るか (非公開のリポジトリから更新する版では要る。Edition が決める)。 */
    @JavascriptInterface
    fun updateNeedsLogin(): Boolean = host.appPageNow && Edition.UPDATE_NEEDS_LOGIN

    /** ⋮ メニューの項目と、出すかどうか (MenuItems.toJson)。設定画面がスイッチを並べるのに使う。 */
    @JavascriptInterface
    fun menuItems(): String = forApp("[]") { MenuItems.toJson(NHData.settingsOf(NHData.obj(ctx))).toString() }

    /** アプリの更新を確認する (UpdateFlow)。結果は画面の nhUpdate(JSON) へ返す。 */
    @JavascriptInterface
    fun checkUpdate() = onUiForApp { update.check() }

    /** 最後に確認で見つけた新しい版をダウンロードして、インストールを始める (UpdateFlow)。 */
    @JavascriptInterface
    fun installUpdate() = onUiForApp { update.install() }

    /** 今のランチャーアイコン (LauncherIcon のキー)。 */
    @JavascriptInterface
    fun launcherIcon(): String = forApp("") { host.launcherIcon.current() }

    /** ランチャーアイコンを切り替える。now = true なら今すぐ (アプリを再起動)、false なら次に起動したとき。アプリ内ページからだけ受け付ける。 */
    @JavascriptInterface
    fun setLauncherIcon(key: String, now: Boolean) = onUiForApp {
        if (!now) host.launcherIcon.queue(key)
        // 今すぐ: 成功するとアプリが終わる。戻ってきたときは失敗なので、黙らずに知らせる
        else if (!host.launcherIcon.set(key, restartNow = true)) Messages.show(ctx, "アイコンを変えられませんでした")
    }
}
