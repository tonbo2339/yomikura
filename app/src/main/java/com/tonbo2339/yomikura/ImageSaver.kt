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
 *   {"error": "challenge"}                       確認画面 (403 / 429 / 503)。話ごと止める
 *   {}                                          取れなかった・対象外 (挿絵だけ飛ばす)
 */
object ImageSaver {
    private const val TIMEOUT_MS = 20_000

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
     */
    fun save(store: OfflineStore, site: String, id: String, url: String, referer: String, blocked: (String) -> Boolean = { false }): String {
        if (!url.startsWith("https://")) return "{}" // 平文の通信は使わない (アプリの方針)
        val u = try { URL(url) } catch (e: Exception) { return "{}" }
        if (blocked(u.host)) return "{}"
        val conn = try { u.openConnection() as HttpURLConnection } catch (e: Exception) { return "{}" }
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Referer", referer)
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            val code = conn.responseCode
            if (code == 403 || code == 429 || code == 503) return """{"error":"challenge"}"""
            if (code !in 200..299) return "{}"
            if (conn.contentLengthLong > OfflineStore.MAX_IMAGE_BYTES) return "{}"
            val bytes = readLimited(conn.inputStream, OfflineStore.MAX_IMAGE_BYTES) ?: return "{}"
            val ext = kindOf(bytes) ?: return "{}"
            val name = nameFor(url, ext)
            val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
            if (store.putImage(site, id, name, b64)) """{"name":"$name","bytes":${bytes.size}}""" else "{}"
        } catch (e: Exception) {
            "{}"
        } finally {
            conn.disconnect()
        }
    }

    /** 上限を超えたら null (大きすぎる物は読み切らない) */
    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray? = input.use { ins ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        out.toByteArray()
    }
}
