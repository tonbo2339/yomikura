package com.tonbo2339.yomikura

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * バックアップの設定と、前回の結果の保存 (SharedPreferences)。送り先ごとに、自分のファイルを渡して使う (ファイル名は送り先ごとに決める)。
 * 時期の決まりは BackupSchedule。キー名は、これまでのバックアップと同じ (保存済みの設定がそのまま効く)。
 *
 * kinds = 種類 (BackupPayload.SNAPSHOTS のキー)。種類ごとに、送るか (on_<種類>)・残す数 (keep_<種類>) を持つ。
 * defaultAuto = 自動の既定 (送り先ごとに決める)
 */
class BackupPrefs(private val prefs: SharedPreferences, private val kinds: List<String>, private val defaultAuto: Boolean) {
    val auto: Boolean get() = prefs.getBoolean(KEY_AUTO, defaultAuto)
    val hours: Int get() = prefs.getInt(KEY_HOURS, BackupSchedule.DEFAULT_HOURS)
    val lastOkAt: Long get() = prefs.getLong(KEY_LAST_OK, 0)
    val lastTryAt: Long get() = prefs.getLong(KEY_LAST_TRY, 0)
    /** 前回の結果の説明 (成功でも失敗でも) */
    val lastMessage: String get() = prefs.getString(KEY_LAST_MSG, "") ?: ""

    fun isKind(kind: String) = kind in kinds
    fun keepOf(kind: String): Int = prefs.getInt("keep_$kind", BackupSchedule.DEFAULT_KEEP)
    fun onOf(kind: String): Boolean = prefs.getBoolean("on_$kind", true)

    /** 自動のオン・オフと間隔 (時間。選べる値でなければ既定) */
    fun setAuto(on: Boolean, everyHours: Int) {
        prefs.edit().putBoolean(KEY_AUTO, on).putInt(KEY_HOURS, BackupSchedule.hoursOrDefault(everyHours)).apply()
    }

    /** 残す数。種類が違う・範囲外は無視 (既定に戻す) */
    fun setKeep(kind: String, n: Int) {
        if (!isKind(kind)) return
        prefs.edit().putInt("keep_$kind", BackupSchedule.keepOrDefault(n)).apply()
    }

    fun setEnabled(kind: String, on: Boolean) {
        if (!isKind(kind)) return
        prefs.edit().putBoolean("on_$kind", on).apply()
    }

    /** 送った結果を残す (試みた時刻は常に、成功したときは成功の時刻も) */
    fun record(ok: Boolean, message: String, now: Long) {
        prefs.edit().putLong(KEY_LAST_TRY, now).putString(KEY_LAST_MSG, message).putBoolean(KEY_LAST_RESULT, ok)
            .apply { if (ok) putLong(KEY_LAST_OK, now) }.apply()
    }

    /** 「送れていない」通知を最後に出した時刻 */
    val lastAlertAt: Long get() = prefs.getLong(KEY_LAST_ALERT, 0)
    fun markAlerted(now: Long) = prefs.edit().putLong(KEY_LAST_ALERT, now).apply()

    /** 通知を出す時期か (BackupSchedule.shouldAlertStale) */
    fun shouldAlert(now: Long = System.currentTimeMillis()) = BackupSchedule.shouldAlertStale(auto, now, lastOkAt, lastAlertAt)

    /** 次に自動で送る時期まで待つ時間。自動がオフなら null */
    fun delayUntilDue(now: Long = System.currentTimeMillis()) = BackupSchedule.delayUntilDue(auto, now, lastOkAt, lastTryAt, hours)

    /** 送り先を変えた・解除したとき、前回の結果を忘れる */
    fun clearStatus() {
        prefs.edit().remove(KEY_LAST_OK).remove(KEY_LAST_TRY).remove(KEY_LAST_MSG).remove(KEY_LAST_RESULT).apply()
    }

    fun isDue(busy: Boolean, ready: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        BackupSchedule.isDue(auto, busy, ready, now, lastOkAt, lastTryAt, hours)

    /** 設定画面に出す、送り先に共通の状態。送り先ごとの項目 (接続先など) は、呼んだ側が足す */
    fun stateJson(busy: Boolean): JSONObject = JSONObject()
        .put("lastOkAt", lastOkAt)
        .put("lastAt", lastTryAt)
        .put("lastOk", prefs.getBoolean(KEY_LAST_RESULT, false))
        .put("lastMsg", lastMessage)
        .put("auto", auto)
        .put("hours", hours)
        .put("keep", JSONObject().also { o -> kinds.forEach { o.put(it, keepOf(it)) } })
        .put("enabled", JSONObject().also { o -> kinds.forEach { o.put(it, onOf(it)) } })
        .put("nextAt", BackupSchedule.nextAt(lastOkAt, hours))
        .put("busy", busy)

    private companion object {
        const val KEY_AUTO = "auto"
        const val KEY_HOURS = "hours"
        const val KEY_LAST_OK = "lastOkAt"
        const val KEY_LAST_TRY = "lastTryAt"
        const val KEY_LAST_MSG = "lastMsg"
        const val KEY_LAST_RESULT = "lastOk"
        const val KEY_LAST_ALERT = "lastAlertAt"
    }
}
