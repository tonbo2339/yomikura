package com.tonbo2339.yomikura

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.webkit.WebView

/** 診断情報を集める (Android の情報はここで読み、文の組み立ては DiagReport)。 */
object Diagnostics {
    /** 小説サイトの Cookie の名前だけ (値は読まない・出さない)。年齢の確認など、サイト側の状態を調べるときに使う */
    private fun cookieNames(): List<String> {
        val cm = android.webkit.CookieManager.getInstance()
        return listOf("https://syosetu.org/", "https://h.syosetu.org/").map { url ->
            val names = cm.getCookie(url).orEmpty().split(";").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }
            "${url.removePrefix("https://").trimEnd('/')}: ${names.sorted().joinToString(", ").ifEmpty { "なし" }}"
        }
    }

    fun report(ctx: Context): String {
        val app = ctx.applicationContext
        val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
        val web = WebView.getCurrentWebViewPackage()
        val night = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val net = Connectivity.label(app)
        val sections = listOf(
            "アプリ" to listOf(
                "${BuildConfig.APP_NAME} ${pkg.versionName} ($code)",
                "版: ${Edition.ID}${if (BuildConfig.DEBUG) " (debug)" else ""} / ${app.packageName}",
            ),
            "端末" to listOf(
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL}",
                "WebView: ${web?.let { "${it.packageName} ${it.versionName}" } ?: "不明"}",
                "表示: ${if (night) "ダーク" else "ライト"} / 文字 ${app.resources.configuration.fontScale}倍 / ${app.resources.configuration.locales[0]}",
                "回線: $net",
            ),
            "通知・省電力" to listOf(
                "通知: ${if (Notices.allowed(app)) "許可" else "不許可"}",
                "電池の最適化から除外: ${if (pm.isIgnoringBatteryOptimizations(app.packageName)) "はい" else "いいえ"}",
                "裏での確認の予定: ${BgCheck.plan(app).ifEmpty { "なし" }}",
            ),
            "データ" to DiagReport.dataSummary(NHData.obj(app)),
            "サイトの Cookie (名前だけ。中身は出さない)" to cookieNames(),
        )
        val log = Diag.entries().asReversed().map { DiagBuffer.line(it) }
        return DiagReport.format(BuildConfig.APP_NAME, sections, log)
    }
}
