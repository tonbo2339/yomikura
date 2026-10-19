package com.tonbo2339.yomikura

import android.content.Context
import android.view.View

/** 読み込みの進み具合を示す細い線 (WebView の上端に重ねる)。 */
class LoadBar(ctx: Context) {
    val view = View(ctx).apply { setBackgroundColor(BarTheme.ACCENT); visibility = View.GONE; pivotX = 0f; scaleX = 0f }

    /** percent = 0〜100。100 で消す。show = false (アプリ内の画面・設定がオフ) のときは出さない。 */
    fun update(percent: Int, show: Boolean) {
        view.animate().cancel()
        if (!show) { view.visibility = View.GONE; return }
        if (percent >= 100) {
            view.animate().scaleX(1f).setDuration(120).withEndAction { view.visibility = View.GONE; view.scaleX = 0f }.start()
        } else {
            view.visibility = View.VISIBLE
            view.scaleX = maxOf(percent, 5) / 100f
        }
    }
}
