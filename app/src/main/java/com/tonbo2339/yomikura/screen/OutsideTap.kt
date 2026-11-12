package com.tonbo2339.yomikura

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * メニュー (PopupWindow) の外を押して閉じたとき、その位置にあったバーのタブ・ボタンを「押したことにする」。
 * 何もしないと、ポップアップは外の押下を自分で使い切るので、メニューを開いたまま別のタブを押すと、閉じるだけで、次のメニューは開かない
 * (もう一度押さないと開かなかった)。メニューを出した部品自身を押したときは、これまでどおり閉じるだけ (開閉の切り替え)。
 * ページ (WebView) への押下は押したことにしない (ページを誤って押さないため)。
 */
object OutsideTap {
    /** anchor = メニューを出した部品。rawX / rawY = 外を押した画面上の位置 (MotionEvent の rawX / rawY)。 */
    fun press(anchor: View, rawX: Float, rawY: Float) {
        val hit = clickableAt(anchor.rootView, rawX, rawY) ?: return
        if (hit === anchor) return
        hit.post { if (hit.isAttachedToWindow) hit.performClick() } // ポップアップが閉じ終わってから
    }

    /** root の中で、画面上の (x, y) にある一番手前の「押せる部品」。無ければ null。 */
    internal fun clickableAt(root: View, x: Float, y: Float): View? {
        val loc = IntArray(2)
        fun walk(v: View): View? {
            if (v.visibility != View.VISIBLE || v is WebView) return null
            v.getLocationOnScreen(loc)
            if (x < loc[0] || x >= loc[0] + v.width || y < loc[1] || y >= loc[1] + v.height) return null
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) walk(v.getChildAt(i))?.let { return it }
            return if (v.isEnabled && v.hasOnClickListeners()) v else null
        }
        return walk(root)
    }
}
