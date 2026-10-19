package com.tonbo2339.yomikura

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * いまの回線の判定 (Android の ConnectivityManager に触る部分を 1 か所に集める)。
 * 一部の Android 11 で SecurityException になることがあるので、調べられなければ「分からない」(null / false) を返し、落ちない。
 */
object Connectivity {
    private fun manager(ctx: Context) = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** いまの回線の性質。つながっていない・調べられないときは null。 */
    fun caps(ctx: Context): NetworkCapabilities? =
        runCatching { manager(ctx).let { it.getNetworkCapabilities(it.activeNetwork) } }.getOrNull()

    /**
     * 従量課金でない回線 (Wi-Fi など) につながっているか。分からなければ従量課金の扱い (false)。
     * validated = true なら、さらに「実際にインターネットへ出られると確かめられた回線」だけ (大きなダウンロード向け)。
     */
    fun unmetered(ctx: Context, validated: Boolean = false): Boolean {
        val c = caps(ctx) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            (!validated || c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    /** いま使える回線があるか。調べられなければ、ある扱い (待ち続けないように) */
    fun hasNetwork(ctx: Context): Boolean = runCatching { manager(ctx).activeNetwork != null }.getOrDefault(true)

    /** 回線の種類の呼び名 (診断情報用)。 */
    fun label(ctx: Context): String {
        val c = caps(ctx)
        return when {
            c == null -> "つながっていない"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "モバイル回線"
            else -> "そのほか"
        }
    }
}
