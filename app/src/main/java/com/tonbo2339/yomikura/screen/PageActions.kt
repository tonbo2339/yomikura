package com.tonbo2339.yomikura

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.webkit.WebView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONTokener

/**
 * いま開いているページへの操作 (⋮ メニューの「共有」「URL をコピー」「ほかのブラウザで開く」「このページの表示を診断」)。
 * どれも、いまの WebView の URL・タイトル・ページの JS だけを使う。
 */
class PageActions(
    private val activity: AppCompatActivity,
    private val web: () -> WebView,
    private val dp: Float,
) {
    /** 今のページの URL を、共有の画面 (Android の共有シート) へ渡す */
    fun share() {
        val url = web().url ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, web().title.orEmpty())
            .putExtra(Intent.EXTRA_TEXT, url)
        activity.startActivity(Intent.createChooser(send, null))
    }

    fun copyUrl() {
        val url = web().url ?: return
        Clip.copy(activity, "url", url)
        Messages.show(activity, "URL をコピーしました")
    }

    /**
     * 今のページをほかのブラウザで開く。入っているブラウザを自分で探して、1 つずつ行き先を決めた形で並べる。
     * システムの選択画面にまかせると、対応サイトのリンクを開く設定にしたとき、ブラウザが出ない (出ても下のほう) ため。
     * このアプリ自身と、同じ画面を持つ別の版は、除く。
     */
    fun openInBrowser() {
        val url = web().url ?: return
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val targets = activity.packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            .map { it.activityInfo }
            .filter { it.packageName != activity.packageName && it.name != MainActivity::class.java.name }
            .distinctBy { it.packageName }
            .map { Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setClassName(it.packageName, it.name) }
        if (targets.isEmpty()) { Messages.show(activity, "開けるブラウザがありません"); return }
        val chooser = Intent.createChooser(targets.first(), "ブラウザで開く")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
        try { activity.startActivity(chooser) } catch (e: Exception) { Messages.show(activity, "開けませんでした") }
    }

    /**
     * このページの表示の診断 (sites.js のセレクターが、いまのページに何件当たるか。sitecheck.js)。結果は、コピーできる文で出す。
     * サイトの作りが変わって表示が崩れたとき、どこが当たらなくなったかを見つけるため。対応サイトのページでなければ、その旨を出す
     */
    fun diagnose() {
        web().evaluateJavascript("window.nhSiteCheck ? nhSiteCheck() : null") { r ->
            val text = runCatching { JSONTokener(r).nextValue() as? String }.getOrNull()
            showTextDialog("このページの表示の診断", text ?: "このページは診断できません。対応サイト (なろう・ハーメルン・カクヨム) のページで開いてください。")
        }
    }

    /** 文を、選んでコピーできる形で出す (等幅)。「コピー」で全部をクリップボードへ */
    private fun showTextDialog(title: String, text: String) {
        val body = TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            val pad = (16 * dp).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        if (activity.isFinishing || activity.isDestroyed) return // ページの答えを待つ間に画面が閉じていたら出さない (落ちないように)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton("コピー") { _, _ ->
                Clip.copy(activity, title, text)
                Messages.show(activity, "コピーしました")
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

}
