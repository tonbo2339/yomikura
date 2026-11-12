package com.tonbo2339.yomikura

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 上部バー右端の「⋮」から開くメニュー。Chrome / Edge のように、上段に記号付きのボタン (戻る・進む・先頭へ・末尾へ)、
 * 下段に記号付きの項目を並べる。使えない項目は薄く出し、押しても何も起きない。
 */
/** いまメニューが開いているか (開いている間は、端から出したバーを隠さない)。[opened] で分かる */
class MainMenu(
    private val ctx: Context,
    private val dp: Float,
    private val bg: Int,
    private val fg: Int,
    private val sub: Int,
    private val line: Int,
    private val ripple: Int,
) {
    /** 1 つの操作。icon = 記号 (drawable)、enabled = 今使えるか */
    class Action(val icon: Int, val label: String, val enabled: Boolean = true, val run: () -> Unit)

    private var popup: PopupWindow? = null

    companion object {
        /** 開いているメニューの数 */
        var opened = 0
            private set
        /** 画面を作り直したとき (回転・大きさの変更など) は、前の画面のメニューが閉じた知らせを受け取れないので、数え直す */
        fun resetOpened() { opened = 0 }
        private var current: PopupWindow? = null
        /** 開いているメニューを閉じる (回転・窓の大きさの変更で、押したボタンの位置が動くので) */
        fun dismissOpen() { current?.dismiss() }
        /** メニューが開いた (true) / 全部閉じた (false) ときに呼ぶ。UI スレッド */
        var onOpenChanged: ((Boolean) -> Unit)? = null
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun tint(icon: ImageView, enabled: Boolean) {
        icon.imageTintList = ColorStateList.valueOf(fg)
        icon.alpha = if (enabled) 1f else 0.35f
    }

    private fun press(view: View, a: Action) {
        view.isEnabled = a.enabled
        if (a.enabled) view.setOnClickListener { popup?.dismiss(); a.run() }
        view.background = RippleDrawable(ColorStateList.valueOf(ripple), null, ColorDrawable(0xFFFFFFFF.toInt()))
    }

    /** 上段: 記号を大きく、下に短い名前。均等に並べる。 */
    private fun topRow(actions: List<Action>): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(4), px(6), px(4), px(6)) }
        actions.forEach { a ->
            val cell = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(6)) }
            val icon = ImageView(ctx).apply { setImageResource(a.icon); tint(this, a.enabled) }
            cell.addView(icon, LinearLayout.LayoutParams(px(26), px(26)))
            cell.addView(TextView(ctx).apply {
                text = a.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (a.enabled) fg else sub)
                alpha = if (a.enabled) 1f else 0.5f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(2) })
            press(cell, a)
            row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    /** 下段: 記号 + 名前。 */
    private fun listItem(a: Action): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), 0, px(16), 0) }
        val icon = ImageView(ctx).apply { setImageResource(a.icon); tint(this, a.enabled) }
        row.addView(icon, LinearLayout.LayoutParams(px(22), px(22)))
        row.addView(TextView(ctx).apply {
            text = a.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(if (a.enabled) fg else sub)
            alpha = if (a.enabled) 1f else 0.5f
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(16) })
        press(row, a)
        return row.also { it.minimumHeight = px(48) }
    }

    private fun divider() = View(ctx).apply { setBackgroundColor(line) }

    /**
     * anchor の下に出す。top = 上段の記号ボタン (空なら出さない)、groups = 下段 (グループごとに区切り線を入れる)。
     * alignEnd = anchor の右端にそろえる (右端のボタン用)。false なら左端にそろえる (左側のタブ用)。
     */
    fun show(anchor: View, top: List<Action>, groups: List<List<Action>>, alignEnd: Boolean = true, widthDp: Int = 310) {
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        groups.forEachIndexed { i, g ->
            if (top.isNotEmpty() || i > 0) content.addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, px(1) / 2)))
            g.forEach { content.addView(listItem(it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        }
        if (top.isNotEmpty()) content.addView(topRow(top), 0)
        val wpx = px(widthDp)
        content.measure(View.MeasureSpec.makeMeasureSpec(wpx, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        // 収まるかどうかは、画面ではなく、押したボタンのあるウィンドウの広さで見る (分割画面・フリーフォームでは、ウィンドウは画面の一部)。座標もウィンドウの中のもの
        val root = anchor.rootView
        val winW = root.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val winH = root.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels
        // 画面の高さより長いとき (スマホの横向きなど) は、縦に動かせるようにして、最後の項目も押せるようにする
        val limit = winH - px(48)
        val scrolls = content.measuredHeight > limit
        val h = minOf(content.measuredHeight, limit)
        val body: View = if (scrolls) android.widget.ScrollView(ctx).apply { addView(content) } else content
        val w = PopupWindow(body, wpx, if (scrolls) h else LinearLayout.LayoutParams.WRAP_CONTENT, true)
        w.setBackgroundDrawable(GradientDrawable().apply { setColor(bg); cornerRadius = 14 * dp; setStroke(maxOf(1, px(1) / 2), line) })
        w.elevation = 12 * dp
        w.isOutsideTouchable = true
        popup = w
        current = w
        opened++
        onOpenChanged?.invoke(true)
        // 外を押して閉じたときの位置を覚えておく (閉じたあと、そこにあるバーのボタンを押したことにする。OutsideTap)
        var outside: Pair<Float, Float>? = null
        // 外の押下は、ACTION_OUTSIDE か、メニューの外の座標の ACTION_DOWN で届く (実機では、後者で届いた)
        w.setTouchInterceptor { v, ev ->
            val out = ev.x < 0 || ev.y < 0 || ev.x >= v.width || ev.y >= v.height
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE || (ev.actionMasked == MotionEvent.ACTION_DOWN && out)) outside = ev.rawX to ev.rawY
            false
        }
        w.setOnDismissListener {
            opened--; if (current === w) current = null; onOpenChanged?.invoke(opened > 0)
            outside?.let { (x, y) -> OutsideTap.press(anchor, x, y) }
        }
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        if (anchor.tag == SiteTabs.RAIL_TAG) {
            // 左右のバーのタブ: バーの横に出す (バーの上に重ねると、ほかのボタンや押したタブ自身が隠れて、別のタブを押せない・同じタブで閉じられない)
            val (x, y) = MenuPlacement.beside(loc[0], loc[1], anchor.width, anchor.height, wpx, h, winW, winH, px(8))
            w.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
            return
        }
        // 下に収まるときは、ふつうに押したボタンの下へ。画面の下の方 (左右のバーの ⋮ など) で下に収まらないときや、横にはみ出すときは、
        // ボタンの横・上へずらして、全部が画面に入る位置に出す
        val fitsBelow = winH - (loc[1] + anchor.height) >= h
        val x0 = if (alignEnd) loc[0] + anchor.width - wpx else loc[0]
        val fitsSide = x0 >= 0 && x0 + wpx <= winW
        val m = px(8)
        if (fitsBelow && fitsSide) {
            w.showAsDropDown(anchor, 0, 0, if (alignEnd) Gravity.END else Gravity.START)
        } else if (fitsBelow) {
            // 下には収まるが、横にはみ出す (上のバーの右寄りのタブなど): ボタンの下のまま、横だけ画面に収まる所へ寄せる
            // (下の「ボタンの左隣へ」へ回すと、左端まで飛んでしまい、押したタブの下から離れる)
            w.showAtLocation(root, Gravity.TOP or Gravity.START, x0.coerceIn(m, maxOf(m, winW - wpx - m)), loc[1] + anchor.height)
        } else {
            var x = x0
            if (x < m) x = loc[0] + anchor.width                      // 左へはみ出す: ボタンの右隣へ
            if (x + wpx > winW - m) x = loc[0] - wpx        // 右へはみ出す: ボタンの左隣へ
            x = x.coerceIn(m, maxOf(m, winW - wpx - m))
            var y = if (fitsBelow) loc[1] + anchor.height else loc[1] + anchor.height - h // 下に収まらないときは、ボタンの下端にそろえて上へ伸ばす
            y = y.coerceIn(m, maxOf(m, winH - h - m))
            w.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
        }
    }
}
