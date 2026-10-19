package com.tonbo2339.yomikura

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 確認画面 (Cloudflare) で止まった巡回用の WebView を、全画面で出して通してもらう。
 * 閉じても WebView は壊さない (持ち主の Collector が、通ったあとの取得を続ける。壊すのも Collector)。
 */
class ChallengeScreen(private val activity: Activity) {
    private var dialog: Dialog? = null
    /** いま出しているサイト (閉じたら null) */
    private var shownSite: String? = null

    fun show(site: String, title: String, web: WebView) {
        dismiss()
        if (activity.isFinishing || activity.isDestroyed) return
        (web.parent as? ViewGroup)?.removeView(web)
        val dp = activity.resources.displayMetrics.density
        val close = Button(activity).apply { text = "閉じる" }
        // 通せなかったとき (チェックが失敗した等): もう一度そのページにアクセスする
        val reload = Button(activity).apply { text = "再読み込み"; setOnClickListener { web.reload() } }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#2b2621"))
            setPadding((16 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            addView(TextView(activity).apply { text = title; setTextColor(Color.WHITE); textSize = 15f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reload)
            addView(close)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * dp).toInt()))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // 状態バー・ナビゲーションバーに重ならないようにする
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        val d = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar).apply {
            setContentView(root)
            setOnDismissListener {
                (web.parent as? ViewGroup)?.removeView(web) // Collector が壊せるよう、外しておく
                if (dialog === this) { dialog = null; shownSite = null }
            }
        }
        close.setOnClickListener { d.dismiss() }
        dialog = d
        shownSite = site
        d.show()
    }

    /** いま出しているのが site の画面なら閉じる (site を指定しなければ、何でも閉じる) */
    fun dismiss(site: String? = null) {
        if (site != null && shownSite != site) return
        dialog?.dismiss()
    }
}
