package com.tonbo2339.yomikura

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * オフライン保存の置き場。端末の内部ストレージ (アプリ専用の領域) に、作品ごとのフォルダを作る。
 *   <root>/<site>/<id>/meta.json    作品の情報と、保存した話の並び
 *   <root>/<site>/<id>/ep_<ep>.json 1 話ぶん (題名と本文の HTML)。gzip で圧縮して保存する (日本語の本文は約 1/3 になる)。
 *                                   読むときは先頭の印で見分けるので、圧縮していない (以前の) ファイルも読める
 * 名前に使える文字を絞ってあるので、渡された値でフォルダの外へ書き出すことはない。
 */
class OfflineStore(private val root: File) {

    private val safe = Regex("^[A-Za-z0-9_-]{1,40}$")

    private fun dir(site: String, id: String): File? =
        if (safe.matches(site) && safe.matches(id)) File(root, "$site/$id") else null

    private fun epFile(site: String, id: String, ep: String): File? =
        if (safe.matches(ep)) dir(site, id)?.let { File(it, "ep_$ep.json") } else null

    // 途中で止まっても壊れたファイルを残さないよう、一時ファイルに書いてから置き換える
    private fun write(f: File, text: String, compress: Boolean = false): Boolean {
        if (text.length > MAX_CHARS) return false
        return try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            // 置き換える前にディスクへ書き切る (電源が切れても、空のファイルに置き換わらないように)
            java.io.FileOutputStream(tmp).use { it.write(encode(text, compress)); it.fd.sync() }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            f.parentFile?.let { sizes.remove(it.absolutePath) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun putEpisode(site: String, id: String, ep: String, json: String): Boolean =
        epFile(site, id, ep)?.let { write(it, json, compress = true) } ?: false

    fun putMeta(site: String, id: String, json: String): Boolean {
        val d = dir(site, id) ?: return false
        val f = File(d, "meta.json")
        // フォルダにあるのに目録に無い話は、目録の後ろに足す。前の目録が読めない (壊れた) と、JS は保存済みの話を知らないまま
        // 今回の話だけの目録を書いてくるので、そのままだと前に保存した話が読めなくなる (直したあとの書き込みでも、毎回足す)
        return write(f, withSavedEpisodes(d, json))
    }

    /** 目録 (json) に無い、フォルダにある話 (ep_<話>.json) を、目録の eps の後ろに足す。読めなければ json のまま */
    private fun withSavedEpisodes(d: File, json: String): String = try {
        val o = JSONObject(json)
        val files = d.listFiles().orEmpty().map { it.name }.filter { it.startsWith("ep_") && it.endsWith(".json") }
        val eps = o.optJSONArray("eps") ?: JSONArray().also { o.put("eps", it) }
        val have = (0 until eps.length()).mapNotNull { eps.optJSONObject(it)?.optString("ep") }.toSet()
        val missing = files.map { it.removePrefix("ep_").removeSuffix(".json") }.filter { it !in have }
            .sortedWith(compareBy<String>({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it })) // 話番号は数の順 (2 が 10 より前)
        missing.forEach { ep -> eps.put(JSONObject().put("ep", ep).put("title", "第 $ep 話").put("url", "")) }
        if (missing.isEmpty()) json else o.toString() // 足す話が無ければ、渡されたとおりに書く
    } catch (e: Exception) {
        json
    }

    fun episode(site: String, id: String, ep: String): String =
        epFile(site, id, ep)?.takeIf { it.isFile }?.let { read(it) }.orEmpty()

    fun meta(site: String, id: String): String =
        dir(site, id)?.let { File(it, "meta.json") }?.takeIf { it.isFile }?.let { read(it) }.orEmpty()

    /** 保存してある作品の一覧 (meta に容量 bytes を足したもの) を JSON の配列で返す。 */
    fun list(): String {
        val out = JSONArray()
        root.listFiles()?.filter { it.isDirectory }?.forEach { s ->
            s.listFiles()?.filter { it.isDirectory }?.forEach { w ->
                val m = File(w, "meta.json")
                if (!m.isFile) return@forEach
                try {
                    val o = JSONObject(read(m))
                    o.put("bytes", size(w))
                    out.put(o)
                } catch (e: Exception) {
                    // 読めない作品は一覧に出さない
                }
            }
        }
        return out.toString()
    }

    /**
     * 挿絵 1 枚を保存する (base64)。名前は i + 8 桁の 16 進 + 拡張子 (JS の imgName と同じ形) だけ受け付ける。
     * 大きさの上限と、中身の種類 (先頭の並び) を確かめる。壊れた物や違う種類は保存しない。
     */
    fun putImage(site: String, id: String, name: String, base64: String): Boolean {
        val f = imageFile(site, id, name, forWrite = true) ?: return false
        if (base64.length > MAX_IMAGE_BASE64) return false
        val bytes = try { java.util.Base64.getDecoder().decode(base64) } catch (e: IllegalArgumentException) { return false }
        if (bytes.size > MAX_IMAGE_BYTES || !looksLike(bytes, name)) return false
        return try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            dir(site, id)?.let { sizes.remove(it.absolutePath) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 保存してある挿絵の名前 (外へ送るときに使う) */
    fun imageNames(site: String, id: String): List<String> =
        dir(site, id)?.let { File(it, "img") }?.listFiles()?.map { it.name }?.filter { safeImage.matches(it) }?.sorted().orEmpty()

    /** 挿絵の中身 (外へ送るため)。無い・名前が違うときは null */
    fun imageBytes(site: String, id: String, name: String): ByteArray? = imageFile(site, id, name)?.readBytes()

    /** 保存した挿絵のファイル (読む画面の配信に使う)。無い・名前が違うときは null */
    fun imageFile(site: String, id: String, name: String): File? = imageFile(site, id, name, forWrite = false)

    private fun imageFile(site: String, id: String, name: String, forWrite: Boolean): File? {
        if (!safeImage.matches(name)) return null
        val f = dir(site, id)?.let { File(File(it, "img"), name) } ?: return null
        return if (forWrite || f.isFile) f else null
    }

    fun delete(site: String, id: String): Boolean {
        val d = dir(site, id) ?: return false
        return d.deleteRecursively().also { sizes.remove(d.absolutePath) }
    }

    fun deleteAll(): Boolean = (!root.exists() || root.deleteRecursively()).also { sizes.clear() }

    /** ファイルを文字列で読む。圧縮してあれば (先頭の印で見分ける) 展開する。壊れた圧縮は空 */
    private fun read(f: File): String = try { decode(f.readBytes()) } catch (e: Exception) { "" }

    /** 作品のフォルダの大きさ。数えるのは全ファイルを歩くので重い。書き込みや削除のたびに忘れ、次の一覧で数え直す */
    private fun size(f: File): Long = sizes.getOrPut(f.absolutePath) { f.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    companion object {
        /** 作品のフォルダごとの大きさ。画面とサービスが別の OfflineStore で同じ場所を書くので、置き場は共有する */
        private val sizes = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /** 文字列を保存する形にする。compress なら gzip (UTF-8 の本文は約 1/3)。しなければ UTF-8 のまま */
        fun encode(text: String, compress: Boolean): ByteArray {
            val raw = text.toByteArray(Charsets.UTF_8)
            if (!compress) return raw
            val out = java.io.ByteArrayOutputStream(raw.size / 3 + 64)
            GZIPOutputStream(out).use { it.write(raw) }
            return out.toByteArray()
        }

        /** 保存した形から文字列に戻す。先頭が gzip の印 (1f 8b) なら展開、そうでなければ UTF-8 のまま (JSON の文字は { などで始まるので、取り違えない) */
        fun decode(bytes: ByteArray): String =
            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
            } else bytes.toString(Charsets.UTF_8)

        /** 1 回の保存の上限 (文字数)。想定外に大きなデータで容量を食わないように */
        const val MAX_CHARS = 3_000_000
        /** 挿絵 1 枚の上限 (JS の IMG_MAX と同じ 8MB) と、base64 の上限 (約 4/3 倍 + 余裕) */
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGE_BASE64 = MAX_IMAGE_BYTES * 4 / 3 + 16

        private val safeImage = Regex("^i[0-9a-f]{8}\\.(jpg|png|gif|webp)$")

        /** 配信するときの種類 (拡張子から)。挿絵だけを扱う (それ以外は null) */
        fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "")) {
            "jpg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null
        }

        /** 先頭の数バイトで種類を確かめる (拡張子だけを信じない) */
        fun looksLike(b: ByteArray, name: String): Boolean {
            fun at(vararg v: Int) = b.size >= v.size && v.indices.all { (b[it].toInt() and 0xff) == v[it] }
            return when (name.substringAfterLast('.', "")) {
                "jpg" -> at(0xFF, 0xD8, 0xFF)
                "png" -> at(0x89, 0x50, 0x4E, 0x47)
                "gif" -> at(0x47, 0x49, 0x46, 0x38)
                "webp" -> at(0x52, 0x49, 0x46, 0x46) && b.size >= 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP"
                else -> false
            }
        }
    }
}
