package com.tonbo2339.yomikura

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 上部バー右端の「⋮」から開くメニュー。Chrome / Edge のように、上段に記号付きのボタン (戻る・進む・先頭へ・末尾へ)、
 * 下段に記号付きの項目を並べる。使えない項目は薄く出し、押しても何も起きない。
 */
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
        val w = PopupWindow(content, px(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT, true)
        w.setBackgroundDrawable(GradientDrawable().apply { setColor(bg); cornerRadius = 14 * dp; setStroke(maxOf(1, px(1) / 2), line) })
        w.elevation = 12 * dp
        w.isOutsideTouchable = true
        popup = w
        w.showAsDropDown(anchor, 0, 0, if (alignEnd) Gravity.END else Gravity.START)
    }
}
