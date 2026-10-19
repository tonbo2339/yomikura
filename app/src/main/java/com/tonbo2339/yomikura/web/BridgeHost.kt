package com.tonbo2339.yomikura

import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

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
 * JS の窓口に共通の「誰からの呼び出しを受け付けるか」の判定と、画面のスレッドへの切り替え。
 * 窓口ごとに同じ `if (host.appPageNow) … else …` / `runOnUiThread { if (!host.onAppPage()) return … }` を書かず、ここを通す。
 *  - アプリ内ページだけ (端末の操作・設定・一覧): [forApp] / [onUiForApp]
 *  - 合言葉を持つ、対応サイトかアプリ内ページ (保存データ・オフライン保存の書き込み): [trusted]
 */
abstract class GuardedBridge(protected val host: BridgeHost) {
    protected val ctx get() = host.activity

    /** 合言葉 (NHData.token) を持つページ本体からで、対応サイトかアプリ内ページのとき */
    protected fun trusted(token: String?) = host.trustedPageNow && token == NHData.token

    /** アプリ内ページからのときだけ block の結果を返し、そうでなければ denied を返す (どのスレッドからでも) */
    protected inline fun <T> forApp(denied: T, block: () -> T): T = if (host.appPageNow) block() else denied

    /** 画面のスレッドで block を行う。 */
    protected fun onUi(block: () -> Unit) = ctx.runOnUiThread(block)

    /** 画面のスレッドで、そのとき WebView に出ているのがアプリ内ページなら block を行う (開いたあとにサイトのページへ移っていたら何もしない)。 */
    protected fun onUiForApp(block: () -> Unit) = ctx.runOnUiThread { if (host.onAppPage()) block() }
}
