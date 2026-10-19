package com.tonbo2339.yomikura

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout

/**
 * 上部バーのスクロール連動 (Chrome のように、下へスクロールすると隠し、上へ戻すと出す)。
 * サイトのページだけ。アプリ内のページ (リスト・設定など) では隠さない。設定 settings.hideBarOnScroll (既定オン)。
 * 隠すときは header の高さを 0 まで縮め、中身を上へずらして「せり上がる」ように見せる (WebView はその分だけ広がる)。
 */
class HeaderScroll(private val header: LinearLayout, private val dp: Float) {

    var hidden = false
        private set
    private var fullHeight = 0
    private var anim: ValueAnimator? = null
    /** 下 / 上へ動いた量の積算 (向きが変わると 0 から数え直す) */
    private var accum = 0f
    /** バーが動いている間は、WebView の広がり縮みで起きるスクロールの知らせを無視する (ページ末尾で出たり消えたりを繰り返さないため) */
    private var ignoreUntil = 0L

    /**
     * WebView がスクロールした。locked = 動かさない画面 (読書画面は reader.js が出し入れする / アプリ内ページ / 設定がオフ) のときは、隠れていれば出すだけ。
     */
    fun onScroll(scrollY: Int, oldY: Int, locked: Boolean) {
        if (locked) {
            if (hidden) showNow()
            return
        }
        if (SystemClock.uptimeMillis() < ignoreUntil) return
        if (scrollY <= 0) { accum = 0f; setHidden(false); return }
        val dy = (scrollY - oldY).toFloat()
        if (dy > 0) {
            accum = maxOf(0f, accum) + dy
            if (!hidden && accum > 24 * dp && scrollY > 56 * dp) { accum = 0f; setHidden(true) }
        } else if (dy < 0) {
            accum = minOf(0f, accum) + dy
            if (hidden && accum < -16 * dp) { accum = 0f; setHidden(false) }
        }
    }

    /** バーを動かして隠す / 出す。 */
    fun setHidden(hide: Boolean) {
        if (hide == hidden) return
        if (hide) {
            if (header.height <= 0) return
            fullHeight = header.height
        } else if (header.visibility != View.VISIBLE) {
            header.visibility = View.VISIBLE
        }
        val from = (header.layoutParams.height.takeIf { it >= 0 } ?: header.height)
        val to = if (hide) 0 else fullHeight
        hidden = hide
        anim?.cancel()
        ignoreUntil = SystemClock.uptimeMillis() + ANIM_MS + 120
        anim = ValueAnimator.ofInt(from, to).apply {
            duration = ANIM_MS
            addUpdateListener { setHeight(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (cancelled) return
                    if (hide) header.visibility = View.GONE else showNow()
                }
            })
            start()
        }
    }

    /** アニメーションなしですぐ出す (ページを開いたとき・バーを作り直したとき・設定をオフにしたとき)。 */
    fun showNow() {
        anim?.cancel()
        anim = null
        hidden = false
        accum = 0f
        header.visibility = View.VISIBLE
        val lp = header.layoutParams
        lp.height = WRAP_CONTENT
        header.layoutParams = lp
        shiftChildren(0f)
    }

    /** 動いている途中のアニメーションをやめる (画面を閉じるとき)。 */
    fun cancel() { anim?.cancel() }

    /** 箱の高さを h にして、中身を (その分だけ) 上へずらす。 */
    private fun setHeight(h: Int) {
        val lp = header.layoutParams
        lp.height = h
        header.layoutParams = lp
        shiftChildren((h - fullHeight).toFloat())
    }

    private fun shiftChildren(dy: Float) {
        for (i in 0 until header.childCount) header.getChildAt(i).translationY = dy
    }

    private companion object { const val ANIM_MS = 160L }
}
