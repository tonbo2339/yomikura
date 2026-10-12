package com.tonbo2339.yomikura

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * アプリの更新: GitHub のリリースから、新しいバージョンの APK を探してダウンロードする。
 *
 * 非公開のリポジトリ (needsLogin) なら、アプリ内の WebView で github.com にログインしておく (その Cookie を借りる)。公開のリポジトリはログイン不要で、Cookie も送らない。
 *  - 最新: https://github.com/<repo>/releases/latest がタグのページへ転送される (未ログインなら 404 かログイン画面)
 *  - APK:  https://github.com/<repo>/releases/expanded_assets/<tag> の HTML にある /releases/download/ のリンク
 * Cookie を付けるのは github.com へのリクエストだけ。転送先 (署名付き URL) には付けない。
 * 通信は呼び出し側のスレッドで行う (UI スレッドでは呼ばない)。
 */
class Updater(private val context: Context, private val repo: String, private val needsLogin: Boolean) {

    /** 確認の結果。state = latest (最新) / available (更新あり) / login (ログインが必要) / error */
    data class Check(val state: String, val latest: String = "", val message: String = "")

    /** 最後の確認で見つかった APK の URL。インストールはこの URL だけを使う (画面側から URL を受け取らない)。 */
    @Volatile
    private var apkUrl: String? = null

    /** 確認で APK が見つかっているか (アプリを閉じると消える。画面側の覚えている結果より短命)。 */
    val hasApk: Boolean get() = apkUrl != null

    /** ダウンロード中か (二重に始めると、同じファイルへ同時に書いて壊れるので、1 本ずつにする) */
    private val downloading = AtomicBoolean(false)

    private fun open(url: String, userAgent: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", userAgent)
        if (needsLogin && URL(url).host == "github.com") {
            CookieManager.getInstance().getCookie("https://github.com")?.let { c.setRequestProperty("Cookie", it) }
        }
        return c
    }

    fun check(userAgent: String, current: String): Check {
        apkUrl = null
        val c = open("https://github.com/$repo/releases/latest", userAgent)
        val code = c.responseCode
        val location = c.getHeaderField("Location").orEmpty()
        c.disconnect()
        earlyResult(code, location, needsLogin)?.let { return it }
        val tag = Regex("/releases/tag/([^/?#]+)").find(location)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
            ?: return Check("error", message = "公開されたリリースがありません")
        val latest = tag.removePrefix("v")
        if (!isNewer(latest, current)) return Check("latest", latest)

        val a = open("https://github.com/$repo/releases/expanded_assets/$tag", userAgent)
        a.setRequestProperty("Accept", "text/html")
        if (a.responseCode != 200) {
            a.disconnect()
            return Check("error", latest, "リリースの内容を読めませんでした")
        }
        val html = try { a.inputStream.bufferedReader().use { it.readText() } } finally { a.disconnect() }
        val href = Regex("href=\"([^\"]*/releases/download/[^\"]+\\.apk)\"").find(html)?.groupValues?.get(1)
            ?: return Check("error", latest, "リリースに APK がありません")
        val url = (if (href.startsWith("/")) "https://github.com$href" else href).replace("&amp;", "&")
        if (URL(url).host != "github.com") return Check("error", latest, "APK の場所が想定と違います")
        apkUrl = url
        return Check("available", latest)
    }

    /** 見つかった APK を cache/update/ に保存する。進み具合 (0〜100) を onProgress に伝える。 */
    fun download(userAgent: String, onProgress: (Int) -> Unit): File {
        if (!downloading.compareAndSet(false, true)) throw IllegalStateException("すでにダウンロード中です")
        try {
            return downloadOnce(userAgent, onProgress)
        } finally {
            downloading.set(false)
        }
    }

