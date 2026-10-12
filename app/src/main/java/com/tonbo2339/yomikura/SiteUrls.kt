package com.tonbo2339.yomikura

import java.net.URI

/**
 * 対応サイトの URL の判定。画面 (MainActivity) から切り離した、Android に依存しない計算 (単体テストで動かせる)。
 * ホストとパスは java.net.URI で読む (読めない文字列は「どこでもない」として扱う)。
 */
object SiteUrls {
    /** ほかのアプリから受け取る URL のホスト (サブドメインも含む)。マニフェストの intent-filter と揃える */
    private val SUPPORTED_HOSTS = listOf("syosetu.com", "syosetu.org", "kakuyomu.jp")
    // URL に使える ASCII 文字だけ (共有テキストの「」や全角の句読点を URL に含めない)
    private val URL_IN_TEXT = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&()*+,;=%]+""")
    private val NCODE_PATH = Regex("^/n\\d{4}[a-z]+(/|$)")
    private val HAMELN_PATH = Regex("^/novel/\\d+")
    private val KAKUYOMU_PATH = Regex("^/works/\\d+")

    fun hostOf(url: String?): String = try { URI(url ?: "").host.orEmpty() } catch (e: Exception) { "" }
    fun pathOf(url: String?): String = try { URI(url ?: "").path.orEmpty() } catch (e: Exception) { "" }
    private fun queryParam(url: String, name: String): String =
        try { URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "").orEmpty() } catch (e: Exception) { "" }

    /** R18 のサイトのホスト (ノクターン・ムーンライト・ミッドナイト・R18 の作品と感想・ハーメルンの R18)。NSFW モードで通信を止める対象 */
    private val R18_HOSTS = setOf("noc.syosetu.com", "mnlt.syosetu.com", "mid.syosetu.com", "novel18.syosetu.com", "novelcom18.syosetu.com", "h.syosetu.org")

    /** R18 のサイトの URL か (R18 のブックマーク /favnovelmain18/ も含む)。 */
    fun isR18Url(url: String?): Boolean {
        val host = hostOf(url)
        return host in R18_HOSTS || (host == "syosetu.com" && pathOf(url).startsWith("/favnovelmain18"))
    }

    /** R18 の作品 (リストに入れるための最小限の情報)。サイトへアクセスしなくても URL だけで作れる範囲。 */
    data class R18Work(val site: String, val id: String, val url: String, val sub: String?)

    private val NCODE_ID = Regex("^/(n\\d{4}[a-z]+)")
    private val HAMELN_ID = Regex("^/novel/(\\d+)")

    /** R18 の作品の URL (なろう系の R18・ハーメルンの R18) から、リストに入れる情報を作る。作品の URL でなければ null。 */
    fun r18WorkOf(url: String?): R18Work? {
        if (!isR18Url(url)) return null
        val host = hostOf(url)
        val path = pathOf(url)
        if (host == "h.syosetu.org") {
            val id = HAMELN_ID.find(path)?.groupValues?.get(1) ?: return null
            return R18Work("hameln", id, "https://syosetu.org/novel/$id/", null)
        }
        val id = NCODE_ID.find(path)?.groupValues?.get(1) ?: return null
        val sub = host.removeSuffix(".syosetu.com").takeIf { it in setOf("noc", "mnlt", "mid") }
        return R18Work("narou18", id, "https://novel18.syosetu.com/$id/", sub)
    }

    /** 対応サイト (サブドメインも含む) のホストか。 */
    fun isSupportedHost(host: String) = SUPPORTED_HOSTS.any { host == it || host.endsWith(".$it") }

    /** 作品のページ (作品トップ・話ページ) の URL か。ランキングや検索などは false。 */
    fun isWorkUrl(url: String?): Boolean {
        val path = pathOf(url)
        return when (hostOf(url)) {
            "ncode.syosetu.com", "novel18.syosetu.com" -> NCODE_PATH.containsMatchIn(path)
            "syosetu.org" -> HAMELN_PATH.containsMatchIn(path)
            "kakuyomu.jp" -> KAKUYOMU_PATH.containsMatchIn(path)
            else -> false
        }
    }

    /** URL → サイトのキー (narou / hameln / kakuyomu / noc / mnlt / mid)。どのサイトでもなければ null。 */
    fun siteKeyOf(url: String?): String? {
        val host = hostOf(url)
        return when {
            host == "noc.syosetu.com" -> "noc"
            host == "mnlt.syosetu.com" -> "mnlt"
            host == "mid.syosetu.com" -> "mid"
            host == "novel18.syosetu.com" -> null
            host == "syosetu.com" || host.endsWith(".syosetu.com") -> "narou"
            host == "syosetu.org" || host.endsWith(".syosetu.org") -> "hameln"
            host == "kakuyomu.jp" -> "kakuyomu"
            else -> null
        }
    }

    /** そのサイトのランキングのページか (なろう系は /rank/ 以下、ハーメルンは ?mode=rank*、カクヨムは /rankings)。 */
    fun isRankingPage(key: String, url: String?): Boolean {
        val host = hostOf(url)
        val path = pathOf(url)
        return when (key) {
            "narou" -> host == "yomou.syosetu.com" && path.startsWith("/rank/")
            "noc", "mnlt", "mid" -> host == "$key.syosetu.com" && path.startsWith("/rank/")
            "hameln" -> host == "syosetu.org" && url != null && queryParam(url, "mode").startsWith("rank")
            "kakuyomu" -> host == "kakuyomu.jp" && path.startsWith("/rankings")
            else -> false
        }
    }

    /** 共有されたテキストの中の最初の URL (末尾の句読点は除く)。無ければ null。 */
    fun firstUrlIn(text: String): String? = URL_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ')', '!', '?')

    /**
     * 対応サイトの URL なら https にそろえて返す。それ以外は null。
     * スキームは http / https (大文字小文字は問わない) だけ受け付ける: `javascript://syosetu.com/%0Aalert(1)` のように
     * ホストだけ対応サイトに見せた URL が WebView の loadUrl に届かないようにするため。
     */
    fun toSupportedLink(raw: String): String? {
        val scheme = raw.substringBefore(':', "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (!raw.regionMatches(scheme.length, "://", 0, 3)) return null
        if (!isSupportedHost(hostOf(raw))) return null
        return "https" + raw.substring(scheme.length)
    }

    /** URL のクエリに name があるか (値が空でもよい)。 */
    fun hasQueryParam(url: String?, name: String): Boolean =
        try { URI(url ?: "").rawQuery.orEmpty().split('&').any { it.substringBefore('=') == name } } catch (e: Exception) { false }

    /** URL のスキーム (小文字)。読めなければ空。 */
    fun schemeOf(url: String?): String = try { URI(url ?: "").scheme.orEmpty().lowercase() } catch (e: Exception) { "" }
}
