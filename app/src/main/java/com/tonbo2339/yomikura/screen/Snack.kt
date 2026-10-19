package com.tonbo2339.yomikura

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 下に出るお知らせのバー (スナックバー)。トーストは Android 12 から 2 行までで、押せるボタンも付けられないので、更新の結果などはこれで出す。
 * 文字は折り返して全部出す。action があればボタンを付ける。しばらくすると消える。押すと閉じる。
 */
class Snack(ctx: Context, dp: Float) {
    private val text = TextView(ctx).apply { textSize = 14f; setTextColor(0xFFF2EDE7.toInt()); setLineSpacing(0f, 1.15f) }
    private val action = TextView(ctx).apply {
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF5CC58C.toInt())
        setPadding((12 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
    }
    private val hide = Runnable { view.animate().alpha(0f).setDuration(180).withEndAction { view.visibility = View.GONE }.start() }

    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        elevation = 6 * dp
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt())
        background = GradientDrawable().apply { setColor(0xF02B2621.toInt()); cornerRadius = 14 * dp }
        isClickable = true
        setOnClickListener { hide.run() }
        addView(text, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(action, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    fun show(message: String, button: Pair<String, () -> Unit>? = null) {
        view.removeCallbacks(hide)
        view.animate().cancel()
        text.text = message
        action.visibility = if (button == null) View.GONE else View.VISIBLE
        action.text = button?.first.orEmpty()
        action.setOnClickListener { hide.run(); button?.second?.invoke() }
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(160).start()
        view.postDelayed(hide, if (button == null && message.length < 30) 3500L else 7000L)
    }
}
