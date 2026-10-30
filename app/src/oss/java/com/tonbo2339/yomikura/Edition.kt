package com.tonbo2339.yomikura

import android.content.Context
import android.webkit.WebView
import org.json.JSONObject

/** 公開版 (ヨミクラ)。通信は止めない。更新は公開のリポジトリから、ログインなしで取る */
object Edition {
    /** JS (設定画面) に伝える版の名前 */
    const val ID = "oss"

    /** アプリの更新を取る GitHub のリポジトリ */
    const val UPDATE_REPO = "tonbo2339/yomikura"

    /** デバッグ用の機能 (設定の「デバッグ」のページ) は無い (通信の記録は URL を省かないので、診断情報の方針に合わない) */
    const val DEBUG_TOOLS = false

    /** ソースコードと LICENSE を公開している GitHub のリポジトリ (設定の「オープンソースライセンス」に出す) */
    const val PUBLIC_REPO = "tonbo2339/yomikura"

    /** 保存データ (SharedPreferences) のファイル名 */
    const val PREFS_NAME = "yomikura"

    /** 設定の書き出し・読み込みの形式名 (JSON の app) */
    const val BACKUP_APP = "yomikura"

    /** 読み込みで受け付ける形式名。この版だけ */
    val BACKUP_ACCEPTS = listOf("yomikura")

    /** 旧版が控えたアプリ内ページの URL のホスト名。この版には旧版が無いので無し */
    const val LEGACY_APP_HOST = ""

    /** 公開のリポジトリなので、ログインは要らない (Cookie も送らない) */
    const val UPDATE_NEEDS_LOGIN = false

    /** true なら、成人向けのサイトは自分で使うと決めた人だけが使う = NSFW モードの初期値がオン (設定でオフにするとき、18 歳以上かを一度だけ確かめる) */
    const val R18_OPT_IN = true

    /** 入れ替える前の保存データはこの版には無いので、引き継ぎはしない (NHData.migrateNsfwDefault は呼ばない) */
    const val KEEP_R18_ON_UPGRADE = false

    /** R18 のサイトを開かない理由 (「…開きません」に続く言い方) */
    const val R18_OFF_WHY = "NSFW モードがオンなので"

    /** true なら、ページの DOM が読めた時点で画面の部品 (ui.js など) を注入する。読み込み完了が遅れることがあるので必要 */
    const val INJECT_AT_DOM_READY = true

    /** サイトの広告の枠の扱い。show = 何もしない */
    const val ADS_MODE = "show"

    fun hostFilter(@Suppress("UNUSED_PARAMETER") ctx: Context): HostFilter = HostFilter { false }

    @Suppress("UNUSED_PARAMETER")
    fun installExtras(ctx: Context, web: WebView, offline: OfflineStore, emit: (String, JSONObject) -> Unit, isAppPage: () -> Boolean): ExtraHooks =
        object : ExtraHooks {
            override fun autoIfDue() = Unit
        }

    @Suppress("UNUSED_PARAMETER")
    fun autoExtrasInBackground(ctx: Context, onDone: () -> Unit): Boolean = false
}
