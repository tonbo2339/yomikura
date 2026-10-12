package com.tonbo2339.yomikura

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * ハーメルンの作品が R-18 かどうかを、小説情報のページ (syosetu.org/?mode=ss_detail&nid=ID) のタグ行で判別する。
 * R-18 の作品はこのページが h.syosetu.org へ転送される (R18 のドメイン) ので、転送先が h.syosetu.org なら R-18 と判断し、
 * R18 のドメインの中身は読まない (NSFW モードでも通信しない。NSFW モード中はこの判別自体を行わない)。
 * タグ行は「タグ R-18 ガールズラブ … 掲載開始」の形で、先頭の R-18 / R-15 の表記で判断する。
 * アクセスのマナー: 1 件ずつ 1.5〜3 秒あけ、403 / 429 / 503 が来たらやめる。同じ作品はアプリを開いている間 1 回だけ試す。
 */
object HamelnRating {
    private const val TAG = "Yomikura.hameln18"
    private val tried = HashSet<String>()
    @Volatile private var running = false
    private val ID = Regex("""^\d+$""")
    private val TAGS = Regex("""タグ\s*(.*?)\s*掲載開始""")
    private val R18_TAG = Regex("""(^|\s)R-?18(\s|$)""")
    private val TAG_MARKUP = Regex("<[^>]+>")

    /** 小説情報のページの HTML から、R-18 のタグがあるか。タグ行が無ければ null (判別できない)。Android に依存しない (単体テスト用) */
    fun isR18(html: String): Boolean? {
        val text = TAG_MARKUP.replace(html, " ").replace(Regex("""\s+"""), " ")
        val tags = TAGS.find(text)?.groupValues?.get(1) ?: return null
        return R18_TAG.containsMatchIn(tags)
    }

    /** ids (ハーメルンの作品番号) を順に判別し、分かるたびに onFound(id, r18) (メインスレッド)、最後に onDone() (メインスレッド)。 */
    fun fill(ids: List<String>, ua: String, onFound: (String, Boolean) -> Unit, onDone: () -> Unit, isResting: () -> Boolean = { false }, markRest: () -> Unit = {}) {
        if (isResting()) return // 止められたばかりのサイトには行かない (試したことにせず見送る)
        val todo = synchronized(tried) {
            if (running) return
            ids.filter { ID.matches(it) && tried.add(it) }.also { if (it.isNotEmpty()) running = true }
        }
        if (todo.isEmpty()) return
        val main = Handler(Looper.getMainLooper())
        thread(name = "Yomikura-hameln18") {
            try {
                for ((i, id) in todo.withIndex()) {
                    if (i > 0) Politeness.pause()
                    val r = get(id, ua)
                    if (r == null) { Diag.w(TAG, "stopped"); markRest(); break } // 403 / 429 / 503 などでやめる
                    Log.d(TAG, "$id -> r18=${r.first} ${r.second}")
                    r.first?.let { v -> main.post { onFound(id, v) } }
                }
            } catch (e: Exception) {
                Diag.w(TAG, "failed", e)
            } finally {
                synchronized(tried) { running = false }
                main.post(onDone)
            }
        }
    }

    /**
     * 1 件を取る。戻り値: (R-18 か (判別できなければ null), コード)。止めるべき応答 (403 / 429 / 503) のときは null。
     * 転送先が h.syosetu.org なら、中身は読まずに R-18 と判断する。
     */
    private fun get(id: String, ua: String): Pair<Boolean?, Int>? {
        val url = "https://syosetu.org/?mode=ss_detail&nid=$id"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", ua)
            CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
            val code = c.responseCode
            if (code == 403 || code == 429 || code == 503) return null
            if (code in 300..399) {
                val loc = c.getHeaderField("Location").orEmpty()
                return (if (URL(URL(url), loc).host == "h.syosetu.org") true else null) to code
            }
            if (code != 200) return null to code
            val html = c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(200_000)
                var n = 0
                while (n < buf.size) { val k = r.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                String(buf, 0, n)
            }
            return isR18(html) to code
        } finally {
            c.disconnect()
        }
    }
}
