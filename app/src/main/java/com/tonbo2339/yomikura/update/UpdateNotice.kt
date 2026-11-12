package com.tonbo2339.yomikura

import android.content.SharedPreferences

/**
 * 新しい版が見つかったことの印 (⋮ / 歯車の小さな点) の状態。
 * アプリを開いたとき、前回の確認から [Logic.CHECK_EVERY_MS] たっていたら、裏で確認する。新しい版があれば、その番号を覚えて、点を出す。
 * 設定の「アプリの更新」で、その版のカードを見せたら「見た」ことにして、点を消す (新しい版が出たら、また 1 回出る)。
 * 印を出すか自体は、設定 settings.updateNotice (既定オン。AppSettings.updateNotice)。
 * 判定は、Android に依存しない [Logic] に分けてある (単体テストする)。
 */
class UpdateNotice(private val prefs: SharedPreferences) {

    /** 見つけた新しい版 (空 = 無い) */
    private var found: String
        get() = prefs.getString(KEY_FOUND, "").orEmpty()
        set(v) { prefs.edit().putString(KEY_FOUND, v).apply() }

    /** カードを見せた版 */
    private var seen: String
        get() = prefs.getString(KEY_SEEN, "").orEmpty()
        set(v) { prefs.edit().putString(KEY_SEEN, v).apply() }

    private var checkedAt: Long
        get() = prefs.getLong(KEY_AT, 0L)
        set(v) { prefs.edit().putLong(KEY_AT, v).apply() }

    /** いま入れられる新しい版 (覚えていて、今の版より新しいもの)。無ければ空 */
    fun pending(current: String): String = Logic.pending(found, current)

    /** 点を出すか */
    fun dotVisible(on: Boolean, current: String): Boolean = Logic.dotVisible(on, found, seen, current)

    /** 裏で確認するか (印がオンで、前回の確認から間が空いたとき) */
    fun dueToCheck(on: Boolean, now: Long): Boolean = Logic.due(on, checkedAt, now)

    /** 確認の結果を覚える。available = 新しい版あり (latest がその番号)、latest = 最新。それ以外 (ログイン切れ・通信の失敗) は覚えず、次の起動でやり直す */
    fun record(state: String, latest: String, now: Long) {
        when (state) {
            "available" -> { found = latest; checkedAt = now }
            "latest" -> { found = ""; checkedAt = now }
        }
    }

    /** 設定の画面で、この版のカードを見せた */
    fun markSeen(version: String) { if (version.isNotEmpty()) seen = version }

    object Logic {
        /** 通信は、半日に 1 回まで */
        const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

        fun pending(found: String, current: String) = if (found.isNotEmpty() && Updater.isNewer(found, current)) found else ""

        fun dotVisible(on: Boolean, found: String, seen: String, current: String): Boolean {
            val p = pending(found, current)
            return on && p.isNotEmpty() && p != seen
        }

        fun due(on: Boolean, checkedAt: Long, now: Long) = on && (now - checkedAt >= CHECK_EVERY_MS || now < checkedAt)
    }

    private companion object {
        const val KEY_FOUND = "updateFound"
        const val KEY_SEEN = "updateSeen"
        const val KEY_AT = "updateCheckedAt"
    }
}
