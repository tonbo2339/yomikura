package com.tonbo2339.yomikura

import android.content.Context

/**
 * 画面での更新の確認 1 回分。巡回用の WebView (Collector) をまとめる。
 *
 * ふだんは Collector 1 つで、サイトを順に回る。確認画面 (Cloudflare) で止まったサイトが出たら、その Collector は止めたまま待たせ
 * (画面に出して通してもらう。通れば同じ WebView がそのサイトの取得を続ける)、次のサイトからは新しい Collector で続ける。
 * 通してもらっている間も、ほかのサイトの確認は止まらない。
 *
 * onDone は、いま動いている (待っていない) Collector がなくなったときに、最後に届いた結果 (collectLog) で呼ぶ
 * (最後のサイトが止まって、動いている Collector が無くなったときは、保存データの collectLog で呼ぶ)。
 * 待っていた Collector が通って終わったときにも、もう一度呼ぶ (そのサイトの結果が加わる)。メインスレッドで使う。
 */
class CollectSession(
    private val ctx: Context,
    private val blocker: HostFilter,
    startUrl: String,
    private val onProgress: (Int, Int) -> Unit,
    /** サイトが確認画面で止まった。held の WebView を画面に出せば通してもらえる */
    private val onChallenge: (site: String, held: Collector) -> Unit,
    /** 待っていたサイトの Collector が終わった (ok = 通って取得まで終わった / そうでなければ時間切れ) */
    private val onHeldEnd: (site: String, ok: Boolean) -> Unit,
    private val onDone: (String?) -> Unit,
) {
    private val parts = mutableListOf<Collector>()
    private val held = LinkedHashMap<Collector, String>()
    private var lastLog: String? = null

    /** 待っていない Collector が動いているか (新しい確認を始められない) */
    val busy: Boolean get() = parts.any { it !in held }

    init { start(startUrl) }

    private fun start(url: String) {
        var col: Collector? = null
        col = Collector(
            ctx, blocker, url, onProgress, holdable = true,
            onChallenge = { site, next -> challenged(col!!, site, next) },
            onDone = { log -> ended(col!!, log) },
        )
        parts += col
    }

    private fun challenged(col: Collector, site: String, nextUrl: String) {
        if (col !in parts) return
        held[col] = site
        if (nextUrl.isNotEmpty()) start(nextUrl)
        onChallenge(site, col)
        // 止まったのが最後のサイトで、ほかに動いている Collector が無い: 結果を返す WebView が無くなるので、ここまでの結果で一度終える
        // (返さないと、待っていた Collector が時間切れで終わったとき onDone が一度も呼ばれず、「確認中」の表示が残る)
        if (!busy) onDone(savedLog())
    }

    /** 保存データにある、今回の確認の結果 (ui.js が collectLog に書いている) */
    private fun savedLog(): String = NHData.obj(ctx).optJSONObject("collectLog")?.toString() ?: "{}"

    private fun ended(col: Collector, log: String?) {
        parts.remove(col)
        val site = held.remove(col)
        if (log != null) lastLog = log
        if (site != null) onHeldEnd(site, log != null)
        if (busy) return
        if (site != null && log == null) return // 待っていたのが時間切れで終わっただけ。新しく知らせることは無い
        onDone(lastLog)
    }

    /** 待っているサイトの Collector (無ければ null) */
    fun heldFor(site: String): Collector? = held.entries.firstOrNull { it.value == site }?.key

    /** 全部やめる (onDone は呼ばない) */
    fun cancel() {
        parts.toList().forEach { it.cancel() }
        parts.clear()
        held.clear()
    }
}
