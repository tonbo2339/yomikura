package com.tonbo2339.yomikura

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher

/**
 * ページ内検索のバー (WebView の上に重ねる)。文字を入れると見つかった所を黄色く示し、「3/12」と件数を出す。▲▼で前後へ、×で閉じる。
 * WebView の findAllAsync / findNext をそのまま使う (ページの JS には触れない)。
 */
class FindBar(private val ctx: Context, private val webView: WebView, bg: Int, fg: Int, sub: Int, private val dp: Float) {
    val view = LinearLayout(ctx)
    private val input = EditText(ctx)
    private val count = TextView(ctx)
    private val icons = mutableListOf<ImageView>()

    val isShowing: Boolean get() = view.visibility == View.VISIBLE

    init {
        view.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            elevation = 6 * dp
            setPadding((14 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
        }
        input.apply {
            hint = "ページ内を検索"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            textSize = 16f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = search(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { webView.findNext(true); true } else false
            }
        }
        count.apply { textSize = 13f; setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0) }
        view.addView(input, LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f))
        view.addView(count, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        view.addView(button(R.drawable.ic_keyboard_arrow_up, "前へ") { webView.findNext(false) })
        view.addView(button(R.drawable.ic_keyboard_arrow_down, "次へ") { webView.findNext(true) })
        view.addView(button(R.drawable.ic_close, "閉じる") { hide() })
        webView.setFindListener { active, total, _ ->
            count.text = when {
                input.text.isNullOrEmpty() -> ""
                total == 0 -> "0 件"
                else -> "${active + 1} / $total"
            }
        }
        applyColors(bg, fg, sub)
    }

    /** 色を塗る (作ったときと、ライト / ダークが変わったとき。MainActivity.onConfigurationChanged) */
    fun applyColors(bg: Int, fg: Int, sub: Int) {
        view.setBackgroundColor(bg)
        input.setTextColor(fg)
        input.setHintTextColor(sub)
        count.setTextColor(sub)
        icons.forEach { it.imageTintList = ColorStateList.valueOf(fg) }
    }

    private fun button(icon: Int, desc: String, onClick: () -> Unit): View {
        val size = (44 * dp).toInt()
        return FrameLayout(ctx).apply {
            contentDescription = desc
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(ctx).apply {
                setImageResource(icon)
                icons += this
            }, FrameLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt(), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun search(q: String) {
        if (q.isEmpty()) { webView.clearMatches(); count.text = "" } else webView.findAllAsync(q)
    }

    fun show() {
        view.visibility = View.VISIBLE
        input.requestFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        if (!isShowing) return
        webView.clearMatches()
        input.setText("")
        count.text = ""
        view.visibility = View.GONE
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        webView.requestFocus()
    }
}
