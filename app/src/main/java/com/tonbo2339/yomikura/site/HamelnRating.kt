package com.tonbo2339.yomikura

import android.content.Context
import android.util.Log

/**
 * ハーメルンの作品が R-18 かどうかを、小説情報のページ (syosetu.org/?mode=ss_detail&nid=ID) のタグ行で判別する。
 * R-18 の作品はこのページが h.syosetu.org へ転送される (R18 のドメイン) ので、転送先が h.syosetu.org なら R-18 と判断し、
 * R18 のドメインの中身は読まない (開く前に止める。NSFW モード中はこの判別自体を行わない)。
 * タグ行は「タグ R-18 ガールズラブ … 掲載開始」の形で、先頭の R-18 / R-15 の表記で判断する。
 * ページは画面に出さない WebView で開く ([PageProbe]。アプリ自身の通信は、サイトの Cloudflare にボットとして 403 で止められるため)。
 * アクセスのマナー: 1 件ずつ 1.5〜3 秒あけ、403 / 429 / 503 が来たらやめる。同じ作品はアプリを開いている間 1 回だけ試す。
 */
object HamelnRating {
    private const val TAG = "Yomikura.hameln18"
    private const val R18_HOST = "h.syosetu.org"
    private val STOP_CODES = setOf(403, 429, 503)
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

    /**
     * 開いた結果から、(R-18 か (判別できなければ null), 止めるべきか) を決める。Android に依存しない (単体テスト用)。
     * 止めるべき = 403 / 429 / 503。R18 のドメインへ転送されたら、中身は読まずに R-18 と判断する。
     */
    fun judge(status: Int, redirectedTo: String?, html: String): Pair<Boolean?, Boolean> {
        if (status in STOP_CODES) return null to true
        if (redirectedTo == R18_HOST) return true to false
        if (status != 200) return null to false
        return isR18(html) to false
    }

    /** ids (ハーメルンの作品番号) を順に判別し、分かるたびに onFound(id, r18)、最後に onDone()。メインスレッドで呼ぶ (結果もメインスレッド)。 */
    fun fill(ctx: Context, ids: List<String>, onFound: (String, Boolean) -> Unit, onDone: () -> Unit, isResting: () -> Boolean = { false }, markRest: () -> Unit = {}, onStopped: () -> Unit = {}, stop: () -> Boolean = { false }) {
        if (isResting()) return // 止められたばかりのサイトには行かない (試したことにせず見送る)
        val todo = synchronized(tried) {
            if (running) return
            ids.filter { ID.matches(it) && tried.add(it) }.also { if (it.isNotEmpty()) running = true }
        }
        if (todo.isEmpty()) return
        ProbeRun.run(ctx, todo, { "https://syosetu.org/?mode=ss_detail&nid=$it" }, setOf(R18_HOST), stop = stop, onResult = { id, r ->
            if (r.status == 0) {
                // 開けなかった・時間切れ (オフラインなど)。試したことにせず、ここでやめる (あとで、もう一度判別できるように)
                Log.d(TAG, "$id -> not loaded")
                return@run false
            }
            val (r18, stopSite) = judge(r.status, r.redirectedTo, r.html)
            if (stopSite) {
                // 403 / 429 / 503 でやめる。残りは試したことにしない (休みが明けたあと、もう一度判別できるように)
                Diag.w(TAG, "stopped: ${r.status}"); markRest(); onStopped()
                false
            } else {
                Log.d(TAG, "$id -> r18=$r18 ${r.status}")
                r18?.let { onFound(id, it) }
                true
            }
        }, finished = { notDone ->
            synchronized(tried) { tried.removeAll(notDone.toSet()); running = false }
            onDone()
        })
    }
}
