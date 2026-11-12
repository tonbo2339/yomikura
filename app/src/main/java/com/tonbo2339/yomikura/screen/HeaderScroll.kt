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

    // ---- 指の動きを、先にバーに使わせる (ずらす方式。Chrome と同じ「バーが先、残りがページ」) ----
    // 本文の箱を動かすと、指が止まっていても、WebView から見た指の位置 (箱の中の座標) が動く。スクロールの通知を受けてから箱を動かすと、
    // それを指の動きと取り違えて、毎コマ「行き過ぎて戻る」(指を止めても往復する) ので、指の動きを WebView に渡す前にバーへ使い、
    // 使った分だけ座標を直して、WebView には残りだけを渡す。

    private var touching = false
    private val touchDelta = TouchDelta()

    /**
     * 本文の箱へ届く指の動き (ev) を、渡す前に見る。active = この画面でバーを動かしてよい。
     * 動く方向に動かせる分だけバーを動かし、WebView から見ると、その分は指が動かなかったことにする。
     */
    fun onTouch(ev: android.view.MotionEvent, active: Boolean) {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { touching = pushContent != null && active; touchDelta.down(ev.rawY); startGate.reset() }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = touchDelta.move(ev.rawY, ev.pointerCount)
                if (!touching || !active || pushContent == null || ev.pointerCount > 1) return
                val used = touchConsume(dy)
                if (used != 0f) ev.offsetLocation(0f, -used)
            }
            // 指を離したとき、バーが途中ならそのまま (先頭でも、途中で止めたら途中で止まる)。
            // 先頭に着いたときの「出す」は、指を離したあとのスクロールの知らせ (onScroll) が行う
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> touching = false
        }
    }

    /** 指が dy (下が +) だけ動いた。バーが使った分 (バーの高さの変わり。出る向きが +) を返し、バーを動かす */
    private fun touchConsume(dy: Float): Float {
        if (dy == 0f) return 0f
        if (!startFollowFor(dy)) return 0f
        return moveShown(dy)
    }

    /** バーを動かし始める (動かない向きなら false)。隠れていれば出す向き (dy > 0) だけ、出ていれば隠す向き (dy < 0) だけ */
    private fun startFollowFor(dy: Float): Boolean {
        if (following) return true
        if (hidden) {
            if (dy < 0) return false
            beginFollow(0f)
        } else {
            if (dy > 0 || header.height <= 0) return false
            fullHeight = header.height
            anim?.cancel(); anim = null
            pushBegin(); pushTo(fullHeight)
            following = true
        }
        return true
    }

    /**
     * ずらす方式で、バーの見えている高さを dy (出る向きが +) だけ変える。バーが使った分を返す。
     * 小数のまま使う (ゆっくりの動きは 1 回が 1px に満たない。整数にすると切り捨てられて、バーが動かず、WebView だけが動く)
     */
    private fun moveShown(dy: Float): Float {
        anim?.cancel(); anim = null
        val cur = fullHeight + header.translationY
        val h = BarScrollRules.shownHeight(cur, dy, fullHeight.toFloat())
        when {
            h >= fullHeight -> showNow()
            h <= 0f -> { stopFollow(); hidden = true; hideEnd() }
            else -> pushTo(h)
        }
        return h - cur
    }

    /**
     * WebView がスクロールした。locked = 動かさない画面 (読書画面は reader.js が出し入れする / アプリ内ページ / 設定がオフ) のときは、隠れていれば出すだけ。
     */
    fun onScroll(scrollY: Int, oldY: Int, locked: Boolean) {
        if (locked) {
            if (hidden || following) showNow() // 途中で止まったバーも、動かさない画面では出し切る
            return
        }
        // 先頭に着いたら、猶予の間でも必ず出す (動きの直後に着くと、この通知が捨てられて、先頭なのにバーが隠れたままになる)
        if (scrollY <= 0) { accum = 0f; if (!(touching && pushContent != null)) setHidden(false); return } // 指で動かしている間は、指を離したときに出す (onTouch)
        if (SystemClock.uptimeMillis() < ignoreUntil) return
        val dy = (scrollY - oldY).toFloat()
        if (pushContent != null) { if (!touching) pushScroll(dy, scrollY); return } // 指で動かしている間は、バーは onTouch が動かす
        if (dy > 0) {
            accum = maxOf(0f, accum) + dy
            if (!hidden && accum > 24 * dp && scrollY > 56 * dp) { accum = 0f; setHidden(true) }
        } else if (dy < 0) {
            accum = minOf(0f, accum) + dy
            if (following) grow(-dy)
            else if (hidden && accum < -16 * dp && over == null) { val start = -accum; accum = 0f; beginFollow(start) }
            else if (hidden && accum < -16 * dp) { accum = 0f; setHidden(false) } // 重ねるとき (縦書き) は、これまでどおり固定の長さで出す
        }
    }

    // ---- 戻すとき (横書き): 上へ動かした量だけ、バーを伸ばして出す (指のスクロールと同じ速さ)。止まったら残りを出す ----

    /** 指に合わせて伸ばしている途中 (hidden は false、高さは fullHeight 未満) */
    private var following = false
    private val settle = Runnable { settleFollow() }
    /** ずらす方式で、バーが動き出す前の、同じ向きの動きの積算 */
    private val startGate = StartGate(START_DP * dp)

    /** 途中で止まっている (伸ばしかけ・隠しかけ) か、隠れている。新しいページ・バーの作り直しでは、出し直して測り直す */
    val displaced get() = hidden || following

    /** 画面の大きさが変わった (回転・分割画面): 途中のバーは、本文の箱の高さが古いままになるので出し切る */
    fun onResized() { if (following) showNow() }

    /** いま見えているバーの高さ (px)。ずらす方式 (push) では、バーの箱の高さは変えず、ずらし量から求める */
    private fun currentHeight() =
        if (pushContent != null) (fullHeight + header.translationY).toInt()
        else header.layoutParams.height.takeIf { it >= 0 } ?: header.height

    private fun beginFollow(px: Float) {
        anim?.cancel()
        anim = null
        if (pushContent != null) { pushBegin(); pushTo(0f) }
        header.visibility = View.VISIBLE
        hidden = false
        following = true
        grow(px)
    }

    private fun grow(px: Float) {
        val h = minOf(fullHeight, currentHeight() + px.toInt())
        if (h >= fullHeight) { showNow(); return }
        setHeight(h)
        if (pushContent != null) return // ずらす方式は、止まっても出し切らない (途中で止めたら、バーも途中で止まる)
        header.removeCallbacks(settle)
        header.postDelayed(settle, SETTLE_MS)
    }

    /** スクロールが止まった (ずらさない方式): 伸ばしかけのバーを、残りの分だけ出す。ずらす方式では、出すよう頼まれたとき (先頭に着いた・setHidden(false)) だけ */
    private fun settleFollow() {
        if (!following) return
        following = false
        animateHeight(currentHeight(), fullHeight) { showNow() }
    }

    /**
     * ずらす方式 (横書き): バーを、指のスクロールと同じ量だけ動かす (下へ動かした分だけ隠れ、上へ戻した分だけ出る)。
     * 止まっても、出し切る・隠し切るはしない (途中で止めたら、バーも途中で止まる。半分で自動で出る動きは、ガクつきに見えた)。
     * 大きさは変えないので、毎回の通知で動かしてよい。先頭に着いたとき・メニューなどが出すよう頼んだときは、必ず出す
     */
    private fun pushScroll(dy0: Float, scrollY: Int) {
        if (dy0 == 0f) return
        var dy = dy0
        if (!following) {
            // 動き出しは、同じ向きに一定量たまってから (WebView の大きさを変えると、スクロール位置が 1〜2px ずれて通知される。
            // それを指の動きと取り違えて、指を止めていても出たり消えたりを繰り返さないため)。たまった分は、動き出しの 1 回目にまとめて動かす
            dy = startGate.feed(dy) ?: return
            if (!startFollowFor(-dy)) return // 隠れた所からは上へ戻した分だけ出し、出し切った所からは下へ動かした分だけ隠す
        }
        moveShown(-dy)
    }

    private fun animateHeight(from: Int, to: Int, onEnd: () -> Unit) {
        anim?.cancel()
        if (pushContent == null) ignoreUntil = SystemClock.uptimeMillis() + ANIM_MS + 120 // 大きさが変わらないずらし方式は、無視しなくてよい
        anim = ValueAnimator.ofInt(from, to).apply {
            duration = ANIM_MS
            addUpdateListener { setHeight(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) onEnd() }
            })
            start()
        }
    }

    private fun stopFollow() {
        following = false
        header.removeCallbacks(settle)
    }

    // ---- 重ねるとき (縦書きで読む間): バーを本文の箱 (WebView) の上に重ね、出入りしても本文の大きさを変えない ----
    // 縦書きの文字の長さは画面の高さで決まるので、バーの出入りで WebView の高さが変わると、本文が組み直されて動いてしまう。
    // 重ねるあいだは、バーの高さは縮めず (上へずらすだけ)、本文の箱を、親いっぱいの高さにして、バーの高さの分だけ上へ潜らせる。

    /** バーの下に潜らせる本文の箱と、その親 (高さの元)。null = ふつう (バーの高さを縮めて、本文を広げる) */
    private var over: View? = null
    private var box: View? = null
    private val keepOver = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncOver() }

    /** いま、本文に重ねているか */
    val overlaying get() = over != null

    /**
     * バーを本文に重ねる (content = 本文の箱、container = バーと本文の箱を縦に並べている親、bg = バーの背景色)。null で、ふつうに戻す。
     * バーの箱自身には背景が無い (後ろの色が透けているだけ) ので、重ねるあいだは bg で塗る (塗らないと、下の本文が透けて見える)。色の変わりのたびに呼んでよい
     */
    fun setOverlay(content: View?, container: View?, bg: Int) {
        if (content === over) { if (content != null) header.setBackgroundColor(bg); return }
        anim?.cancel()
        over?.let { release(it) }
        over = content
        box = container
        showNow() // 縮めた高さ・ずらした位置は引き継がない (新しいページは、バーを出して始める)
        if (content != null && container != null) {
            header.setBackgroundColor(bg)
            header.elevation = OVER_ELEVATION_DP * dp // 本文の箱より手前に描く (本文の箱は、バーの下に潜っている)
            header.addOnLayoutChangeListener(keepOver)
            container.addOnLayoutChangeListener(keepOver)
            syncOver()
        }
    }

    private fun release(content: View) {
        header.removeOnLayoutChangeListener(keepOver)
        box?.removeOnLayoutChangeListener(keepOver)
        header.elevation = 0f
        header.background = null
        (content.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.topMargin = 0; lp.height = 0; lp.weight = 1f
            content.layoutParams = lp
        }
    }

    /** 本文の箱の高さを親いっぱいにして、バーの高さの分だけ上へ。バーの高さや親の高さが変わるたびに合わせる */
    private fun syncOver() {
        val content = over ?: return
        // 隠している間は、バーの高さが変わっても (状態バー分の余白が、隠したあとに付くなど)、全部が画面の外に出る位置に合わせる
        // (隠すときの高さのままだと、増えた分だけバーが画面に残る)
        if (hidden && anim?.isRunning != true && header.height > 0) header.translationY = -header.height.toFloat()
        val parent = box ?: return
        if (parent.height <= 0 || header.height <= 0) return // まだ測れていない
        val lp = content.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.topMargin == -header.height && lp.height == parent.height && lp.weight == 0f) return
        lp.topMargin = -header.height; lp.height = parent.height; lp.weight = 0f
        content.layoutParams = lp
    }

    /** 重ねているときの出し入れ: 高さは変えず、バーを上へずらして隠す / 戻して出す */
    private fun slide(hide: Boolean) {
        if (hide == hidden) return
        if (hide && header.height <= 0) return
        fullHeight = header.height
        hidden = hide
        anim?.cancel()
        anim = ValueAnimator.ofFloat(header.translationY, if (hide) -fullHeight.toFloat() else 0f).apply {
            duration = ANIM_MS
            addUpdateListener { header.translationY = it.animatedValue as Float }
            start()
        }
    }

    /** バーを動かして隠す / 出す。 */
    fun setHidden(hide: Boolean) {
        if (over != null) { slide(hide); return }
        if (!hide && following) { header.removeCallbacks(settle); settleFollow(); return } // 伸ばしかけ: 残りを出す
        if (hide == hidden) return
        val keep = if (following) currentHeight() else -1 // 途中 (ずらす方式) からは、いまの位置から隠す
        stopFollow()
        if (hide) {
            if (header.height <= 0) return
            fullHeight = header.height
            if (pushContent != null) { pushBegin(); pushTo(if (keep >= 0) keep else fullHeight) } // 本文の箱を広げる分、いまの見た目の位置へずらしておく
        } else {
            if (pushContent != null && header.visibility != View.VISIBLE) { pushBegin(); pushTo(0f) } // 先に本文の箱を広い形にしてから、バーを出す
            if (header.visibility != View.VISIBLE) header.visibility = View.VISIBLE
        }
        val from = currentHeight()
        val to = if (hide) 0 else fullHeight
        hidden = hide
        animateHeight(from, to) { if (hide) hideEnd() else showNow() }
    }

    /** 隠し終わった: ずらし方式なら本文の箱をふつうの並びに戻し (見た目は同じ)、バーの箱をたたむ */
    private fun hideEnd() {
        restorePush()
        header.translationY = 0f
        header.visibility = View.GONE
    }

    /** アニメーションなしですぐ出す (ページを開いたとき・バーを作り直したとき・設定をオフにしたとき)。 */
    fun showNow() {
        stopFollow()
        anim?.cancel()
        anim = null
        hidden = false
        accum = 0f
        startGate.reset()
        restorePush()
        header.visibility = View.VISIBLE
        header.translationY = 0f
        val lp = header.layoutParams
        if (lp.height != WRAP_CONTENT) { lp.height = WRAP_CONTENT; header.layoutParams = lp } // 変わらないときは、レイアウトを頼まない
        shiftChildren(0f)
    }

    // ---- ずらすとき (横書き): バーを出し入れしている間、本文 (WebView) の大きさを変えない ----
    // バーの高さを毎フレーム縮めると、WebView の高さも毎フレーム変わって、ページが組み直されてガクガクする。
    // 動かしている間は、本文の箱を親いっぱいの高さのまま、バーの見えている分だけ下へずらす (大きさの変更は、始めと終わりの 1 回だけ)。

    private var pushContent: View? = null
    private var pushBox: View? = null

    /** 本文の箱 (content) と、バーと本文の箱を縦に並べる親 (container) を渡すと、ずらす方式になる。null でふつうに戻す */
    fun setPush(content: View?, container: View?) {
        if (content === pushContent) return
        anim?.cancel()
        showNow() // 前の方式の途中の形は引き継がない
        pushContent = content
        pushBox = if (content != null) container else null
    }

    /** 本文の箱を、バーの下に潜る広い形 (親いっぱい) にする。見た目の位置は、pushTo のずらしで合わせる */
    private fun pushBegin() {
        val c = pushContent ?: return
        val parent = pushBox ?: return
        val lp = c.layoutParams as? LinearLayout.LayoutParams ?: return
        if (parent.height <= 0) return
        if (lp.topMargin != -fullHeight || lp.height != parent.height || lp.weight != 0f) {
            lp.topMargin = -fullHeight; lp.height = parent.height; lp.weight = 0f
            c.layoutParams = lp
        }
    }

    /** バーの見えている高さを h にする: バーを上へ、本文の箱を下へ、同じだけずらす */
    private fun pushTo(h: Int) = pushTo(h.toFloat())
    private fun pushTo(h: Float) {
        header.translationY = h - fullHeight
        pushContent?.translationY = h
    }

    /** 本文の箱を、ふつうの並び (バーの下・残りの高さ) に戻す。ずれていたときだけ (大きさが変わるので、直後のスクロールの知らせは無視する) */
    private fun restorePush() {
        val c = pushContent ?: return
        val lp = c.layoutParams as? LinearLayout.LayoutParams ?: return
        c.translationY = 0f
        if (lp.topMargin != 0 || lp.height != 0 || lp.weight != 1f) {
            lp.topMargin = 0; lp.height = 0; lp.weight = 1f
            c.layoutParams = lp
            ignoreUntil = SystemClock.uptimeMillis() + 250
        }
    }

    /** 動いている途中のアニメーションをやめる (画面を閉じるとき)。 */
    fun cancel() { stopFollow(); anim?.cancel() }

    /** 箱の高さを h にして、中身を (その分だけ) 上へずらす。 */
    private fun setHeight(h: Int) {
        if (pushContent != null) { pushTo(h); return }
        val lp = header.layoutParams
        lp.height = h
        header.layoutParams = lp
        shiftChildren((h - fullHeight).toFloat())
    }

    private fun shiftChildren(dy: Float) {
        for (i in 0 until header.childCount) header.getChildAt(i).translationY = dy
    }

    private companion object {
        const val ANIM_MS = 160L
        /** 指のスクロールが止まってから、伸ばしかけのバーを残りまで出すまでの間 */
        const val SETTLE_MS = 140L
        /** ずらす方式で、バーが動き出すのに要る、指の動き (dp)。これより小さいずれは、レイアウトの揺れとして無視する */
        const val START_DP = 6f
        const val OVER_ELEVATION_DP = 4f
    }
}
