package com.tonbo2339.yomikura

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * R18 の作品がノクターン / ムーンライト / ミッドナイトのどれかを、作品ページ (novel18.syosetu.com/ID/) を読んで判別する。
 * サイトを記録する前 (v0.15.0 より前) に入れた作品や、更新の確認で入った作品のため。WebView では開かないので閲覧履歴には残らない。
 * 作品ページのヘッダーにあるサイトへのリンク (…?ref=ncodeheader) のホストで判断する (ui.js の NH.r18Sub と同じ)。
 * Cookie と UA はアプリの WebView と同じものを使う (年齢確認を通った Cookie が無ければ確認ページになり、判別できずに飛ばす)。
 * アクセスのマナー: 1 件ずつ 1.5〜3 秒あけ、403 / 429 / 503 が来たらやめる。同じ作品はアプリを開いている間 1 回だけ試す。
 */
object R18Subs {
    private const val TAG = "Yomikura.r18"
    private val tried = HashSet<String>()
    @Volatile private var running = false
    private val HEADER = Regex("""https://(noc|mnlt|mid)\.syosetu\.com/[^"'\s]*ref=ncodeheader""")
    private val ANY = Regex("""https://(noc|mnlt|mid)\.syosetu\.com/""")
    private val NCODE = Regex("n\\d{4}[a-z]+")

    /** 作品ページの HTML から noc / mnlt / mid を読む (ヘッダーのリンクを優先)。分からなければ null。Android に依存しない (単体テスト用) */
    fun detectSub(html: String): String? = (HEADER.find(html) ?: ANY.find(html))?.groupValues?.get(1)

    /** ids の作品を順に判別し、分かるたびに onFound(id, sub) (メインスレッド)、最後に onDone() (メインスレッド)。 */
    fun fill(ids: List<String>, ua: String, onFound: (String, String) -> Unit, onDone: () -> Unit, isResting: () -> Boolean = { false }, markRest: () -> Unit = {}) {
        // 止められたばかりのサイトには行かない (試したことにせず見送る。休止が明けて、あとで呼ばれたとき判別できるように)
        if (isResting()) return
        // 実行中に呼ばれたときは、試したことにせず見送る (あとで呼ばれたとき判別できるように)
        val todo = synchronized(tried) {
            if (running) return
            ids.filter { NCODE.matches(it) && tried.add(it) }.also { if (it.isNotEmpty()) running = true }
        }
        if (todo.isEmpty()) return
        val main = Handler(Looper.getMainLooper())
        thread(name = "Yomikura-r18subs") {
            try {
                for ((i, id) in todo.withIndex()) {
                    if (i > 0) Politeness.pause()
                    val (code, html) = get("https://novel18.syosetu.com/$id/", ua)
                    if (code == 403 || code == 429 || code == 503) { Diag.w(TAG, "stopped: $code"); markRest(); break }
                    val sub = detectSub(html)
                    Log.d(TAG, "$id -> $code $sub")
                    if (sub != null) main.post { onFound(id, sub) }
                }
            } catch (e: Exception) {
                Diag.w(TAG, "failed", e)
            } finally {
                synchronized(tried) { running = false }
                main.post(onDone)
            }
        }
    }

    private fun get(url: String, ua: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", ua)
            CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            val code = c.responseCode
            if (code != 200) return code to ""
            // ヘッダーは先頭にあるので、前のほうだけ読めば足りる
            val text = c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(200_000)
                var n = 0
                while (n < buf.size) { val k = r.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                String(buf, 0, n)
            }
            return code to text
        } finally {
            c.disconnect()
        }
    }
}
