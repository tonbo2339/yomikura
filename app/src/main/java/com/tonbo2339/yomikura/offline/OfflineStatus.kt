package com.tonbo2339.yomikura

import org.json.JSONObject

/**
 * 保存サービス (OfflineService) の「いま」の様子。サービスが書き、上部の帯 (MainActivity) と保存キューの画面が読む。
 * キューの中身 (順番・状態) は SaveQueue に残るので、ここには保存中の 1 作品の進みと、サービスが動いているかだけを置く。
 * サービスと画面は同じプロセスなので、置き場はメモリで足りる (プロセスが終われば、サービスも止まっている)。
 */
object OfflineStatus {
    /** 保存中の作品の進み。paused = 利用者が一時停止、waitingWifi = Wi-Fi 待ち */
    data class Live(val key: String, val title: String, val ok: Int, val total: Int, val paused: Boolean, val waitingWifi: Boolean)

    @Volatile var live: Live? = null
        private set

    /** サービスが動いているか (止まっているときに、一時停止などの操作を送らないため) */
    @Volatile var running: Boolean = false
        private set

    /** 変わったときに呼ぶ。メインスレッドで呼ばれる (画面を直す側は、自分で post しなくてよい) */
    @Volatile var onChange: (() -> Unit)? = null

    fun publish(live: Live?, running: Boolean) {
        this.live = live
        this.running = running
        onChange?.invoke()
    }

    /** 保存キューの画面 (JS) に渡す形 */
    fun toJson(): String {
        val o = JSONObject().put("running", running)
        live?.let {
            o.put("key", it.key).put("title", it.title).put("ok", it.ok).put("total", it.total)
                .put("paused", it.paused).put("waitingWifi", it.waitingWifi)
        }
        return o.toString()
    }
}
