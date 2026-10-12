package com.tonbo2339.yomikura

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * 保存データ (SharedPreferences の 1 つの JSON) の書き込み口。アプリの画面・画面外の巡回 (Collector)・Kotlin のどこから書くときも、ここを通す。
 *
 * 書き込むたびに版 (version) を 1 つ進める。JS の NH.update は「読んだときの版のままなら書く (setIf)、変わっていたら読み直してやり直す」
 * ので、画面の WebView と巡回の WebView が同時に書いても、片方の書き込みが消えない。
 *
 * token: JS の窓口 (NHStore / NHOffline) に添える合言葉。ページ本体 (メインフレーム) にだけ渡すので、
 * ページに埋め込まれた広告などの iframe は、窓口が見えても保存データを読み書きできない。プロセスごとに作り直す。
 */
object NHData {
    const val KEY = "data"
    val token: String = UUID.randomUUID().toString()
    private val lock = Any()
    @Volatile var version = 0L
        private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(Edition.PREFS_NAME, Context.MODE_PRIVATE)

    fun get(ctx: Context): String = prefs(ctx).getString(KEY, "") ?: ""

    fun obj(ctx: Context): JSONObject = try { JSONObject(get(ctx)) } catch (e: Exception) { JSONObject() }

    fun set(ctx: Context, json: String) = synchronized(lock) {
        prefs(ctx).edit().putString(KEY, json).apply()
        version++
    }

    /** 版が expected のままなら書いて true。ほかが先に書いていたら書かずに false。 */
    fun setIf(ctx: Context, json: String, expected: Long): Boolean = synchronized(lock) {
        if (version != expected) return false
        prefs(ctx).edit().putString(KEY, json).apply()
        version++
        true
    }

    /** Kotlin から書き換える: 読み込み → fn → 書き込みを、ほかの書き込みと重ならないように行う。 */
    fun update(ctx: Context, fn: (JSONObject) -> Unit) = synchronized(lock) {
        val o = obj(ctx)
        fn(o)
        prefs(ctx).edit().putString(KEY, o.toString()).apply()
        version++
    }

    /**
     * 初期値が違う旧版 (NSFW モードの初期値がオフだった版) から入れ替えた保存データを、成人向けが使えるまま引き継ぐ。
     * すでに設定 (settings) がある保存データで nsfw が未設定なら、nsfw = false (オフ) を書く。新規のデータ (settings が無い) は触らず、初期値のまま。
     * 一度だけ行う (nsfwMigrated)。変えたときは true。
     */
    fun migrateNsfwDefault(o: JSONObject): Boolean {
        if (o.optInt("nsfwMigrated") == 1) return false
        o.optJSONObject("settings")?.let { if (!it.has("nsfw")) it.put("nsfw", false) }
        o.put("nsfwMigrated", 1)
        return true
    }

    /** NSFW モード (R18 のサイトへ一切アクセスしない) か。設定に無いときの既定は版で決まる (Edition.R18_OPT_IN)。JS の NH.nsfwOn と同じ */
    fun nsfwOn(settings: JSONObject?): Boolean = settings?.optBoolean("nsfw", Edition.R18_OPT_IN) ?: Edition.R18_OPT_IN

    /** ページに入れる JS の先頭に付ける、合言葉の受け渡し (core.js / early.js が読んで消す)。 */
    val tokenJs: String get() = "window.__nhT=${JSONObject.quote(token)};\n"
}
