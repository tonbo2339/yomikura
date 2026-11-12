package com.tonbo2339.yomikura

import android.webkit.CookieManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 挿絵を、アプリの中から直接取って端末に保存する。
 * 挿絵は本文のサイトとは別のサイト (画像の置き場所) にあることが多く、サイトのページからの取得は
 * ブラウザの制限 (CORS) で失敗するため、通信はここで行う。
 *
 * 結果は JSON の文字列で返す (JS の窓口 NHOffline.saveImage が使う):
 *   {"name": "i1234abcd.jpg", "bytes": 12345}  保存できた
 *   {"error": "challenge"}                       対応サイト自身の確認画面・アクセス制限 (403 / 429 / 503)。話ごと止める
 *   {}                                          取れなかった・対象外 (挿絵だけ飛ばす)
 */
object ImageSaver {
    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private const val TOTAL_MS = 60_000L // 1 枚の取得の全体の期限

    /**
     * 応答 code が、本文のサイトに止められた印か (話ごと止めて、サイトを休ませる)。
     * 別の置き場所 (画像の預け先) の 403 などは、直リンクの禁止や削除された画像のことが多いので、その挿絵だけ飛ばす
     * (止めると、その挿絵のある話から先へ進めず、同じサイトのほかの作品まで止まってしまう)
     */
    fun stopsSite(code: Int, host: String): Boolean =
        (code == 403 || code == 429 || code == 503) && SiteUrls.isSupportedHost(host)

    /** 挿絵の名前 (JS の imgName と同じ。FNV-1a 32bit の 8 桁 + 拡張子) */
    fun nameFor(url: String, ext: String): String {
        var h = 0x811c9dc5.toInt()
        for (ch in url) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return "i" + String.format("%08x", h) + "." + ext
    }

    /**
     * 種類を中身の並び (先頭のバイト) で決める。配信元が種類を正しく返さないときにも取れるように、拡張子ではなく中身で見る。
     * 対応していない物 (SVG・HTML など) は null
     */
    fun kindOf(b: ByteArray): String? {
        fun at(vararg v: Int) = b.size >= v.size && v.indices.all { (b[it].toInt() and 0xff) == v[it] }
        return when {
            at(0xFF, 0xD8, 0xFF) -> "jpg"
            at(0x89, 0x50, 0x4E, 0x47) -> "png"
            at(0x47, 0x49, 0x46, 0x38) -> "gif"
            at(0x52, 0x49, 0x46, 0x46) && b.size >= 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            else -> null
        }
    }

    /**
     * 取って保存する。referer = 挿絵を載せている話のページ。blocked(host) が true の配信元は取らない。
     * 同じサイトの Cookie を付ける (ログインが要る画像のため)。
     * 転送 (3xx) は自分でたどる: HttpURLConnection に任せると、手で付けた Cookie が転送先の別のホストへもそのまま送られるため。
     * 1 回ごとに https と blocked を確かめ直し、Cookie はその URL のものを付け直す。
     */
    fun save(
        store: OfflineStore, site: String, id: String, url: String, referer: String,
        blocked: (String) -> Boolean = { false }, stillWanted: () -> Boolean = { true },
    ): String {
        var cur = url
        val deadline = System.currentTimeMillis() + TOTAL_MS // 少しずつ送り続けるサーバーで、窓口を握られ続けない
        repeat(MAX_REDIRECTS + 1) {
            if (!cur.startsWith("https://")) return "{}" // 平文の通信は使わない (アプリの方針)
            val u = try { URL(cur) } catch (e: Exception) { return "{}" }
            if (blocked(u.host) || pointsInside(u.host)) return "{}"
            val conn = try { u.openConnection() as HttpURLConnection } catch (e: Exception) { return "{}" }
            val startedAt = System.currentTimeMillis()
            var cookieSent = false
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Referer", refererFor(referer, u.host))
                CookieManager.getInstance().getCookie(cur)?.let { conn.setRequestProperty("Cookie", it); cookieSent = true }
                val code = conn.responseCode
                NetLog.native(conn, cur, startedAt, cookieSent)
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return "{}"
                    cur = try { URL(u, loc).toString() } catch (e: Exception) { return "{}" }
                    return@repeat
                }
                if (stopsSite(code, u.host)) return """{"error":"challenge"}"""
                if (code !in 200..299) return "{}"
                if (conn.contentLengthLong > OfflineStore.MAX_IMAGE_BYTES) return "{}"
                val bytes = readLimited(conn.inputStream, OfflineStore.MAX_IMAGE_BYTES, deadline) ?: return "{}"
                return keep(store, site, id, url, bytes, stillWanted)
            } catch (e: Exception) {
                return "{}"
            } finally {
                conn.disconnect()
            }
        }
        return "{}" // 転送が多すぎる
    }

    /** 取った中身 (bytes) の種類を確かめて保存し、結果の JSON を返す (save と saveData の共通部分) */
    private fun keep(store: OfflineStore, site: String, id: String, url: String, bytes: ByteArray, stillWanted: () -> Boolean): String {
        val ext = kindOf(bytes) ?: return "{}"
        val name = nameFor(url, ext) // 名前は元の URL で決める (話の本文の img と対応させるため)
        val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
        // 通信のあいだに作品が消されていたら、書き戻さない
        return if (store.guarded(stillWanted) { store.putImage(site, id, name, b64) }) """{"name":"$name","bytes":${bytes.size}}""" else "{}"
    }

    /**
     * ページの中 (WebView) で取った挿絵 (base64) を保存する。url は取った場所 (名前を決めるのと、https・blocked の確認に使う)。
     * 本文と同じサイトにある挿絵は、アプリ自身の通信だと Cloudflare に止められる (403) ので、WebView の fetch で取り、ここへ渡す。
     * 結果は save と同じ形。
     */
    fun saveData(
        store: OfflineStore, site: String, id: String, url: String, base64: String,
        blocked: (String) -> Boolean = { false }, stillWanted: () -> Boolean = { true },
    ): String {
        if (!url.startsWith("https://")) return "{}"
        val host = try { URL(url).host } catch (e: Exception) { return "{}" }
        if (blocked(host) || base64.length > OfflineStore.MAX_IMAGE_BASE64) return "{}"
        val bytes = try { java.util.Base64.getDecoder().decode(base64) } catch (e: IllegalArgumentException) { return "{}" }
        return keep(store, site, id, url, bytes, stillWanted)
    }

    /** 本文のサイトの外へ出すときは、話のページの全体ではなく、サイトの名前 (origin) だけを教える (WebView の既定と同じ) */
    internal fun refererFor(referer: String, host: String): String {
        val r = try { URL(referer) } catch (e: Exception) { return "" }
        return if (r.host == host) referer else "${r.protocol}://${r.host}/"
    }

    /** 自分の端末や家の中 (127.x・10.x・192.168.x など) を指す名前か。挿絵の取得先にさせない (見つからない名前も取らない) */
    private fun pointsInside(host: String): Boolean = try {
        java.net.InetAddress.getAllByName(host).any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }
    } catch (e: Exception) {
        true
    }

    /** 上限を超える・期限 (deadline の時刻) を過ぎたら null (大きすぎる物・遅すぎる物は読み切らない) */
    private fun readLimited(input: java.io.InputStream, max: Int, deadline: Long): ByteArray? = input.use { ins ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max || System.currentTimeMillis() > deadline) return null
        }
        out.toByteArray()
    }
}
