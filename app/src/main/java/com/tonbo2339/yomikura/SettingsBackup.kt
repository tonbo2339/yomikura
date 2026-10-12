package com.tonbo2339.yomikura

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 標準のバックアップ (Google への自動バックアップ・機種変更のときの移行) で残すのは、設定だけにする。
 *
 * 保存データ (NHData) は 1 つの JSON にリスト・履歴・読書位置もまとめて入っているので、設定 (settings) だけを
 * 別の SharedPreferences `settings_backup` に写し、バックアップの対象はそのファイルだけにする
 * (res/xml/data_extraction_rules.xml・backup_rules.xml)。ログイン情報 (Cookie)・連携先の接続情報・オフライン保存の本文は対象にしない。
 *
 * - 写す: アプリが裏に回るとき (MainActivity.onPause)。保存データは読書中に何度も書かれるので、書くたびには写さない
 * - 戻す: 入れ直した直後など、保存データが空のときに、写しがあれば設定だけを戻す (MainActivity.onCreate)
 */
object SettingsBackup {
    private const val PREFS = "settings_backup" // バックアップの対象のファイル名 (settings_backup.xml)。xml の規則と同じ名前
    private const val KEY = "settings"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 今の設定を写す (前と同じなら書かない) */
    fun save(ctx: Context) {
        val settings = NHData.obj(ctx).optJSONObject("settings")?.toString() ?: return
        val p = prefs(ctx)
        if (p.getString(KEY, null) != settings) p.edit().putString(KEY, settings).apply()
    }

    /** 写しを消す (すべてのデータを消したあと。残すと、入れ直しや再起動のときに消したはずの設定が戻ってしまう) */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }

    /** 保存データが空なら、写しから設定を戻す。戻したら true */
    fun restoreIfEmpty(ctx: Context): Boolean {
        val data = restored(NHData.get(ctx), prefs(ctx).getString(KEY, null)) ?: return false
        NHData.set(ctx, data)
        return true
    }

    /** 戻す保存データ。保存データが空 (入れ直した直後) で、写しが読めるときだけ。Android に依存しない (単体テスト用) */
    fun restored(current: String, copy: String?): String? {
        if (current.isNotEmpty() || copy.isNullOrEmpty()) return null
        val settings = try { JSONObject(copy) } catch (e: Exception) { return null }
        return JSONObject().put("readLater", JSONArray()).put("settings", settings).toString()
    }
}
