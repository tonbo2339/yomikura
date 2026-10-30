package com.tonbo2339.yomikura

import android.content.Context

/**
 * 作品の ID を 1 件ずつ、[PageProbe] で開いていく流れ (HamelnRating・R18Subs 共通)。1 件ごとに [Politeness] の間隔をあける。
 * メインスレッドで呼ぶ。onResult・finished もメインスレッドで呼ばれる。
 */
object ProbeRun {
    private const val TAG = "Yomikura.probe"

    /**
     * ids を順に urlOf(id) で開く。
     * stop() が true なら、開く前にやめる (間隔をあけたあとに見る)。onResult(id, 結果) が false を返したら、そこでやめる。
     * 終わったら finished(開けなかった id) を呼ぶ (やめた id 自身と、その後ろ。全部開けたら空。呼び出し側が「試した」から外す)。
     */
    fun run(
        ctx: Context, ids: List<String>, urlOf: (String) -> String, stopHosts: Set<String>,
        stop: () -> Boolean = { false },
        onResult: (String, PageProbe.Result) -> Boolean,
        finished: (List<String>) -> Unit,
    ) {
        val main = MainThread.handler
        val probe = try { PageProbe(ctx, stopHosts) } catch (e: Exception) { Diag.w(TAG, "failed", e); finished(ids); return }
        fun end(from: Int) {
            main.post { probe.destroy() } // WebView 自身のコールバックの中で壊さないよう、あとで
            finished(ids.subList(from.coerceAtMost(ids.size), ids.size))
        }
        fun step(i: Int) {
            if (i >= ids.size) return end(ids.size)
            main.postDelayed({
                if (stop()) return@postDelayed end(i)
                try {
                    probe.load(urlOf(ids[i])) { r ->
                        val next = try { onResult(ids[i], r) } catch (e: Exception) { Diag.w(TAG, "failed", e); true }
                        if (next) step(i + 1) else end(i)
                    }
                } catch (e: Exception) { Diag.w(TAG, "failed", e); end(i) }
            }, if (i == 0) 0L else Politeness.pauseMs())
        }
        step(0)
    }
}