    private fun downloadOnce(userAgent: String, onProgress: (Int) -> Unit): File {
        var url = apkUrl ?: throw IllegalStateException("先に更新を確認してください")
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Yomikura-update.apk")

        // 転送先 (署名付き URL) は github.com 以外なので、Cookie を付けずに順にたどる
        repeat(6) {
            val c = open(url, userAgent)
            val code = c.responseCode
            if (code in 300..399) {
                val next = URL(URL(url), c.getHeaderField("Location") ?: throw IllegalStateException("転送先がありません")).toString()
                c.disconnect()
                if (!isAllowedRedirect(next)) {
                    throw IllegalStateException("想定外の場所へ転送されました")
                }
                url = next
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                throw IllegalStateException("ダウンロードできませんでした ($code)")
            }
            val total = c.contentLengthLong
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
            c.disconnect()
            if (total > 0 && done != total) throw IllegalStateException("ダウンロードが途中で切れました")
            return out
        }
        throw IllegalStateException("転送が多すぎます")
    }

    /** ダウンロードした APK が、このアプリの更新として入れられるものか。問題があればその説明、無ければ null。 */
    fun verify(apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return "ダウンロードしたファイルを読めませんでした"
        if (info.packageName != context.packageName) return "別のアプリのファイルです"
        val mine = signers(pm.getPackageInfo(context.packageName, flags))
        val theirs = signers(info)
        if (mine.isEmpty() || theirs.isEmpty()) return "署名を確認できませんでした"
        if (mine.none { it in theirs }) return "署名が今のアプリと違うため更新できません。テスト用 (debug) の版が入っているときは、一度アンインストールしてから入れてください"
        return null
    }

    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs?.map { it.toCharsString() } ?: emptyList()
    }

    companion object {
        /**
         * 最新のリリースを尋ねた応答 (HTTP の状態と転送先) から、タグを読むまでもなく決まる結果。タグへの転送なら null (先へ進む)。
         * 非公開のリポジトリ (needsLogin) は、ログインしていないと 404 かログイン画面への転送 = login。
         * 公開のリポジトリの 404 は、ログインの問題ではなく、リリースがまだ無い (またはリポジトリが無い)
         */
        fun earlyResult(code: Int, location: String, needsLogin: Boolean): Check? = when {
            needsLogin && (code == 404 || (code in 300..399 && "/login" in location)) -> Check("login")
            code == 404 -> Check("error", message = "公開されたリリースがありません")
            code !in 300..399 -> Check("error", message = "GitHub が応答しませんでした ($code)")
            else -> null
        }

        /** ダウンロードの転送先として許す URL か (https の github.com と、*.githubusercontent.com だけ) */
        fun isAllowedRedirect(next: String): Boolean {
            if (!next.startsWith("https://")) return false
            val host = try { URL(next).host } catch (e: Exception) { return false }
            return host == "github.com" || host.endsWith(".githubusercontent.com")
        }

        /**
         * latest が current より新しいか (「0.10.2」のように数字を . でつないだ形)。
         * 「1.2.3-beta」のような後ろの印 (プレリリース) は、同じ数字の正式版より古い。「+build」以降は無視する。
         */
        fun isNewer(latest: String, current: String): Boolean {
            val (a, pa) = split(latest)
            val (b, pb) = split(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return when {
                pa == pb -> false
                pa == null -> true      // latest が正式版
                pb == null -> false     // current が正式版
                else -> comparePre(pa, pb) > 0
            }
        }

        private fun split(v: String): Pair<List<Int>, String?> {
            val noBuild = v.substringBefore('+')
            val core = noBuild.substringBefore('-')
            val pre = if ('-' in noBuild) noBuild.substringAfter('-') else null
            return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }

        private fun comparePre(a: String, b: String): Int {
            val x = a.split('.')
            val y = b.split('.')
            for (i in 0 until minOf(x.size, y.size)) {
                val m = x[i].toIntOrNull()
                val n = y[i].toIntOrNull()
                val c = when {
                    m != null && n != null -> m.compareTo(n)
                    m != null -> -1 // 数字は文字より小さい
                    n != null -> 1
                    else -> x[i].compareTo(y[i])
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}
