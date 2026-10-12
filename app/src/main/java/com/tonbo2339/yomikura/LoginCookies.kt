package com.tonbo2339.yomikura

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import org.json.JSONObject

/** 各サイトのログイン情報 (Cookie) の読み取りと消去。 */
object LoginCookies {
    /** marks = あればログイン済みとみなす名前、names = ログアウトで消す名前 (cf_clearance などは消さない) */
    class Entry(val url: String, val marks: List<String>, val names: List<String>)

    /**
     * なろうは R18 のサイトと共通 (.syosetu.com)。名前は実機の WebView の Cookie から確認した (値は見ていない)。
     * ハーメルンはログイン済みの端末しか確認できていないので、SES2 / autologin のどちらかがあれば済みとする。
     */
    val SITES = mapOf(
        "narou" to Entry("https://syosetu.com", listOf("autologin"), listOf("autologin", "ses", "ks2", "userl")), // userl: 公式のログアウト (/login/logout/) のあとも残るログイン関係の Cookie
        "hameln" to Entry("https://syosetu.org", listOf("SES2", "autologin"), listOf("ETURAN", "SES2", "sson", "autologin")),
        "kakuyomu" to Entry("https://kakuyomu.jp", listOf("dlsc"), listOf("dllmc", "dlsc")),
    )

    fun cookieNames(url: String): Set<String> =
        CookieManager.getInstance().getCookie(url).orEmpty().split(";").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }.toSet()

    /** marks のどれかの Cookie があるか (値は見ない) */
    private fun hasCookie(url: String, marks: List<String>): Boolean = cookieNames(url).any { it in marks }

    /** 各サイトのログインの印 (Cookie の有無)。{"narou":true,"hameln":false,"kakuyomu":false} */
    fun state(): JSONObject = JSONObject().also { r -> for ((site, c) in SITES) r.put(site, hasCookie(c.url, c.marks)) }

    /**
     * ログインの有無を保存データの logins に写す (変わったときだけ書く)。更新の巡回 (collectActive) が、ログインしていないサイトを
     * 飛ばすために読む。画面が無い裏の確認でも読めるよう、保存データに置く。ページを開くたびと、設定が調べたときに呼ぶ
     */
    fun sync(ctx: Context, now: JSONObject = state()) {
        if (NHData.obj(ctx).optJSONObject("logins")?.toString() == now.toString()) return
        NHData.update(ctx) { it.put("logins", now) }
    }

    /** そのサイトのログインの印 (SITES) を期限切れにする。知らないサイトは false。 */
    fun expire(site: String): Boolean {
        val c = SITES[site] ?: return false
        expireCookies(c.url, c.names)
        return true
    }

    /** ログイン情報をすべて消す。Cloudflare の確認を通した印 (cf_clearance) は残す。GitHub のログインも消える。 */
    fun clearAll() {
        for (url in listOf("https://syosetu.com", "https://syosetu.org", "https://kakuyomu.jp", "https://github.com")) {
            expireCookies(url, cookieNames(url) - "cf_clearance")
        }
    }

    /**
     * names の Cookie を期限切れにして消す。Cookie の Domain 属性は読み出せないので、ドメイン付きとホストだけの両方で上書きする。
     * 消したあとは flush して、ディスクにも反映する。
     */
    private fun expireCookies(url: String, names: Collection<String>) {
        val cm = CookieManager.getInstance()
        val host = Uri.parse(url).host.orEmpty()
        for (n in names) {
            cm.setCookie(url, "$n=; Max-Age=0; Path=/; Secure") // (__Host- 付きの Cookie は Secure と Path=/ が要る)
            cm.setCookie(url, "$n=; Max-Age=0; Path=/; Secure; Domain=$host")
        }
        cm.flush()
    }

    /** GitHub にログインしているか。ログイン済みならユーザー名 (分からなければ "yes")、未ログインなら "" */
    fun githubUser(): String {
        val c = CookieManager.getInstance().getCookie("https://github.com").orEmpty()
        if (!Regex("(^|; )logged_in=yes(;|$)").containsMatchIn(c)) return ""
        return Regex("(^|; )dotcom_user=([^;]+)").find(c)?.groupValues?.get(2) ?: "yes"
    }
}
