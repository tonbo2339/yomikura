package com.tonbo2339.yomikura

import android.content.Context
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/** アプリ内ページ (https://yomikura.local/…) と注入 JS の中身を、assets と保存した挿絵から作る。 */
object AppAssets {
    fun readAsset(ctx: Context, path: String): String =
        ctx.assets.open(path).bufferedReader().use { it.readText() }

    /** 版ごとの設定を JS (sites.js など) に渡す。sites.js より先に置く */
    val editionJs: String
        get() = "window.NH_EDITION=" + JSONObject()
            .put("r18OptIn", Edition.R18_OPT_IN).put("appName", BuildConfig.APP_NAME).put("backupApp", Edition.BACKUP_APP).put("backupApps", org.json.JSONArray(Edition.BACKUP_ACCEPTS)).put("publicRepo", Edition.PUBLIC_REPO) + ";\n"

    /** サイトのページに注入する JS (画面の WebView と、裏での確認の WebView で共通)。 */
    fun siteScripts(ctx: Context): String = editionJs + listOf("sites.js", "core.js", "narou.js", "hameln.js", "kakuyomu.js", "offline.js", "offline-bg.js", "login.js", "ui-save.js", "ui-list.js", "ui.js", "sitecheck.js", "reader.js")
        .joinToString("\n;\n") { name -> readAsset(ctx, "js/$name") }

    private fun empty(status: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    fun forbidden() = empty(403, "Forbidden")

    /** NSFW モード中に、リンクのタップ以外 (アプリ自身の読み込みなど) で R18 のページを開こうとしたときの画面。通信はしない。目立たないよう、ほぼ空白 */
    fun nsfwBlockedPage(): WebResourceResponse {
        val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<body style='font:13px sans-serif;margin:0;padding:24px;color:#999;background:#f2f2f7'>" + Edition.R18_OFF_WHY + "開きません"
        return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))
    }

    /** 保存した挿絵を返す。path は <サイト>/<ID>/<名前>。ファイルが無い・名前が違うときは 404 */
    fun offlineImage(offline: OfflineStore, path: String): WebResourceResponse {
        val parts = path.split('/')
        val file = if (parts.size == 3) offline.imageFile(parts[0], parts[1], parts[2]) else null
        val mime = OfflineStore.mimeOf(parts.getOrElse(2) { "" })
        if (file == null || mime == null) return empty(404, "Not Found")
        return WebResourceResponse(mime, null, FileInputStream(file))
    }

    /**
     * `<!--#include 名前-->` を assets/web/<名前> の中身に置き換える (Includes。サブフォルダ・入れ子も可)。
     * 設定画面は settings/ の下のページごとのファイルを読み込んで組み立てる。版によってだけある画面・処理の差し込み口は、その版にファイルが無ければ空
     */
    private fun expandIncludes(ctx: Context, html: String): String = Includes.expand(html, { name -> readAsset(ctx, "web/$name") })

    /** アプリ内ページ。path は https://yomikura.local/ 以降 (/js/ 以下は assets/js、それ以外は assets/web)。 */
    fun appAsset(ctx: Context, path: String): WebResourceResponse {
        val mime = when (path.substringAfterLast('.', "")) {
            "html" -> "text/html"
            "svg" -> "image/svg+xml"
            "js" -> "application/javascript"
            "css" -> "text/css"
            else -> "application/octet-stream"
        }
        return try {
            if (".." in path) throw IllegalArgumentException(path)
            // settings/ の下は、設定画面を組み立てる部品。include でだけ読む (直接は開かせない)
            if (path.startsWith("settings/")) throw IllegalArgumentException(path)
            val file = if (path.startsWith("js/")) path else "web/$path"
            if (mime == "text/html") {
                // 最初のページを描く前に、ログインの控え (logins) を最新にする。ページの読み込み完了を待つと、最初の 1 回だけ古い (無い) 控えで描かれる
                LoginCookies.sync(ctx)
                // ページの JS (core.js) が保存データの窓口に添える合言葉を、ほかのスクリプトより先に置く
                val html = expandIncludes(ctx, readAsset(ctx, file)).replaceFirst("<head>", "<head><script>${NHData.tokenJs}$editionJs</script>")
                return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(html.toByteArray()))
            }
            WebResourceResponse(mime, "utf-8", ctx.assets.open(file))
        } catch (e: Exception) {
            empty(404, "Not Found")
        }
    }
}
