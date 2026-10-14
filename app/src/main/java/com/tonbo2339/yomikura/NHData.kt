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
    /** 読めなかった保存データの写し (上書きで失わないため。keepUnreadable) */
    const val KEY_UNREADABLE = "data_unreadable"
    val token: String = UUID.randomUUID().toString()
    private val lock = Any()
    @Volatile var version = 0L
        private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(Edition.PREFS_NAME, Context.MODE_PRIVATE)

    fun get(ctx: Context): String = prefs(ctx).getString(KEY, "") ?: ""

    fun obj(ctx: Context): JSONObject = try { JSONObject(get(ctx)) } catch (e: Exception) { JSONObject() }

    /**
     * apply() で書いたまま、まだファイルに書けていない分を書き終えるまで待つ (このあとすぐプロセスを止めるときに呼ぶ)。
     * 空の commit() は、先に頼んだ書き込みが終わってから返る。
     */
    fun flush(ctx: Context) {
        prefs(ctx).edit().commit()
    }

    /**
     * いまの保存データが読めない形 (途中で切れているなど) なら、上書きする前に KEY_UNREADABLE へ写しておく。
     * 読めないデータは JS からは空に見えるので、そのまま書くと全部が消えてしまうため (あとで取り出せるように残す)。
     * 全体を読むと重いので、前後の形 ({ … }) だけ見る
     */
    private fun keepUnreadable(ctx: Context, next: String) {
        val p = prefs(ctx)
        if (next.isEmpty()) { p.edit().remove(KEY_UNREADABLE).apply(); return } // すべて消すときは、写しも消す
        val cur = p.getString(KEY, "").orEmpty().trim()
        if (cur.isEmpty() || (cur.startsWith("{") && cur.endsWith("}"))) return
        p.edit().putString(KEY_UNREADABLE, cur).apply() // いちばん新しい読めなかった中身を残す
        runCatching { Diag.w("Yomikura.data", "stored data unreadable; kept a copy") }
    }

    fun set(ctx: Context, json: String) = synchronized(lock) {
        keepUnreadable(ctx, json)
        prefs(ctx).edit().putString(KEY, json).apply()
        version++
    }

    /** 版が expected のままなら書いて true。ほかが先に書いていたら書かずに false。 */
    fun setIf(ctx: Context, json: String, expected: Long): Boolean = synchronized(lock) {
        if (version != expected) return false
        keepUnreadable(ctx, json)
        prefs(ctx).edit().putString(KEY, json).apply()
        version++
        true
    }

    /** Kotlin から書き換える: 読み込み → fn → 書き込みを、ほかの書き込みと重ならないように行う。 */
    fun update(ctx: Context, fn: (JSONObject) -> Unit): Unit = synchronized(lock) {
        val raw = get(ctx)
        val o = if (raw.isBlank()) JSONObject() else try { JSONObject(raw) } catch (e: Exception) {
            // 読めない保存データには書かない (空のデータに足して書くと、全部が消えてしまう)。中身は写しに残す
            prefs(ctx).edit().putString(KEY_UNREADABLE, raw).apply()
            runCatching { Diag.w("Yomikura.data", "stored data unreadable; update skipped") }
            return@synchronized
        }
        fn(o)
        val json = o.toString()
        keepUnreadable(ctx, json)
        prefs(ctx).edit().putString(KEY, json).apply()
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
