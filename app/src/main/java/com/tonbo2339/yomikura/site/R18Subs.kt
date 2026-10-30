package com.tonbo2339.yomikura

import android.content.Context
import android.util.Log

/**
 * R18 の作品がノクターン / ムーンライト / ミッドナイトのどれかを、作品ページ (novel18.syosetu.com/ID/) を読んで判別する。
 * サイトを記録する前 (v0.15.0 より前) に入れた作品や、更新の確認で入った作品のため。アプリの画面の WebView では開かないので、アプリの閲覧履歴には残らない。
 * 作品ページのヘッダーにあるサイトへのリンク (…?ref=ncodeheader) のホストで判断する (ui.js の NH.r18Sub と同じ)。
 * ページは画面に出さない WebView で開く ([PageProbe]。アプリ自身の通信は、サイトの Cloudflare にボットとして 403 で止められるため)。
 * Cookie は WebView と共通 (年齢確認を通った Cookie が無ければ確認ページになり、判別できずに飛ばす)。
 * アクセスのマナー: 1 件ずつ 1.5〜3 秒あけ、403 / 429 / 503 が来たらやめる。同じ作品はアプリを開いている間 1 回だけ試す。
 */
object R18Subs {
    private const val TAG = "Yomikura.r18"
    private val tried = HashSet<String>()
    @Volatile private var running = false
    private val STOP_CODES = setOf(403, 429, 503)
    private val HEADER = Regex("""https://(noc|mnlt|mid)\.syosetu\.com/[^"'\s]*ref=ncodeheader""")
    private val ANY = Regex("""https://(noc|mnlt|mid)\.syosetu\.com/""")
    private val NCODE = Regex("n\\d{4}[a-z]+")

    /** 作品ページの HTML から noc / mnlt / mid を読む (ヘッダーのリンクを優先)。分からなければ null。Android に依存しない (単体テスト用) */
    fun detectSub(html: String): String? = (HEADER.find(html) ?: ANY.find(html))?.groupValues?.get(1)

    /** 開いた結果から、(noc / mnlt / mid (判別できなければ null), 止めるべきか) を決める。止めるべき = 403 / 429 / 503。Android に依存しない (単体テスト用) */
    fun judge(status: Int, html: String): Pair<String?, Boolean> {
        if (status in STOP_CODES) return null to true
        if (status != 200) return null to false
        return detectSub(html) to false
    }

    /**
     * ids の作品を順に判別し、分かるたびに onFound(id, sub)、最後に onDone()。メインスレッドで呼ぶ (結果もメインスレッド)。
     * stop = 途中でやめるか (1 件ごとに、取りに行く前に見る。判別の途中で NSFW モードがオンになったら、R18 のサイトへ行かない)
     */
    fun fill(ctx: Context, ids: List<String>, onFound: (String, String) -> Unit, onDone: () -> Unit, isResting: () -> Boolean = { false }, markRest: () -> Unit = {}, stop: () -> Boolean = { false }) {
        // 止められたばかりのサイトには行かない (試したことにせず見送る。休止が明けて、あとで呼ばれたとき判別できるように)
        if (isResting()) return
        // 実行中に呼ばれたときは、試したことにせず見送る (あとで呼ばれたとき判別できるように)
        val todo = synchronized(tried) {
            if (running) return
            ids.filter { NCODE.matches(it) && tried.add(it) }.also { if (it.isNotEmpty()) running = true }
        }
        if (todo.isEmpty()) return
        ProbeRun.run(ctx, todo, { "https://novel18.syosetu.com/$it/" }, emptySet(), stop = stop, onResult = { id, r ->
            if (r.status == 0) {
                // 開けなかった・時間切れ (オフラインなど)。試したことにせず、ここでやめる (あとで、もう一度判別できるように)
                Log.d(TAG, "$id -> not loaded")
                return@run false
            }
            val (sub, stopSite) = judge(r.status, r.html)
            if (stopSite) {
                // 403 / 429 / 503 でやめる。残りは試したことにしない (休みが明けたあと、もう一度判別できるように)
                Diag.w(TAG, "stopped: ${r.status}"); markRest()
                false
            } else {
                Log.d(TAG, "$id -> ${r.status} $sub")
                sub?.let { onFound(id, it) }
                true
            }
        }, finished = { notDone ->
            // やめた (403 など・NSFW モードがオン) ときの残りは、試したことにしない (あとで、もう一度判別できるように)
            synchronized(tried) { tried.removeAll(notDone.toSet()); running = false }
            onDone()
        })
    }
}
