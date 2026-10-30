package com.tonbo2339.yomikura

import android.webkit.WebResourceRequest
import java.net.HttpURLConnection

/**
 * 通信の出来事を [DevLog] (分類 net) に渡す入口。どのサイトへ何を取りに行って、何が返ったかを、URL を省かずに残す。Cookie・ログイン情報の中身は残さない。
 * 1 件の要約は `<種類> <メソッド> <コード> <URL>`、項目に所要時間・Cookie を送ったか・止められた理由を見分けるヘッダー。
 * 種類: `native` = アプリ自身の通信 (HttpURLConnection)、`web:<場所>` = WebView の移動・HTTP エラー。
 */
object NetLog {
    private fun log(kind: String, method: String, code: Int?, url: String, fields: String) =
        DevLog.event(DevLog.Category.NET, "$kind $method ${code ?: "-"} $url", fields)

    /** アプリ自身の通信 1 件の結果。応答のヘッダーのうち、止められた理由を見分けるものだけ添える (Cookie の中身は出さない) */
    fun native(c: HttpURLConnection, url: String, startedAt: Long, cookieSent: Boolean) {
        if (!DevLog.on(DevLog.Category.NET)) return
        val code = runCatching { c.responseCode }.getOrNull()
        val fields = buildList {
            add("${System.currentTimeMillis() - startedAt}ms")
            add(if (cookieSent) "cookie=sent" else "cookie=none")
            for (h in listOf("Server", "cf-mitigated", "Location", "Content-Type", "Retry-After")) c.getHeaderField(h)?.let { add("$h=$it") }
        }.joinToString(" ")
        log("native", c.requestMethod, code, url, fields)
    }

    /** 通信が例外で終わったとき */
    fun nativeFailed(url: String, startedAt: Long, e: Exception) =
        log("native", "GET", null, url, "${System.currentTimeMillis() - startedAt}ms ${e.javaClass.simpleName}: ${e.message ?: ""}")

    /** WebView の移動 (最上位のページだけ。広告・画像などの部分は数が多いので記録しない) */
    fun page(where: String, request: WebResourceRequest) {
        if (!request.isForMainFrame || !DevLog.on(DevLog.Category.NET)) return
        log("web:$where", request.method, null, request.url.toString(), "navigate")
    }

    /** WebView の HTTP エラー (400 以上)。部分の読み込みの失敗も残す */
    fun webError(where: String, request: WebResourceRequest, status: Int, headers: Map<String, String>? = null) {
        if (!DevLog.on(DevLog.Category.NET)) return
        val fields = buildList {
            add(if (request.isForMainFrame) "main" else "sub")
            for (h in listOf("server", "cf-mitigated", "content-type")) headers?.entries?.firstOrNull { it.key.equals(h, true) }?.let { add("$h=${it.value}") }
        }.joinToString(" ")
        log("web:$where", request.method, status, request.url.toString(), fields)
    }
}
