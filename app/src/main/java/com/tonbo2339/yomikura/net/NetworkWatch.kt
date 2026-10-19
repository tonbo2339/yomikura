package com.tonbo2339.yomikura

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler

/**
 * 回線の変化を見張る (保存のサービスが使う)。回線に着いているか (online) と、従量課金か (metered) を保ち、変わったら listener に知らせる。
 * listener は main (画面のスレッド) で呼ぶ。
 *
 * 回線が切れた / つながった: 切れている間は JS が失敗にせず待つ (短い途切れで止めないため)。
 * 知らせの順番 (前の回線の onLost が、次の回線の onAvailable より後に届くことがある) に頼らず、いまの回線を調べ直す。
 * 従量課金かどうかは、電波の強さなどでも何度も届くので、変わったときだけ知らせる。
 */
class NetworkWatch(private val ctx: Context, private val main: Handler, private val listener: Listener) {
    interface Listener {
        /** つながった / 切れた (online が変わったかもしれない) */
        fun onLinkChanged(available: Boolean)
        /** 従量課金かどうかが変わった */
        fun onMeteredChanged()
    }

    @Volatile var online = true
        private set
    var metered = false
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 最初のページを読む前に、いまの回線を調べておく (回線の知らせが届くまで metered = false のままだと、Wi-Fi のときだけの設定でも最初のページをモバイルで読んでしまう) */
    fun start() {
        metered = runCatching { !Connectivity.unmetered(ctx) }.getOrDefault(false)
        online = Connectivity.hasNetwork(ctx)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { online = Connectivity.hasNetwork(ctx); main.post { listener.onLinkChanged(false) } }
            override fun onAvailable(network: Network) { online = Connectivity.hasNetwork(ctx); main.post { listener.onLinkChanged(true) } }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                main.post {
                    val now = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    if (now == metered) return@post
                    metered = now
                    listener.onMeteredChanged()
                }
            }
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    fun stop() {
        callback?.let { runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        callback = null
    }
}
