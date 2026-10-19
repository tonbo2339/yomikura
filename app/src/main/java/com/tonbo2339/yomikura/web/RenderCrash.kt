package com.tonbo2339.yomikura

import android.content.SharedPreferences

/**
 * WebView の表示用プロセスが落ちた (メモリ不足など) あとの、開き直しの控え。
 * アプリごと落ちないよう画面を作り直して、落ちる前に開いていたページを開き直す。
 * 続けて落ちるとき (15 秒以内) は、同じページを開き直さず、起動ページから始める。
 */
class RenderCrash(private val prefs: SharedPreferences) {

    /** 落ちた。lastUrl = そのとき開いていたページ。 */
    fun record(lastUrl: String?) {
        val now = System.currentTimeMillis()
        val repeated = now - prefs.getLong(KEY_CRASH_AT, 0L) < REPEAT_MS
        val ed = prefs.edit().putLong(KEY_CRASH_AT, now)
        if (repeated) ed.remove(KEY_CRASH_URL) else ed.putString(KEY_CRASH_URL, lastUrl)
        ed.apply()
    }

    /** 作り直したとき、開き直すページ (1 回だけ。無ければ null)。 */
    fun take(): String? {
        val url = prefs.getString(KEY_CRASH_URL, null)?.let(StartPage::fixLegacyHost) ?: return null
        prefs.edit().remove(KEY_CRASH_URL).apply()
        return url
    }

    private companion object {
        const val KEY_CRASH_URL = "crashUrl"
        const val KEY_CRASH_AT = "crashAt"
        const val REPEAT_MS = 15_000L
    }
}
